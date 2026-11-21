package com.agri.pit.demo;

import com.agri.pit.backfill.DependencyGraphService;
import com.agri.pit.backfill.MissingPolicyService;
import com.agri.pit.catalog.CatalogDtos;
import com.agri.pit.catalog.CatalogService;
import com.agri.pit.common.Db;
import com.agri.pit.ingest.IngestDtos;
import com.agri.pit.ingest.WarehouseService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Deterministic local sample encoding the three acceptance scenarios:
 * <ol>
 *   <li><b>Late scene</b> (SCENE-2024-06-18-B): acquired before the hazard date but ingested after
 *       the case cutoff. The original case must exclude it; a controlled backfill may use it only
 *       to produce a review candidate.</li>
 *   <li><b>Overlapping boundaries</b> (parcel P-1001 v1 and v2): both cover the same ground.
 *       The case locks v1 and owns the shared pixels; a boundary-variant run on v2 must see those
 *       pixels excluded, so no raster cell is counted into two fields.</li>
 *   <li><b>Post-settlement weather revision</b> (WS-001, week of 2024-06-03): the correction is
 *       ingested weeks after the case was paid. Impact analysis creates a review candidate and
 *       leaves the frozen snapshot and payout untouched.</li>
 * </ol>
 * Seeding is idempotent: if the demo cases exist, it is skipped.
 */
