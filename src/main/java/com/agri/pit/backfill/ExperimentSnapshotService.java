package com.agri.pit.backfill;

import com.agri.pit.cases.CaseService;
import com.agri.pit.common.ApiException;
import com.agri.pit.common.Db;
import com.agri.pit.common.Times;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Experiment snapshot repository. Every shard result is frozen as an immutable snapshot holding
 * the experiment inputs, outputs and missing reasons. Rows are keyed by
 * (experiment, case, definition version), so two definition versions backfilling the same case
 * at the same time can never contaminate each other's feature values, and a database trigger
 * rejects any update or delete — a strategy change always means a new experiment.
 */
@Service
public class ExperimentSnapshotService {

    private final Db db;

    public ExperimentSnapshotService(Db db) {
        this.db = db;
    }

    @Transactional
    public long persist(long experimentId, long shardId, CaseService.CaseRow caseRow,
                        String featureCode, long definitionVersionId, int definitionVersionNo,
                        String caseClass, ExperimentExecutor.ExperimentResult result) {
        ArrayNode missing = db.mapper().createArrayNode();
        result.missingReasons().forEach(missing::add);
        String inputsHash = hash(result);
        return db.insert("""
                        insert into experiment_snapshot
                            (experiment_id, shard_id, case_id, feature_code, definition_version_id,
                             case_class, cutoff_at, availability_ceiling, value, value_status,
                             missing_reasons, policy_applications, inputs, outputs, inputs_hash)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?)
                        """,
                experimentId, shardId, caseRow.id(), featureCode, definitionVersionId,
                caseClass,
                Db.timestamp(caseRow.scoringCutoff()), Db.timestamp(result.availabilityCeiling()),
                result.value() == null ? null : BigDecimal.valueOf(result.value()),
                result.valueStatus(), db.jsonArg(missing), db.jsonArg(result.policyApplications()),
                db.jsonArg(result.inputs()), db.jsonArg(result.outputs()), inputsHash);
    }

    private String hash(ExperimentExecutor.ExperimentResult result) {
        StringBuilder sb = new StringBuilder();
        result.inputs().forEach(node -> sb.append(node.path("sourceType").asText()).append(':')
                .append(node.path("sourceId").asLong()).append(':')
                .append(node.path("revisionNo").asInt()).append(':')
                .append(node.path("eligibility").asText()).append(';'));
        sb.append("value=").append(result.value());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ queries

    public List<BackfillDtos.ExperimentView> listExperiments() {
        return db.jdbc().query("""
                        select id, experiment_key, feature_code, policy_set, case_scope, scan_id,
                               status, notes, created_at
                          from experiment order by id
                        """,
                (rs, i) -> new BackfillDtos.ExperimentView(rs.getLong("id"),
                        rs.getString("experiment_key"), rs.getString("feature_code"),
                        db.readTree(rs.getString("policy_set")), rs.getString("case_scope"),
                        (Long) rs.getObject("scan_id"), rs.getString("status"),
                        rs.getString("notes"), Times.format(Db.instant(rs, "created_at"))));
    }

    public BackfillDtos.ExperimentView requireExperimentView(long experimentId) {
        List<BackfillDtos.ExperimentView> rows = db.jdbc().query("""
                        select id, experiment_key, feature_code, policy_set, case_scope, scan_id,
                               status, notes, created_at
                          from experiment where id = ?
                        """,
                (rs, i) -> new BackfillDtos.ExperimentView(rs.getLong("id"),
                        rs.getString("experiment_key"), rs.getString("feature_code"),
                        db.readTree(rs.getString("policy_set")), rs.getString("case_scope"),
                        (Long) rs.getObject("scan_id"), rs.getString("status"),
                        rs.getString("notes"), Times.format(Db.instant(rs, "created_at"))),
                experimentId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("EXPERIMENT_NOT_FOUND", "experiment not found: " + experimentId);
        }
        return rows.get(0);
    }

    public List<BackfillDtos.SnapshotView> snapshots(long experimentId, String caseClass) {
        String sql = """
                select s.*, c.case_no, fv.version_no
                  from experiment_snapshot s
                  join claim_case c on c.id = s.case_id
                  join feature_definition_version fv on fv.id = s.definition_version_id
                 where s.experiment_id = ?
                """;
        List<Object> args = new ArrayList<>();
        args.add(experimentId);
        if (caseClass != null && !caseClass.isBlank()) {
            sql += " and s.case_class = ?";
            args.add(caseClass.trim().toUpperCase());
        }
        sql += " order by c.case_no, fv.version_no";
        return db.jdbc().query(sql, (rs, i) -> mapSnapshot(rs), args.toArray());
    }

    private BackfillDtos.SnapshotView mapSnapshot(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new BackfillDtos.SnapshotView(rs.getLong("id"), rs.getLong("experiment_id"),
                rs.getLong("shard_id"), rs.getLong("case_id"), rs.getString("case_no"),
                rs.getString("feature_code"), rs.getLong("definition_version_id"),
                rs.getInt("version_no"), rs.getString("case_class"),
                Times.format(Db.instant(rs, "cutoff_at")),
                Times.format(Db.instant(rs, "availability_ceiling")),
                rs.getBigDecimal("value") == null ? null : rs.getBigDecimal("value").doubleValue(),
                rs.getString("value_status"),
                db.mapper().convertValue(db.readTree(rs.getString("missing_reasons")),
                        new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                        }),
                db.readTree(rs.getString("policy_applications")),
                db.readTree(rs.getString("inputs")),
                db.readTree(rs.getString("outputs")),
                rs.getString("inputs_hash"), Times.format(Db.instant(rs, "created_at")));
    }

