package com.agri.pit.backfill;

import com.agri.pit.catalog.CatalogService;
import com.agri.pit.common.ApiException;
import com.agri.pit.common.Db;
import com.agri.pit.common.GeoUtil;
import com.agri.pit.common.Times;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.locationtech.jts.geom.Geometry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Historical availability scanner. For every historical case it replays the question
 * "could this feature have been computed at the case's own scoring cutoff?" against the
 * dependency graph, using only {@code ingested_at <= cutoff} visibility.
 * <p>
 * The scanner never adopts post-cutoff data to make a case look computable. In particular a
 * planting declaration whose {@code ingested_at} is later than the case cutoff is flagged
 * {@code LOOKAHEAD_RISK} (穿越风险) — the case is classified {@code POST_HOC_ONLY} for that
 * feature and is excluded from evaluation backfills by default.
 */
@Service
public class AvailabilityScanner {

    private final Db db;
    private final CatalogService catalog;
    private final DependencyGraphService dependencies;
    private final GeoUtil geo;

    public AvailabilityScanner(Db db, CatalogService catalog, DependencyGraphService dependencies,
                               GeoUtil geo) {
        this.db = db;
        this.catalog = catalog;
        this.dependencies = dependencies;
        this.geo = geo;
    }

    private record CaseRow(long id, String caseNo, long parcelId, LocalDate hazardDate,
                           Instant scoringCutoff, String cropCode, long boundaryVersionId,
                           com.fasterxml.jackson.databind.JsonNode boundaryGeometry) {
    }

    private record DepVerdict(String depKey, String status, String reason, ObjectNode evidence) {
    }

    @Transactional
    public long scan(String featureCode, LocalDate hazardFrom, LocalDate hazardTo, String createdBy) {
        CatalogService.FeatureRow feature = catalog.requireFeature(featureCode);
        List<DependencyGraphService.Dependency> deps = dependencies.dependenciesFor(feature.id());
        if (deps.isEmpty()) {
            throw ApiException.unprocessable("NO_DEPENDENCIES",
                    "feature " + feature.code() + " has no registered dependencies; "
                            + "register the dependency graph before scanning");
        }
        LocalDate from = hazardFrom != null ? hazardFrom : LocalDate.now(Times.UTC).minusYears(3);
        List<CaseRow> cases = loadCases(from, hazardTo);
        Long currentVersionId = db.jdbc().query("""
                        select current_version_id from feature_definition where id = ?
                        """, rs -> rs.next() ? (Long) rs.getObject(1) : null, feature.id());

        long scanId = db.insert("""
                        insert into availability_scan
                            (feature_definition_id, definition_version_id, hazard_from, hazard_to,
                             status, created_by)
                        values (?, ?, ?, ?, 'RUNNING', ?)
                        """,
                feature.id(), currentVersionId, from, hazardTo,
                createdBy == null || createdBy.isBlank() ? "availability-scanner" : createdBy.trim());

        int evaluable = 0;
        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        for (CaseRow c : cases) {
            List<DepVerdict> verdicts = new ArrayList<>();
            for (DependencyGraphService.Dependency dep : deps) {
                verdicts.add(evaluate(dep, c));
            }
            String featureStatus = combine(verdicts);
            String caseClass = switch (featureStatus) {
                case "AVAILABLE", "DEGRADED" -> "EVALUABLE";
                default -> "POST_HOC_ONLY";
            };
            if ("EVALUABLE".equals(caseClass)) {
                evaluable++;
            }
            statusCounts.merge(featureStatus, 1, Integer::sum);

            ArrayNode depJson = db.mapper().createArrayNode();
            ArrayNode reasons = db.mapper().createArrayNode();
            for (DepVerdict verdict : verdicts) {
                ObjectNode node = db.mapper().createObjectNode();
                node.put("depKey", verdict.depKey());
                node.put("status", verdict.status());
                node.put("reason", verdict.reason());
                node.set("evidence", verdict.evidence());
                depJson.add(node);
                if (!"AVAILABLE".equals(verdict.status())) {
                    reasons.add(verdict.depKey() + ": " + verdict.reason());
                }
            }
            db.insert("""
                            insert into availability_scan_item
                                (scan_id, case_id, feature_code, feature_status, case_class,
                                 dependencies, reasons)
                            values (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                            """,
                    scanId, c.id(), feature.code(), featureStatus, caseClass,
                    db.jsonArg(depJson), db.jsonArg(reasons));
        }
        ObjectNode summary = db.mapper().createObjectNode();
        statusCounts.forEach(summary::put);
        summary.put("note",
                "EVALUABLE cases had every required input available at their own scoring cutoff and "
                        + "enter experiment metrics; POST_HOC_ONLY cases carried look-ahead risk or missing "
                        + "inputs and may only be used for supplementary post-hoc analysis.");
        db.jdbc().update("""
                        update availability_scan
                           set status = 'COMPLETED', case_count = ?, evaluable_count = ?,
                               post_hoc_count = ?, summary = ?::jsonb
                         where id = ?
                        """,
                cases.size(), evaluable, cases.size() - evaluable, db.jsonArg(summary), scanId);
        return scanId;
    }