@Component
public class DemoSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

    public static final LocalDate HAZARD_DATE = LocalDate.of(2024, 6, 20);
    public static final Instant CASE_CUTOFF = Instant.parse("2024-06-22T00:00:00Z");
    public static final String SCENE_LATE = "SCENE-2024-06-18-B";
    public static final String WEATHER_REVISION_KEY = "WS-001-W2024-06-13";
    public static final String CASE_PRIMARY = "DROUGHT-2024-0001";
    public static final String CASE_SECONDARY = "DROUGHT-2024-0002";

    private final Db db;
    private final CatalogService catalog;
    private final WarehouseService warehouse;
    private final DependencyGraphService dependencies;
    private final MissingPolicyService policies;
    private final boolean enabled;

    public DemoSeeder(Db db, CatalogService catalog, WarehouseService warehouse,
                      DependencyGraphService dependencies, MissingPolicyService policies,
                      @Value("${app.demo.seed-on-startup:true}") boolean enabled) {
        this.db = db;
        this.catalog = catalog;
        this.warehouse = warehouse;
        this.dependencies = dependencies;
        this.policies = policies;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        if (!enabled) {
            log.info("demo seeding disabled");
            return;
        }
        Integer existing = db.jdbc().queryForObject("""
                        select count(*) from claim_case where case_no in (?, ?)
                        """, Integer.class, CASE_PRIMARY, CASE_SECONDARY);
        if (existing != null && existing > 0) {
            log.info("demo data already present, skipping base seed");
        } else {
            log.info("seeding drought demo scenario");
            seedCatalog();
            long[] parcels = seedParcelsAndStations();
            long parcel1 = parcels[0];
            long parcel2 = parcels[1];
            seedScenes();
            seedWeather();
            seedDeclarations(parcel1, parcel2);
            seedCases(parcel1, parcel2);
            log.info("demo seed complete");
        }
        seedBackfillLab();
    }

    // ------------------------------------------------------------------ catalog

    private void seedCatalog() {
        catalog.createFeature(new CatalogDtos.CreateFeatureRequest("NDVI_DROUGHT", "NDVI drought index",
                "Vegetation vigour deficit over the 30 days before the hazard date", "index",
                List.of("SATELLITE_NDVI", "PARCEL_BOUNDARY")));
        catalog.createVersion("NDVI_DROUGHT", new CatalogDtos.CreateVersionRequest(
                "clamp(1 - ndvi_mean + 0.25 * ndvi_range, 0, 1)", true, 0L, "AS_OF_CUTOFF", 30,
                "v1: vegetation deficit with dispersion penalty"));
        catalog.approve("NDVI_DROUGHT", 1, "catalog-admin", "initial drought index");

        catalog.createFeature(new CatalogDtos.CreateFeatureRequest("RAIN_ANOMALY", "Rainfall deficit",
                "Rainfall shortfall over the 60-day pre-hazard window", "mm",
                List.of("WEATHER_OBSERVATION")));
        catalog.createVersion("RAIN_ANOMALY", new CatalogDtos.CreateVersionRequest(
                "max(0, 70 - rainfall_weighted * rainfall_periods)", true, 0L, "AS_OF_CUTOFF", 60,
                "v1: rainfall shortfall vs a 70mm reference over the window"));
        catalog.approve("RAIN_ANOMALY", 1, "catalog-admin", "initial rainfall deficit");

        catalog.createFeature(new CatalogDtos.CreateFeatureRequest("CROP_EXPOSURE", "Crop exposure",
                "Declared area scaled by growth stage", "ha",
                List.of("PLANTING_DECLARATION")));
        catalog.createVersion("CROP_EXPOSURE", new CatalogDtos.CreateVersionRequest(
                "declared_area_ha * clamp(days_since_planting / 120, 0.2, 1.5)", true, 0L,
                "AS_OF_CUTOFF", 0, "v1: area scaled by growth stage"));
        catalog.approve("CROP_EXPOSURE", 1, "catalog-admin", "initial crop exposure");
    }

    // ------------------------------------------------------------- geo + assets

    private long[] seedParcelsAndStations() {
        long parcel1 = warehouse.createParcel(new IngestDtos.ParcelRequest("P-1001",
                "North ridge wheat field", "Hebei-North", "WHEAT"));
        long parcel2 = warehouse.createParcel(new IngestDtos.ParcelRequest("P-1002",
                "South valley wheat field", "Hebei-South", "WHEAT"));

        // Both boundary versions were available before the case cutoff, so the overlap is a data
        // reality, not a leakage problem. v1 (locked by the case) and v2 (re-survey extending east)
        // share the western cells.
        warehouse.addBoundary(parcel1, new IngestDtos.BoundaryRequest(1,
                polygon(115.900, 39.900, 115.910, 39.910),
                LocalDate.of(2024, 1, 1), null,
                Instant.parse("2024-04-01T08:00:00Z"), Instant.parse("2024-04-02T08:00:00Z"),
                null, "initial survey"));
        warehouse.addBoundary(parcel1, new IngestDtos.BoundaryRequest(2,
                polygon(115.900, 39.900, 115.920, 39.910),
                LocalDate.of(2024, 6, 1), null,
                Instant.parse("2024-06-19T08:00:00Z"), Instant.parse("2024-06-21T08:00:00Z"),
                null, "re-survey before the case cutoff; overlaps v1 on the west side"));
        warehouse.addBoundary(parcel2, new IngestDtos.BoundaryRequest(1,
                polygon(115.950, 39.850, 115.960, 39.860),
                LocalDate.of(2024, 1, 1), null,
                Instant.parse("2024-04-01T08:00:00Z"), Instant.parse("2024-04-02T08:00:00Z"),
                null, "initial survey"));

        long station1 = warehouse.createStation(new IngestDtos.StationRequest("WS-001",
                "North ridge station", 115.905, 39.905));
        long station2 = warehouse.createStation(new IngestDtos.StationRequest("WS-002",
                "South valley station", 115.955, 39.855));
        warehouse.linkStation(parcel1, station1, 0.7);
        warehouse.linkStation(parcel1, station2, 0.3);
        warehouse.linkStation(parcel2, station2, 1.0);
        return new long[]{parcel1, parcel2};
    }

    // ------------------------------------------------------------------- scenes

    private void seedScenes() {
        // Scene A: acquired 2024-06-10, ingested 2024-06-12, entirely visible at the cutoff.
        // Cells A-01..A-05 sit inside both parcel-1 boundary versions; A-08..A-09 sit only in v2.
        warehouse.ingestScene(new IngestDtos.SceneRequest("SCENE-2024-06-10-A", 1,
                Instant.parse("2024-06-10T10:30:00Z"), Instant.parse("2024-06-12T04:00:00Z"),
                8.0, footprint(115.895, 39.895, 115.965, 39.915), "sentinel-2",
                null, List.of(
                        cell("A-01", 115.9050, 39.9050, 0.42),
                        cell("A-02", 115.9060, 39.9050, 0.38),
                        cell("A-03", 115.9070, 39.9050, 0.45),
                        cell("A-04", 115.9055, 39.9060, 0.40),
                        cell("A-05", 115.9065, 39.9060, 0.36),
                        cell("A-08", 115.9130, 39.9050, 0.30),
                        cell("A-09", 115.9140, 39.9060, 0.28),
                        cell("A-06", 115.9550, 39.8550, 0.62),
                        cell("A-07", 115.9560, 39.8560, 0.58))));

        // Scene B: the leakage trap. Acquired 2024-06-18 (in the pre-hazard window), ingested
        // 2024-06-25 (after the 2024-06-22 cutoff). Clearer and much drier, so using it would
        // silently raise the payout of an already-settled case.
        warehouse.ingestScene(new IngestDtos.SceneRequest(SCENE_LATE, 1,
                Instant.parse("2024-06-18T09:15:00Z"), Instant.parse("2024-06-25T03:00:00Z"),
                3.0, footprint(115.895, 39.895, 115.965, 39.915), "sentinel-2",
                null, List.of(
                        cell("B-01", 115.9050, 39.9050, 0.21),
                        cell("B-02", 115.9060, 39.9050, 0.19),
                        cell("B-03", 115.9070, 39.9050, 0.24),
                        cell("B-04", 115.9055, 39.9060, 0.22),
                        cell("B-05", 115.9065, 39.9060, 0.18),
                        cell("B-06", 115.9550, 39.8550, 0.54),
                        cell("B-07", 115.9560, 39.8560, 0.51))));
    }

    // ------------------------------------------------------------------ weather

    private void seedWeather() {
        LocalDate start = HAZARD_DATE.minusDays(56);
        String[] stations = {"WS-001", "WS-002"};
        double[][] rainfall = {
                {8.0, 6.0, 4.0, 2.0, 3.0, 1.0, 0.5, 1.0},
                {12.0, 9.0, 7.0, 5.0, 4.0, 2.0, 1.0, 1.5}};
        for (int s = 0; s < stations.length; s++) {
            for (int week = 0; week < 8; week++) {
                LocalDate periodStart = start.plusWeeks(week);
                LocalDate periodEnd = periodStart.plusDays(6);
                Instant observed = endOf(periodEnd);
                Instant ingested = observed.plusSeconds(6 * 3600);
                warehouse.ingestWeather(new IngestDtos.WeatherRequest(stations[s],
                        stations[s] + "-W" + periodStart, startOf(periodStart), endOf(periodEnd),
                        rainfall[s][week], 28.0 - week * 0.3, observed, ingested, null, null,
                        "ground-station"));
            }
        }
        // The correction that arrives after settlement (ingested 2024-07-08).
        long station1 = db.jdbc().queryForObject(
                "select id from weather_station where station_code = 'WS-001'", Long.class);
        long original = db.jdbc().queryForObject("""
                        select id from weather_observation
                         where station_id = ? and external_key = ?
                        """, Long.class, station1, WEATHER_REVISION_KEY);
        warehouse.ingestWeather(new IngestDtos.WeatherRequest("WS-001", WEATHER_REVISION_KEY,
                startOf(HAZARD_DATE.minusDays(7)), endOf(HAZARD_DATE.minusDays(1)),
                14.5, 27.1, Instant.parse("2024-06-19T23:59:59Z"),
                Instant.parse("2024-07-08T02:00:00Z"), 2, original, "post-audit-correction"));
    }

    // ------------------------------------------------------------- declarations

    private void seedDeclarations(long parcel1, long parcel2) {
        warehouse.ingestDeclaration(new IngestDtos.DeclarationRequest("DECL-2024-1001", parcel1,
                "WHEAT", LocalDate.of(2024, 3, 15), 5.6, Instant.parse("2024-03-20T08:00:00Z"),
                Instant.parse("2024-03-22T08:00:00Z"), 1, null, "county-submission"));
        warehouse.ingestDeclaration(new IngestDtos.DeclarationRequest("DECL-2024-1002", parcel2,
                "WHEAT", LocalDate.of(2024, 3, 18), 4.9, Instant.parse("2024-03-20T08:00:00Z"),
                Instant.parse("2024-03-22T08:00:00Z"), 1, null, "county-submission"));
    }

    // -------------------------------------------------------------------- cases

    private void seedCases(long parcel1, long parcel2) {
        long parcel1V1 = boundaryId(parcel1, 1);
        long parcel2V1 = boundaryId(parcel2, 1);
        insertCase(CASE_PRIMARY, "POL-2024-0001", parcel1, parcel1V1);
        insertCase(CASE_SECONDARY, "POL-2024-0002", parcel2, parcel2V1);
        // Reflect that these claims were already settled before the late revision arrived. The
        // queue worker still needs the run snapshot, so settlement is applied by the demo
        // bootstrap once scoring completes (see DemoController bootstrap note / README).
    }

    private long boundaryId(long parcelId, int versionNo) {
        return db.jdbc().queryForObject("""
                        select id from parcel_boundary_version where parcel_id = ? and version_no = ?
                        """, Long.class, parcelId, versionNo);
    }

    private void insertCase(String caseNo, String policyNo, long parcelId, long boundaryVersionId) {
        long caseId = db.insert("""
                        insert into claim_case
                            (case_no, policy_no, parcel_id, crop_code, hazard_type, hazard_date,
                             scoring_cutoff, boundary_version_id, status)
                        values (?, ?, ?, 'WHEAT', 'DROUGHT', ?, ?, ?, 'QUEUED')
                        """,
                caseNo, policyNo, parcelId, HAZARD_DATE, Db.timestamp(CASE_CUTOFF), boundaryVersionId);
        List<Long> versionIds = db.jdbc().query(
                "select current_version_id from feature_definition where status = 'APPROVED'",
                (rs, i) -> rs.getLong(1));
        for (Long versionId : versionIds) {
            db.jdbc().update("""
                            insert into claim_case_feature_lock (case_id, feature_definition_version_id)
                            values (?, ?)
                            """, caseId, versionId);
        }
        db.insert("""
                        insert into feature_run
                            (case_id, mode, trigger_type, status, verdict, data_policy, cutoff_at,
                             availability_ceiling, hazard_date, boundary_version_id, boundary_variant,
                             reason, requested_by)
                        values (?, 'ORIGINAL', 'CASE_SCORING', 'QUEUED', 'NOT_EVALUATED', 'AS_OF_CUTOFF',
                                ?, ?, ?, ?, false, 'demo seed intake', 'demo-seeder')
                        """,
                caseId, Db.timestamp(CASE_CUTOFF), Db.timestamp(CASE_CUTOFF), HAZARD_DATE,
                boundaryVersionId);
    }

    // ========================================================================
    // Backfill lab demo: the model team's two new features and three years of
    // historical cases with different point-in-time availability profiles.
    // ========================================================================

    public static final String FEATURE_GDD = "GDD_AFTER_SOWING";
    public static final String FEATURE_SLOPE = "NDVI_RECOVERY_SLOPE";
    public static final String CASE_CLEAN = "HIST-2024-0003";
    public static final String CASE_LATE_DECL = "HIST-2025-0001";
    public static final String CASE_CLOUD_LEAD = "HIST-2025-0002";
    public static final String CASE_CLOUD_TRAIL = "HIST-2025-0003";
    public static final String CASE_NO_TEMP = "HIST-2026-0001";

    /**
     * Seeds the backfill lab: feature definitions for effective growing degree days after
     * sowing and the pre-hazard NDVI recovery slope, their dependency graph entries, the
     * missing-data policy library, and five historical cases (2024-2026) that exercise every
     * scanner classification. Idempotent: skipped when the GDD feature already exists.
     */
    private void seedBackfillLab() {
        Integer existing = db.jdbc().queryForObject(
                "select count(*) from feature_definition where code = ?", Integer.class, FEATURE_GDD);
        if (existing != null && existing > 0) {
            log.info("backfill lab demo already present, skipping");
            return;
        }
        log.info("seeding backfill lab demo (new features, policies, historical cases)");
        seedBackfillCatalog();
        seedBackfillDependencies();
        seedBackfillPolicies();
        seedHistoricalCases();
    }

    private void seedBackfillCatalog() {
        // required=false: these features are evaluated by the backfill lab for historical
        // experiments; the main scoring pipeline leaves them NULL until its windows are extended.
        catalog.createFeature(new CatalogDtos.CreateFeatureRequest(FEATURE_GDD,
                "Effective GDD after sowing",
                "Effective growing degree days (base 10C) accumulated from the planting date to "
                        + "the hazard date; needs a sowing declaration and a temperature series",
                "C-day", List.of("WEATHER_OBSERVATION", "PLANTING_DECLARATION")));
        catalog.createVersion(FEATURE_GDD, new CatalogDtos.CreateVersionRequest(
                "gdd_effective", false, 0L, "AS_OF_CUTOFF", 120,
                "v1: raw effective GDD over the sowing-to-hazard window"));
        catalog.approve(FEATURE_GDD, 1, "model-team", "initial GDD feature for historical evaluation");
        catalog.createVersion(FEATURE_GDD, new CatalogDtos.CreateVersionRequest(
                "gdd_effective * 0.85", false, 0L, "AS_OF_CUTOFF", 120,
                "v2: calibrated GDD (x0.85) - concurrent version for the backfill scheduler"));
        catalog.approve(FEATURE_GDD, 2, "model-team", "calibration update");

        catalog.createFeature(new CatalogDtos.CreateFeatureRequest(FEATURE_SLOPE,
                "Pre-hazard NDVI recovery slope",
                "Least-squares slope of the per-scene mean NDVI inside the 45-day pre-hazard "
                        + "imagery window; needs at least two usable scenes",
                "ndvi/day", List.of("SATELLITE_NDVI")));
        catalog.createVersion(FEATURE_SLOPE, new CatalogDtos.CreateVersionRequest(
                "ndvi_recovery_slope", false, 0L, "AS_OF_CUTOFF", 45,
                "v1: vegetation recovery slope before the disaster"));
        catalog.approve(FEATURE_SLOPE, 1, "model-team", "initial recovery slope");
    }

    private void seedBackfillDependencies() {
        dependencies.ensureRegistered(FEATURE_GDD, "SOWING_DATE", null,
                "planting date anchors the GDD window; a declaration ingested after the case "
                        + "cutoff is look-ahead risk");
        ObjectNode tempParams = db.mapper().createObjectNode();
        tempParams.put("baseTempC", 10.0);
        tempParams.put("minCoverage", 0.8);
        tempParams.put("fallbackWindowDays", 120);
        dependencies.ensureRegistered(FEATURE_GDD, "TEMPERATURE_SERIES", tempParams,
                "daily/weekly mean temperature over the sowing-to-hazard window");
        ObjectNode imageryParams = db.mapper().createObjectNode();
        imageryParams.put("windowDays", 45);
        imageryParams.put("maxCloudPct", 30.0);
        imageryParams.put("minScenes", 2);
        dependencies.ensureRegistered(FEATURE_SLOPE, "IMAGERY_SEQUENCE", imageryParams,
                "scene sequence in the 45-day pre-hazard window; cloud-occluded scenes are "
                        + "handled by the CLOUD_OCCLUSION policy");
    }

    private void seedBackfillPolicies() {
        ObjectNode cloudParams = db.mapper().createObjectNode();
        cloudParams.put("maxCloudPct", 30.0);
        policies.create(new com.agri.pit.backfill.BackfillDtos.CreatePolicyRequest(
                "CLOUD_OCCLUSION", "INTERPOLATE", cloudParams,
                "v1: interpolate cloud-occluded scenes from the nearest clear neighbours"));
        policies.create(new com.agri.pit.backfill.BackfillDtos.CreatePolicyRequest(
                "CLOUD_OCCLUSION", "EXCLUDE_MISSING", cloudParams,
                "v2: exclude cloud-occluded scenes; report the feature missing when the sequence "
                        + "thins out (supersedes interpolation for reporting)"));
        policies.create(new com.agri.pit.backfill.BackfillDtos.CreatePolicyRequest(
                "LATE_DECLARATION", "FLAG_LOOKAHEAD_RISK", null,
                "v1: a declaration ingested after the case cutoff is look-ahead risk and is "
                        + "never adopted for historical evaluation"));
        policies.create(new com.agri.pit.backfill.BackfillDtos.CreatePolicyRequest(
                "MISSING_WEATHER", "EXCLUDE_MISSING", null,
                "v1: compute over the visible periods; missing periods are excluded and the "
                        + "coverage ratio is reported"));
    }

    // ------------------------------------------------------- historical cases

    private void seedHistoricalCases() {
        // Case 1: clean history, every input visible at the cutoff -> EVALUABLE for both features.
        long p1 = histParcel("P-2001", "East ridge wheat", "Hebei-East",
                116.100, 40.100, 116.110, 40.110, "2024-01-06T08:00:00Z");
        histWeather("WS-2001", p1, LocalDate.of(2024, 4, 25), 15, 16.0, 0.8, false);
        histDeclaration("DECL-2024-2001", p1, LocalDate.of(2024, 5, 2), 6.2,
                "2024-05-08T08:00:00Z");
        histScene("SCENE-H1-A", p1, "2024-07-01T10:00:00Z", 8.0, 0.30);
        histScene("SCENE-H1-B", p1, "2024-07-11T10:00:00Z", 12.0, 0.34);
        histScene("SCENE-H1-C", p1, "2024-07-21T10:00:00Z", 6.0, 0.38);
        histScene("SCENE-H1-D", p1, "2024-07-31T10:00:00Z", 14.0, 0.42);
        histScene("SCENE-H1-E", p1, "2024-08-08T10:00:00Z", 9.0, 0.45);
        histCase(CASE_CLEAN, "POL-2024-2001", p1, LocalDate.of(2024, 8, 10),
                Instant.parse("2024-08-12T00:00:00Z"));

        // Case 2: the only planting declaration was ingested AFTER the case cutoff -> the GDD
        // feature is look-ahead risk; the slope feature is fine.
        long p2 = histParcel("P-2002", "West basin wheat", "Hebei-West",
                116.200, 40.200, 116.210, 40.210, "2024-11-06T08:00:00Z");
        histWeather("WS-2002", p2, LocalDate.of(2025, 2, 27), 16, 12.0, 0.9, false);
        histDeclaration("DECL-2025-2002", p2, LocalDate.of(2025, 3, 7), 5.4,
                "2025-06-20T02:00:00Z");
        histScene("SCENE-H2-A", p2, "2025-05-05T10:00:00Z", 7.0, 0.28);
        histScene("SCENE-H2-B", p2, "2025-05-15T10:00:00Z", 10.0, 0.33);
        histScene("SCENE-H2-C", p2, "2025-05-25T10:00:00Z", 5.0, 0.37);
        histScene("SCENE-H2-D", p2, "2025-06-04T10:00:00Z", 12.0, 0.41);
        histCase(CASE_LATE_DECL, "POL-2025-2002", p2, LocalDate.of(2025, 6, 15),
                Instant.parse("2025-06-17T00:00:00Z"));

        // Case 3: the first half of the imagery sequence is cloud-occluded -> interpolation and
        // exclusion produce different slopes for the same case.
        long p3 = histParcel("P-2003", "Central loess wheat", "Hebei-Central",
                116.300, 40.300, 116.310, 40.310, "2025-01-06T08:00:00Z");
        histWeather("WS-2003", p3, LocalDate.of(2025, 5, 26), 16, 18.0, 0.6, false);
        histDeclaration("DECL-2025-2003", p3, LocalDate.of(2025, 6, 2), 7.1,
                "2025-06-09T08:00:00Z");
        histScene("SCENE-H3-A", p3, "2025-08-01T10:00:00Z", 65.0, 0.30);
        histScene("SCENE-H3-B", p3, "2025-08-12T10:00:00Z", 70.0, 0.34);
        histScene("SCENE-H3-C", p3, "2025-08-22T10:00:00Z", 10.0, 0.40);
        histScene("SCENE-H3-D", p3, "2025-09-01T10:00:00Z", 8.0, 0.46);
        histCase(CASE_CLOUD_LEAD, "POL-2025-2003", p3, LocalDate.of(2025, 9, 10),
                Instant.parse("2025-09-12T00:00:00Z"));

        // Case 4: only one cloud-free scene; interpolation still yields a (flat-tail) slope,
        // while exclusion leaves the sequence too thin -> missing under EXCLUDE_MISSING.
        long p4 = histParcel("P-2004", "South hill wheat", "Hebei-South",
                116.400, 40.400, 116.410, 40.410, "2025-02-06T08:00:00Z");
        histWeather("WS-2004", p4, LocalDate.of(2025, 6, 24), 17, 20.0, 0.5, false);
        histDeclaration("DECL-2025-2004", p4, LocalDate.of(2025, 7, 1), 4.8,
                "2025-07-06T08:00:00Z");
        histScene("SCENE-H4-A", p4, "2025-09-10T10:00:00Z", 12.0, 0.33);
        histScene("SCENE-H4-B", p4, "2025-09-25T10:00:00Z", 66.0, 0.38);
        histScene("SCENE-H4-C", p4, "2025-10-10T10:00:00Z", 72.0, 0.42);
        histCase(CASE_CLOUD_TRAIL, "POL-2025-2004", p4, LocalDate.of(2025, 10, 20),
                Instant.parse("2025-10-22T00:00:00Z"));

        // Case 5: temperature was never measured (temp_mean_c is null) -> GDD is post-hoc only;
        // the imagery sequence is complete.
        long p5 = histParcel("P-2005", "North plain wheat", "Hebei-North",
                116.500, 40.500, 116.510, 40.510, "2025-11-06T08:00:00Z");
        histWeather("WS-2005", p5, LocalDate.of(2026, 2, 3), 15, 0.0, 0.0, true);
        histDeclaration("DECL-2026-2005", p5, LocalDate.of(2026, 2, 10), 5.9,
                "2026-02-15T08:00:00Z");
        histScene("SCENE-H5-A", p5, "2026-04-10T10:00:00Z", 9.0, 0.25);
        histScene("SCENE-H5-B", p5, "2026-04-20T10:00:00Z", 11.0, 0.30);
        histScene("SCENE-H5-C", p5, "2026-04-30T10:00:00Z", 7.0, 0.35);
        histScene("SCENE-H5-D", p5, "2026-05-10T10:00:00Z", 13.0, 0.40);
        histCase(CASE_NO_TEMP, "POL-2026-2005", p5, LocalDate.of(2026, 5, 20),
                Instant.parse("2026-05-22T00:00:00Z"));
    }

    /** Parcel + one boundary version + one weather station. Returns the parcel id. */
    private long histParcel(String code, String name, String region,
                            double minLon, double minLat, double maxLon, double maxLat,
                            String boundaryIngested) {
        long parcelId = warehouse.createParcel(new IngestDtos.ParcelRequest(code, name, region, "WHEAT"));
        warehouse.addBoundary(parcelId, new IngestDtos.BoundaryRequest(1,
                polygon(minLon, minLat, maxLon, maxLat),
                LocalDate.of(2024, 1, 1), null,
                Instant.parse(boundaryIngested).minusSeconds(24 * 3600), Instant.parse(boundaryIngested),
                null, "legacy survey"));
        String stationCode = "WS-" + code.substring(2);
        long stationId = warehouse.createStation(new IngestDtos.StationRequest(stationCode,
                name + " station", (minLon + maxLon) / 2, (minLat + maxLat) / 2));
        warehouse.linkStation(parcelId, stationId, 1.0);
        HIST_GEO.put(code, new double[]{minLon, minLat, maxLon, maxLat});
        return parcelId;
    }

    private static final java.util.Map<String, double[]> HIST_GEO = new java.util.HashMap<>();

    private void histWeather(String stationCode, long parcelId, LocalDate start, int weeks,
                             double tempStart, double tempStep, boolean nullTemp) {
        for (int week = 0; week < weeks; week++) {
            LocalDate periodStart = start.plusWeeks(week);
            LocalDate periodEnd = periodStart.plusDays(6);
            Instant observed = endOf(periodEnd);
            Instant ingested = observed.plusSeconds(6 * 3600);
            Double temp = nullTemp ? null : tempStart + week * tempStep;
            warehouse.ingestWeather(new IngestDtos.WeatherRequest(stationCode,
                    stationCode + "-W" + periodStart, startOf(periodStart), endOf(periodEnd),
                    4.0 + (week % 5), temp, observed, ingested, null, null, "ground-station"));
        }
    }

    private void histDeclaration(String declarationNo, long parcelId, LocalDate plantingDate,
                                 double areaHa, String ingestedAt) {
        warehouse.ingestDeclaration(new IngestDtos.DeclarationRequest(declarationNo, parcelId,
                "WHEAT", plantingDate, areaHa,
                Instant.parse(ingestedAt).minusSeconds(48 * 3600), Instant.parse(ingestedAt),
                null, null, "county-submission"));
    }

    /** A scene whose cells sit inside the parcel box; ingested two days after acquisition. */
    private void histScene(String sceneKey, long parcelId, String acquisitionAt, double cloudPct,
                           double ndvi) {
        String parcelCode = db.jdbc().queryForObject(
                "select parcel_code from parcel where id = ?", String.class, parcelId);
        double[] box = HIST_GEO.get(parcelCode);
        double cx = (box[0] + box[2]) / 2;
        double cy = (box[1] + box[3]) / 2;
        Instant acquired = Instant.parse(acquisitionAt);
        warehouse.ingestScene(new IngestDtos.SceneRequest(sceneKey, 1, acquired,
                acquired.plusSeconds(48 * 3600), cloudPct,
                footprint(box[0] - 0.005, box[1] - 0.005, box[2] + 0.005, box[3] + 0.005),
                "sentinel-2", null, List.of(
                        cell(sceneKey + "-1", cx - 0.002, cy, ndvi),
                        cell(sceneKey + "-2", cx, cy + 0.002, ndvi),
                        cell(sceneKey + "-3", cx + 0.002, cy - 0.002, ndvi))));
    }

    /** Legacy scored case: no runs, no locks - the predecessor system's record. */
    private void histCase(String caseNo, String policyNo, long parcelId, LocalDate hazardDate,
                          Instant cutoff) {
        long boundaryVersionId = db.jdbc().queryForObject("""
                        select id from parcel_boundary_version where parcel_id = ? and version_no = 1
                        """, Long.class, parcelId);
        db.insert("""
                        insert into claim_case
                            (case_no, policy_no, parcel_id, crop_code, hazard_type, hazard_date,
                             scoring_cutoff, boundary_version_id, status)
                        values (?, ?, ?, 'WHEAT', 'DROUGHT', ?, ?, ?, 'SCORED')
                        """,
                caseNo, policyNo, parcelId, hazardDate, Db.timestamp(cutoff), boundaryVersionId);
    }

    // ---------------------------------------------------------------- utilities

    private static Instant startOf(LocalDate date) {
        return date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
    }

    private static Instant endOf(LocalDate date) {
        return date.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().minusSeconds(1);
    }

    private static IngestDtos.SceneCellRequest cell(String key, double lon, double lat, double ndvi) {
        return new IngestDtos.SceneCellRequest(key, lon, lat, ndvi, 0.09);
    }

    private JsonNode polygon(double minLon, double minLat, double maxLon, double maxLat) {
        ObjectNode node = db.mapper().createObjectNode();
        node.put("type", "Polygon");
        ArrayNode coords = node.putArray("coordinates");
        ArrayNode outer = coords.addArray();
        outer.addArray().add(minLon).add(minLat);
        outer.addArray().add(maxLon).add(minLat);
        outer.addArray().add(maxLon).add(maxLat);
        outer.addArray().add(minLon).add(maxLat);
        outer.addArray().add(minLon).add(minLat);
        return node;
    }

    private JsonNode footprint(double minLon, double minLat, double maxLon, double maxLat) {
        return polygon(minLon, minLat, maxLon, maxLat);
    }
}