    /**
     * Experiment metrics are aggregated over EVALUABLE, COMPUTED snapshots only: post-hoc
     * supplementary results (which may embed data ingested after the case cutoff, such as a
     * late planting declaration) never contaminate the reported numbers.
     */
    public Map<String, Object> metrics(long experimentId) {
        List<Map<String, Object>> rows = db.jdbc().queryForList("""
                        select s.definition_version_id, fv.version_no, s.case_class, s.value_status,
                               s.value
                          from experiment_snapshot s
                          join feature_definition_version fv on fv.id = s.definition_version_id
                         where s.experiment_id = ?
                        """, experimentId);
        Map<Long, List<Double>> byVersion = new TreeMap<>();
        Map<Long, Integer> versionNos = new TreeMap<>();
        int evaluableComputed = 0;
        int postHocComputed = 0;
        int missing = 0;
        List<Double> overall = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String valueStatus = (String) row.get("value_status");
            String caseClass = (String) row.get("case_class");
            if (!"COMPUTED".equals(valueStatus)) {
                missing++;
                continue;
            }
            double value = ((BigDecimal) row.get("value")).doubleValue();
            if ("POST_HOC_ONLY".equals(caseClass)) {
                postHocComputed++;
                continue;
            }
            evaluableComputed++;
            overall.add(value);
            long versionId = ((Number) row.get("definition_version_id")).longValue();
            byVersion.computeIfAbsent(versionId, k -> new ArrayList<>()).add(value);
            versionNos.put(versionId, ((Number) row.get("version_no")).intValue());
        }
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("evaluableComputed", evaluableComputed);
        metrics.put("postHocComputed", postHocComputed);
        metrics.put("missing", missing);
        metrics.put("overall", stats(overall));
        List<Map<String, Object>> versions = new ArrayList<>();
        byVersion.forEach((versionId, values) -> {
            Map<String, Object> entry = new LinkedHashMap<>(stats(values));
            entry.put("definitionVersionId", versionId);
            entry.put("definitionVersionNo", versionNos.get(versionId));
            versions.add(entry);
        });
        metrics.put("byDefinitionVersion", versions);
        metrics.put("note",
                "metrics cover EVALUABLE COMPUTED snapshots only; POST_HOC_ONLY snapshots are "
                        + "supplementary analysis and never enter reported experiment metrics");
        return metrics;
    }

    private Map<String, Object> stats(List<Double> values) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("count", values.size());
        if (values.isEmpty()) {
            stats.put("mean", null);
            stats.put("min", null);
            stats.put("max", null);
            return stats;
        }
        double sum = 0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (double value : values) {
            sum += value;
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        stats.put("mean", Math.round(sum / values.size() * 1_000_000d) / 1_000_000d);
        stats.put("min", min);
        stats.put("max", max);
        return stats;
    }

    /** Data-availability statement attached to every experiment: where the sample comes from. */
    public Map<String, Object> availabilityStatement(long experimentId) {
        BackfillDtos.ExperimentView experiment = requireExperimentView(experimentId);
        Map<String, Object> statement = new LinkedHashMap<>();
        statement.put("scanId", experiment.scanId());
        if (experiment.scanId() != null) {
            Map<String, Object> scan = db.jdbc().queryForMap("""
                            select case_count, evaluable_count, post_hoc_count, summary
                              from availability_scan where id = ?
                            """, experiment.scanId());
            statement.put("scannedCases", ((Number) scan.get("case_count")).intValue());
            statement.put("evaluableCases", ((Number) scan.get("evaluable_count")).intValue());
            statement.put("postHocOnlyCases", ((Number) scan.get("post_hoc_count")).intValue());
            statement.put("featureStatusCounts",
                    db.readTree(String.valueOf(scan.get("summary"))));
        }
        List<Map<String, Object>> missingReasons = db.jdbc().queryForList("""
                        select value_status, count(*) as n from experiment_snapshot
                         where experiment_id = ? and value_status <> 'COMPUTED'
                         group by value_status
                        """, experimentId);
        Map<String, Integer> missing = new LinkedHashMap<>();
        missingReasons.forEach(row -> missing.put((String) row.get("value_status"),
                ((Number) row.get("n")).intValue()));
        statement.put("missingByStatus", missing);
        statement.put("note",
                "EVALUABLE cases had all required inputs available at their own scoring cutoff; "
                        + "POST_HOC_ONLY cases carried look-ahead risk (e.g. a planting declaration "
                        + "ingested after the cutoff) or missing inputs, and are excluded from "
                        + "experiment metrics. A smaller comparable sample is the accepted price of "
                        + "keeping post-incident data out of the evaluation.");
        return statement;
    }

    // --------------------------------------------------------------- comparison

    /**
     * Compare two experiments over the same feature. The report separates differences that come
     * from the sample scope (cases computable in one experiment but missing in the other) from
     * differences that come from the formula or the missing-data policy (same case, different
     * value), so the model team can attribute a metric change to the right cause.
     */
    public Map<String, Object> compare(long leftId, long rightId) {
        BackfillDtos.ExperimentView left = requireExperimentView(leftId);
        BackfillDtos.ExperimentView right = requireExperimentView(rightId);
        if (!left.featureCode().equals(right.featureCode())) {
            throw ApiException.badRequest("EXPERIMENT_FEATURE_MISMATCH",
                    "experiments cover different features (" + left.featureCode() + " vs "
                            + right.featureCode() + "); comparison is only meaningful per feature");
        }
        List<BackfillDtos.SnapshotView> leftSnaps = snapshots(leftId, "EVALUABLE");
        List<BackfillDtos.SnapshotView> rightSnaps = snapshots(rightId, "EVALUABLE");
        Map<String, BackfillDtos.SnapshotView> leftComputed = computedByCaseAndVersion(leftSnaps);
        Map<String, BackfillDtos.SnapshotView> rightComputed = computedByCaseAndVersion(rightSnaps);

        List<Map<String, Object>> valueDiffs = new ArrayList<>();
        for (Map.Entry<String, BackfillDtos.SnapshotView> entry : leftComputed.entrySet()) {
            BackfillDtos.SnapshotView other = rightComputed.get(entry.getKey());
            if (other == null) {
                continue;
            }
            double delta = entry.getValue().value() - other.value();
            if (Math.abs(delta) > 1e-9) {
                Map<String, Object> diff = new LinkedHashMap<>();
                diff.put("caseNo", entry.getValue().caseNo());
                diff.put("definitionVersionNo", entry.getValue().definitionVersionNo());
                diff.put("leftValue", entry.getValue().value());
                diff.put("rightValue", other.value());
                diff.put("delta", Math.round(delta * 1_000_000d) / 1_000_000d);
                valueDiffs.add(diff);
            }
        }
        List<String> onlyLeft = onlyIn(leftComputed, rightComputed);
        List<String> onlyRight = onlyIn(rightComputed, leftComputed);

        String attribution;
        boolean scopeDiffers = !onlyLeft.isEmpty() || !onlyRight.isEmpty();
        boolean valuesDiffer = !valueDiffs.isEmpty();
        if (scopeDiffers && valuesDiffer) {
            attribution = "SAMPLE_SCOPE_AND_FORMULA_OR_POLICY";
        } else if (scopeDiffers) {
            attribution = "SAMPLE_SCOPE";
        } else if (valuesDiffer) {
            attribution = "FORMULA_OR_POLICY";
        } else {
            attribution = "IDENTICAL";
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("left", experimentSummary(left));
        report.put("right", experimentSummary(right));
        Map<String, Object> sampleScope = new LinkedHashMap<>();
        sampleScope.put("bothComputed", leftComputed.keySet().stream()
                .filter(rightComputed::containsKey).count());
        sampleScope.put("onlyLeftComputed", onlyLeft);
        sampleScope.put("onlyRightComputed", onlyRight);
        sampleScope.put("leftMissing", missingHistogram(leftSnaps));
        sampleScope.put("rightMissing", missingHistogram(rightSnaps));
        report.put("sampleScope", sampleScope);
        report.put("valueDiffs", valueDiffs);
        report.put("attribution", attribution);
        report.put("explanation", switch (attribution) {
            case "SAMPLE_SCOPE" ->
                    "指标差异来自样本范围：同一案件在一个实验中可计算、在另一个实验中缺失（例如云遮挡策略"
                            + "由插值改为缺失排除后样本减少）。公式本身未造成数值差异。";
            case "FORMULA_OR_POLICY" ->
                    "指标差异来自特征公式或缺失处理策略：相同样本范围内同一案件的取值不同。";
            case "SAMPLE_SCOPE_AND_FORMULA_OR_POLICY" ->
                    "指标差异同时来自样本范围与公式/策略：既有案件只在单侧可计算，也有共同案件取值不同。";
            default -> "两个实验在相同样本上得到相同取值；指标差异为 0。";
        });
        return report;
    }

    private Map<String, BackfillDtos.SnapshotView> computedByCaseAndVersion(
            List<BackfillDtos.SnapshotView> snaps) {
        Map<String, BackfillDtos.SnapshotView> map = new TreeMap<>();
        for (BackfillDtos.SnapshotView snap : snaps) {
            if ("COMPUTED".equals(snap.valueStatus())) {
                map.put(snap.caseNo() + "@" + snap.definitionVersionNo(), snap);
            }
        }
        return map;
    }

    private List<String> onlyIn(Map<String, BackfillDtos.SnapshotView> mine,
                                Map<String, BackfillDtos.SnapshotView> theirs) {
        List<String> only = new ArrayList<>();
        mine.forEach((key, snap) -> {
            if (!theirs.containsKey(key)) {
                only.add(snap.caseNo() + " (v" + snap.definitionVersionNo() + ")");
            }
        });
        return only;
    }

    private Map<String, Integer> missingHistogram(List<BackfillDtos.SnapshotView> snaps) {
        Map<String, Integer> histogram = new LinkedHashMap<>();
        for (BackfillDtos.SnapshotView snap : snaps) {
            if (!"COMPUTED".equals(snap.valueStatus())) {
                histogram.merge(snap.valueStatus(), 1, Integer::sum);
            }
        }
        return histogram;
    }

    private Map<String, Object> experimentSummary(BackfillDtos.ExperimentView experiment) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", experiment.id());
        summary.put("experimentKey", experiment.experimentKey());
        summary.put("featureCode", experiment.featureCode());
        summary.put("caseScope", experiment.caseScope());
        summary.put("policySet", experiment.policySet());
        summary.put("metrics", metrics(experiment.id()));
        return summary;
    }
}