    /** Worst status wins: any look-ahead risk or hard miss makes the whole feature unusable. */
    private String combine(List<DepVerdict> verdicts) {
        String result = "AVAILABLE";
        for (DepVerdict verdict : verdicts) {
            switch (verdict.status()) {
                case "LOOKAHEAD_RISK" -> {
                    return "LOOKAHEAD_RISK";
                }
                case "MISSING" -> result = "MISSING";
                case "DEGRADED" -> {
                    if (!"MISSING".equals(result)) {
                        result = "DEGRADED";
                    }
                }
                default -> {
                }
            }
        }
        // MISSING outranks DEGRADED but LOOKAHEAD_RISK already returned above.
        return result;
    }

    private DepVerdict evaluate(DependencyGraphService.Dependency dep, CaseRow c) {
        return switch (dep.depKey()) {
            case "SOWING_DATE" -> evalSowingDate(c);
            case "TEMPERATURE_SERIES" -> evalTemperature(dep, c);
            case "IMAGERY_SEQUENCE" -> evalImagery(dep, c);
            default -> new DepVerdict(dep.depKey(), "MISSING", "unknown dependency kind",
                    db.mapper().createObjectNode());
        };
    }

    // ------------------------------------------------------------- SOWING_DATE

    /**
     * The look-ahead guard: a declaration that only exists with {@code ingested_at > cutoff}
     * is flagged LOOKAHEAD_RISK. It is never treated as available just because it exists today.
     */
    private DepVerdict evalSowingDate(CaseRow c) {
        List<Map<String, Object>> rows = db.jdbc().queryForList("""
                        select id, declaration_no, planting_date, ingested_at, revision_no
                          from planting_declaration
                         where parcel_id = ?
                         order by revision_no desc
                        """, c.parcelId());
        ObjectNode evidence = db.mapper().createObjectNode();
        evidence.put("declarations", rows.size());
        List<Map<String, Object>> visible = rows.stream()
                .filter(r -> !toInstant(r.get("ingested_at")).isAfter(c.scoringCutoff()))
                .toList();
        List<Map<String, Object>> late = rows.stream()
                .filter(r -> toInstant(r.get("ingested_at")).isAfter(c.scoringCutoff()))
                .toList();
        evidence.put("visibleAtCutoff", visible.size());
        evidence.put("ingestedAfterCutoff", late.size());
        if (!visible.isEmpty()) {
            Map<String, Object> chosen = visible.get(0);
            evidence.put("declarationNo", String.valueOf(chosen.get("declaration_no")));
            evidence.put("plantingDate", String.valueOf(chosen.get("planting_date")));
            evidence.put("ingestedAt", String.valueOf(chosen.get("ingested_at")));
            String reason = "declaration " + chosen.get("declaration_no")
                    + " was visible at the cutoff";
            if (!late.isEmpty()) {
                reason += "; " + late.size()
                        + " later revision(s) arrived after the cutoff and stay excluded";
                evidence.put("lateRevisions", late.size());
            }
            return new DepVerdict("SOWING_DATE", "AVAILABLE", reason, evidence);
        }
        if (!late.isEmpty()) {
            Map<String, Object> first = late.get(late.size() - 1);
            evidence.put("declarationNo", String.valueOf(first.get("declaration_no")));
            evidence.put("firstIngestedAt", String.valueOf(first.get("ingested_at")));
            evidence.put("cutoff", Times.format(c.scoringCutoff()));
            return new DepVerdict("SOWING_DATE", "LOOKAHEAD_RISK",
                    "planting declaration " + first.get("declaration_no") + " was only ingested at "
                            + first.get("ingested_at") + ", after the case cutoff "
                            + Times.format(c.scoringCutoff())
                            + "; flagged as look-ahead risk (穿越风险) and not adopted",
                    evidence);
        }
        return new DepVerdict("SOWING_DATE", "MISSING",
                "no planting declaration was ever recorded for this parcel", evidence);
    }

