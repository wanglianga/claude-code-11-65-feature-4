package com.agri.pit.demo;

import com.agri.pit.common.Db;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private final Db db;

    public DemoController(Db db) {
        this.db = db;
    }

    /** Self-describing index of the three sample scenarios and their expected endpoints. */
    @GetMapping("/scenarios")
    public Map<String, Object> scenarios() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cases", List.of(
                Map.of("caseNo", DemoSeeder.CASE_PRIMARY,
                        "parcel", "P-1001",
                        "lockedBoundary", "v1",
                        "note", "locks boundary v1; a variant run on v2 must not double count shared pixels"),
                Map.of("caseNo", DemoSeeder.CASE_SECONDARY,
                        "parcel", "P-1002",
                        "lockedBoundary", "v1",
                        "note", "control case without overlapping boundaries")));
        body.put("scenario1LateScene", Map.of(
                "scene", DemoSeeder.SCENE_LATE,
                "acquisition", "2024-06-18T09:15:00Z",
                "ingested", "2024-06-25T03:00:00Z",
                "caseCutoff", DemoSeeder.CASE_CUTOFF.toString(),
                "expected", "original scoring run verdict PASS_WITH_EXCLUSIONS (or BLOCKED if required data "
                        + "vanishes); the late scene appears as EXCLUDED_LATE_AVAILABILITY in the input ledger",
                "howToCheck", List.of(
                        "GET /api/cases/" + DemoSeeder.CASE_PRIMARY,
                        "GET /api/cases/" + DemoSeeder.CASE_PRIMARY + "/trace",
                        "GET /api/runs/{runId} -> inputs[] and leakage.violations[]")));
        body.put("scenario2OverlappingBoundaries", Map.of(
                "parcel", "P-1001",
                "versions", List.of("v1 (locked by case)", "v2 (re-survey, overlaps v1)"),
                "expected", "variant run on v2 records the shared cells as EXCLUDED_PIXEL_ALLOCATED_OTHER_PARCEL "
                        + "and never adds them into v2's mean NDVI",
                "howToCheck", List.of(
                        "POST /api/impact/boundary-variant-runs {caseNo, boundaryVersionId}",
                        "GET /api/runs/{variantRunId} -> inputs[] with exclusion verdicts")));
        body.put("scenario3WeatherRevision", Map.of(
                "station", "WS-001",
                "observation", DemoSeeder.WEATHER_REVISION_KEY,
                "revisionIngested", "2024-07-08T02:00:00Z",
                "expected", "impact analysis reports RECOMPUTE_CANDIDATE, a controlled backfill runs, and a "
                        + "PENDING_REVIEW candidate is created; the frozen snapshot and payout stay unchanged",
                "howToCheck", List.of(
                        "GET /api/warehouse/revision-events?sourceType=WEATHER_OBSERVATION",
                        "POST /api/impact/analyses/by-revision/{revisionEventId}",
                        "GET /api/review/candidates?status=PENDING_REVIEW")));
        body.put("backfillLab", Map.of(
                "newFeatures", List.of(
                        DemoSeeder.FEATURE_GDD + " (v1 raw, v2 calibrated x0.85)",
                        DemoSeeder.FEATURE_SLOPE + " (v1)"),
                "historicalCases", List.of(
                        DemoSeeder.CASE_CLEAN + " - clean history, evaluable for both features",
                        DemoSeeder.CASE_LATE_DECL + " - declaration ingested after the cutoff: "
                                + "GDD is LOOKAHEAD_RISK / POST_HOC_ONLY",
                        DemoSeeder.CASE_CLOUD_LEAD + " - leading cloud occlusion: interpolation and "
                                + "exclusion give different slopes",
                        DemoSeeder.CASE_CLOUD_TRAIL + " - one cloud-free scene: exclusion reports "
                                + "the slope missing",
                        DemoSeeder.CASE_NO_TEMP + " - temp_mean_c missing: GDD is POST_HOC_ONLY"),
                "flow", List.of(
                        "GET  /api/backfill/dependencies?featureCode=GDD_AFTER_SOWING",
                        "POST /api/backfill/scans {\"featureCode\":\"GDD_AFTER_SOWING\"}",
                        "GET  /api/backfill/scans/{scanId} -> EVALUABLE vs POST_HOC_ONLY",
                        "POST /api/backfill/jobs {\"experimentKey\":\"EXP-...\",\"featureCode\":\"...\"}",
                        "POST /api/backfill/jobs/{jobId}/execute",
                        "GET  /api/backfill/jobs/{jobId} -> shard status",
                        "GET  /api/experiments/{id} -> metrics + availability statement",
                        "GET  /api/experiments/compare?left=..&right=.."),
                "rules", List.of(
                        "a declaration ingested after the case cutoff is LOOKAHEAD_RISK, never adopted",
                        "a cloud-policy change (INTERPOLATE -> EXCLUDE_MISSING) requires a new "
                                + "experiment; reported metrics are never overwritten",
                        "two definition versions backfilling one case are stored as separate "
                                + "snapshots keyed by (experiment, case, definition version)")));
        body.put("counts", db.jdbc().queryForMap("""
                select (select count(*) from claim_case) as cases,
                       (select count(*) from scoring_snapshot) as snapshots,
                       (select count(*) from review_candidate) as candidates,
                       (select count(*) from feature_run) as runs,
                       (select count(*) from experiment) as experiments,
                       (select count(*) from experiment_snapshot) as experiment_snapshots
                """));
        return body;
    }
}