    // ------------------------------------------------------- TEMPERATURE_SERIES

    private DepVerdict evalTemperature(DependencyGraphService.Dependency dep, CaseRow c) {
        double minCoverage = dep.paramDouble("minCoverage", 0.8);
        int fallbackDays = dep.paramInt("fallbackWindowDays", 120);
        LocalDate planting = visiblePlantingDate(c);
        Instant windowStart = planting != null
                ? Times.startOfDay(planting)
                : Times.startOfDay(c.hazardDate().minusDays(fallbackDays));
        Instant windowEnd = Times.endOfDayExclusive(c.hazardDate());
        List<Map<String, Object>> rows = db.jdbc().queryForList("""
                        select o.external_key, o.period_start, o.period_end, o.temp_mean_c,
                               o.revision_no, o.ingested_at
                          from weather_observation o
                          join parcel_weather_link l on l.station_id = o.station_id
                         where l.parcel_id = ? and o.ingested_at <= ?
                           and o.period_end >= ? and o.period_start <= ?
                           and o.temp_mean_c is not null
                         order by o.external_key, o.revision_no desc
                        """,
                c.parcelId(), Db.timestamp(c.scoringCutoff()), Db.timestamp(windowStart),
                Db.timestamp(windowEnd));
        // Newest visible revision per external key, then merge intervals for true coverage.
        Map<String, Instant[]> intervalsByKey = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String key = String.valueOf(row.get("external_key"));
            intervalsByKey.putIfAbsent(key, new Instant[]{
                    toInstant(row.get("period_start")), toInstant(row.get("period_end"))});
        }
        double windowSeconds = Math.max(1, windowEnd.getEpochSecond() - windowStart.getEpochSecond());
        double covered = mergeAndSum(intervalsByKey.values().stream().toList(), windowStart, windowEnd);
        double coverage = Math.min(1d, covered / windowSeconds);
        ObjectNode evidence = db.mapper().createObjectNode();
        evidence.put("windowStart", Times.format(windowStart));
        evidence.put("windowEnd", Times.format(windowEnd));
        evidence.put("anchoredToSowingDate", planting != null);
        evidence.put("periodsWithTemp", intervalsByKey.size());
        evidence.put("coverage", Math.round(coverage * 1000d) / 1000d);
        evidence.put("minCoverage", minCoverage);
        if (intervalsByKey.isEmpty()) {
            return new DepVerdict("TEMPERATURE_SERIES", "MISSING",
                    "no temperature observations with temp_mean_c were visible at the cutoff "
                            + "(缺测气象); coverage 0 of the sowing→hazard window",
                    evidence);
        }
        if (coverage < minCoverage) {
            return new DepVerdict("TEMPERATURE_SERIES", "DEGRADED",
                    "temperature coverage " + Math.round(coverage * 1000d) / 10d + "% of the window "
                            + "is below the required " + minCoverage * 100d + "%",
                    evidence);
        }
        return new DepVerdict("TEMPERATURE_SERIES", "AVAILABLE",
                "temperature coverage " + Math.round(coverage * 1000d) / 10d + "% of the window",
                evidence);
    }

    // -------------------------------------------------------- IMAGERY_SEQUENCE

    private DepVerdict evalImagery(DependencyGraphService.Dependency dep, CaseRow c) {
        int windowDays = dep.paramInt("windowDays", 45);
        double maxCloudPct = dep.paramDouble("maxCloudPct", 30.0);
        int minScenes = dep.paramInt("minScenes", 2);
        Instant windowStart = Times.startOfDay(c.hazardDate().minusDays(windowDays));
        Instant windowEnd = Times.endOfDayExclusive(c.hazardDate());
        Geometry boundary = geo.parse(c.boundaryGeometry());
        List<Map<String, Object>> rows = db.jdbc().queryForList("""
                        select id, scene_key, acquisition_at, ingested_at, cloud_cover_pct,
                               footprint_geojson
                          from satellite_scene
                         where acquisition_at >= ? and acquisition_at <= ? and ingested_at <= ?
                         order by acquisition_at
                        """,
                Db.timestamp(windowStart), Db.timestamp(windowEnd), Db.timestamp(c.scoringCutoff()));
        int covering = 0;
        int usable = 0;
        int occluded = 0;
        ArrayNode sceneList = db.mapper().createArrayNode();
        for (Map<String, Object> row : rows) {
            Geometry footprint = geo.parse(db.readTree(String.valueOf(row.get("footprint_geojson"))));
            if (!geo.intersects(footprint, boundary)) {
                continue;
            }
            covering++;
            Object cloud = row.get("cloud_cover_pct");
            double cloudPct = cloud == null ? 0d : ((java.math.BigDecimal) cloud).doubleValue();
            boolean clear = cloudPct <= maxCloudPct;
            if (clear) {
                usable++;
            } else {
                occluded++;
            }
            ObjectNode scene = db.mapper().createObjectNode();
            scene.put("sceneKey", String.valueOf(row.get("scene_key")));
            scene.put("cloudCoverPct", cloudPct);
            scene.put("usable", clear);
            sceneList.add(scene);
        }
        ObjectNode evidence = db.mapper().createObjectNode();
        evidence.put("windowStart", Times.format(windowStart));
        evidence.put("windowEnd", Times.format(windowEnd));
        evidence.put("visibleScenes", covering);
        evidence.put("usableScenes", usable);
        evidence.put("occludedScenes", occluded);
        evidence.put("maxCloudPct", maxCloudPct);
        evidence.put("minScenes", minScenes);
        evidence.set("scenes", sceneList);
        if (covering == 0) {
            return new DepVerdict("IMAGERY_SEQUENCE", "MISSING",
                    "no imagery covering the parcel was visible at the cutoff inside the "
                            + windowDays + "-day window",
                    evidence);
        }
        if (usable < minScenes) {
            return new DepVerdict("IMAGERY_SEQUENCE", "DEGRADED",
                    "only " + usable + " cloud-free scene(s) visible at the cutoff (" + occluded
                            + " occluded by cloud); the cloud-occlusion policy decides whether the "
                            + "sequence is interpolated or reported missing",
                    evidence);
        }
        String reason = usable + " usable scene(s) in the window";
        if (occluded > 0) {
            reason += "; " + occluded + " cloud-occluded scene(s) handled by policy";
        }
        return new DepVerdict("IMAGERY_SEQUENCE", "AVAILABLE", reason, evidence);
    }

    // ------------------------------------------------------------------ queries

    public BackfillDtos.ScanDetail getScan(long scanId) {
        List<BackfillDtos.ScanSummary> scans = db.jdbc().query("""
                        select s.id, fd.code, fv.version_no, s.hazard_from, s.hazard_to, s.status,
                               s.case_count, s.evaluable_count, s.post_hoc_count, s.summary,
                               s.created_by, s.created_at
                          from availability_scan s
                          join feature_definition fd on fd.id = s.feature_definition_id
                          left join feature_definition_version fv on fv.id = s.definition_version_id
                         where s.id = ?
                        """,
                (rs, i) -> new BackfillDtos.ScanSummary(rs.getLong("id"), rs.getString("code"),
                        (Integer) rs.getObject("version_no"),
                        rs.getObject("hazard_from", LocalDate.class) == null
                                ? null : rs.getObject("hazard_from", LocalDate.class).toString(),
                        rs.getObject("hazard_to", LocalDate.class) == null
                                ? null : rs.getObject("hazard_to", LocalDate.class).toString(),
                        rs.getString("status"), rs.getInt("case_count"),
                        rs.getInt("evaluable_count"), rs.getInt("post_hoc_count"),
                        db.readTree(rs.getString("summary")),
                        rs.getString("created_by"), Times.format(Db.instant(rs, "created_at"))),
                scanId);
        if (scans.isEmpty()) {
            throw ApiException.notFound("SCAN_NOT_FOUND", "availability scan not found: " + scanId);
        }
        List<BackfillDtos.ScanItemView> items = db.jdbc().query("""
                        select i.case_id, c.case_no, c.hazard_date, c.scoring_cutoff, i.feature_status,
                               i.case_class, i.dependencies, i.reasons
                          from availability_scan_item i
                          join claim_case c on c.id = i.case_id
                         where i.scan_id = ?
                         order by c.case_no
                        """,
                (rs, i) -> new BackfillDtos.ScanItemView(rs.getLong("case_id"), rs.getString("case_no"),
                        rs.getObject("hazard_date", LocalDate.class).toString(),
                        Times.format(Db.instant(rs, "scoring_cutoff")), rs.getString("feature_status"),
                        rs.getString("case_class"), db.readTree(rs.getString("dependencies")),
                        db.mapper().convertValue(db.readTree(rs.getString("reasons")),
                                new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                                })),
                scanId);
        return new BackfillDtos.ScanDetail(scans.get(0), items);
    }

    public Long latestScanId(long featureDefinitionId) {
        return db.jdbc().query("""
                        select id from availability_scan
                         where feature_definition_id = ? and status = 'COMPLETED'
                         order by id desc limit 1
                        """, rs -> rs.next() ? rs.getLong(1) : null, featureDefinitionId);
    }

    private List<CaseRow> loadCases(LocalDate from, LocalDate to) {
        String sql = """
                select c.id, c.case_no, c.parcel_id, c.hazard_date, c.scoring_cutoff, c.crop_code,
                       c.boundary_version_id, b.geometry_geojson
                  from claim_case c
                  join parcel_boundary_version b on b.id = c.boundary_version_id
                 where c.hazard_date >= ?
                """;
        List<Object> args = new ArrayList<>();
        args.add(from);
        if (to != null) {
            sql += " and c.hazard_date <= ?";
            args.add(to);
        }
        sql += " order by c.case_no";
        return db.jdbc().query(sql, (rs, i) -> new CaseRow(rs.getLong("id"), rs.getString("case_no"),
                rs.getLong("parcel_id"), rs.getObject("hazard_date", LocalDate.class),
                Db.instant(rs, "scoring_cutoff"), rs.getString("crop_code"),
                rs.getLong("boundary_version_id"), db.readTree(rs.getString("geometry_geojson"))),
                args.toArray());
    }

    private LocalDate visiblePlantingDate(CaseRow c) {
        List<LocalDate> dates = db.jdbc().query("""
                        select planting_date from planting_declaration
                         where parcel_id = ? and ingested_at <= ?
                         order by revision_no desc limit 1
                        """,
                (rs, i) -> rs.getObject(1, LocalDate.class), c.parcelId(),
                Db.timestamp(c.scoringCutoff()));
        return dates.isEmpty() ? null : dates.get(0);
    }

    private static double mergeAndSum(List<Instant[]> intervals, Instant windowStart, Instant windowEnd) {
        List<long[]> clipped = new ArrayList<>();
        for (Instant[] interval : intervals) {
            long start = Math.max(interval[0].getEpochSecond(), windowStart.getEpochSecond());
            long end = Math.min(interval[1].getEpochSecond(), windowEnd.getEpochSecond());
            if (end > start) {
                clipped.add(new long[]{start, end});
            }
        }
        clipped.sort((a, b) -> Long.compare(a[0], b[0]));
        double covered = 0;
        long curStart = -1;
        long curEnd = -1;
        for (long[] interval : clipped) {
            if (curEnd < 0) {
                curStart = interval[0];
                curEnd = interval[1];
            } else if (interval[0] <= curEnd) {
                curEnd = Math.max(curEnd, interval[1]);
            } else {
                covered += curEnd - curStart;
                curStart = interval[0];
                curEnd = interval[1];
            }
        }
        if (curEnd > 0) {
            covered += curEnd - curStart;
        }
        return covered;
    }

    private static Instant toInstant(Object value) {
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toInstant();
        }
        if (value instanceof java.time.OffsetDateTime odt) {
            return odt.toInstant();
        }
        return Instant.parse(String.valueOf(value).replace(' ', 'T').replace("+00", "Z"));
    }
}
