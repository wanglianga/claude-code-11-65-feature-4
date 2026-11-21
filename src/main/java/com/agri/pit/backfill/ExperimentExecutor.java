package com.agri.pit.backfill;

import com.agri.pit.cases.CaseService;
import com.agri.pit.catalog.FormulaExpression;
import com.agri.pit.common.Db;
import com.agri.pit.common.Times;
import com.agri.pit.leakage.AsOfSelector;
import com.agri.pit.leakage.InputLedger;
import com.agri.pit.runs.SeriesVariables;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.locationtech.jts.geom.Geometry;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes one feature for one historical case under one definition version and one frozen
 * policy set. This is the shard-level engine of the backfill scheduler.
 * <p>
 * Two rules are hard-wired:
 * <ul>
 *   <li>EVALUABLE shards select inputs with {@code ceiling = case scoring cutoff}; nothing
 *       ingested later can leak in. A declaration that only exists after the cutoff produces a
 *       {@code LOOKAHEAD_RISK} missing reason, never a value.</li>
 *   <li>Missing-data policies are applied explicitly and recorded: cloud interpolation or
 *       exclusion, missing-weather exclusion, late-declaration flagging. The applications are
 *       stored on the snapshot so a metric can always be traced back to the strategy that
 *       produced it.</li>
 * </ul>
 */
@Service
public class ExperimentExecutor {

    private final Db db;
    private final AsOfSelector selector;

    public ExperimentExecutor(Db db, AsOfSelector selector) {
        this.db = db;
        this.selector = selector;
    }

    public record ExperimentResult(
            String valueStatus,                 // COMPUTED | MISSING_EXCLUDED | INSUFFICIENT_DATA
            Double value,
            List<String> missingReasons,
            ArrayNode policyApplications,
            ArrayNode inputs,
            ObjectNode outputs,
            Instant availabilityCeiling) {
    }

    public ExperimentResult compute(CaseService.CaseRow caseRow, String featureCode, String unit,
                                    long definitionVersionId, int definitionVersionNo, String formula,
                                    List<DependencyGraphService.Dependency> deps,
                                    Map<String, MissingPolicyService.Policy> policies,
                                    String caseClass) {
        Instant ceiling = "POST_HOC_ONLY".equals(caseClass)
                ? Instant.now() : caseRow.scoringCutoff();
        InputLedger.Builder ledger = new InputLedger.Builder();
        List<String> missing = new ArrayList<>();
        // True when a missing feature is caused by a policy exclusion (e.g. cloud EXCLUDE_MISSING)
        // rather than by data that simply was not available at the ceiling.
        boolean[] policyCausedMissing = {false};
        ArrayNode policyApps = db.mapper().createArrayNode();
        Map<String, Double> vars = new LinkedHashMap<>();

        DependencyGraphService.Dependency sowingDep = findDep(deps, "SOWING_DATE");
        DependencyGraphService.Dependency tempDep = findDep(deps, "TEMPERATURE_SERIES");
        DependencyGraphService.Dependency imageryDep = findDep(deps, "IMAGERY_SEQUENCE");

        // ---------------------------------------------------- sowing declaration
        AsOfSelector.DeclarationPick declaration = selector.selectDeclaration(
                ledger, caseRow.parcelId(), ceiling, caseRow.cropCode());
        if (declaration.missing() && sowingDep != null && sowingDep.required()) {
            missing.add(lateDeclarationReason(caseRow, ceiling, policies, policyApps));
        }
        LocalDate plantingDate = declaration.missing() ? null : declaration.value().plantingDate();
        if (!declaration.missing()) {
            vars.put("declared_area_ha", declaration.value().declaredAreaHa());
            vars.put("days_since_planting",
                    (double) java.time.temporal.ChronoUnit.DAYS.between(plantingDate, caseRow.hazardDate()));
            vars.put("planting_year", (double) plantingDate.getYear());
        }

        // ------------------------------------------------------ temperature series
        if (tempDep != null && missing.isEmpty()) {
            int fallbackDays = tempDep.paramInt("fallbackWindowDays", 120);
            double baseTempC = tempDep.paramDouble("baseTempC", SeriesVariables.DEFAULT_BASE_TEMP_C);
            double minCoverage = tempDep.paramDouble("minCoverage", 0.8);
            Instant windowStart = plantingDate != null
                    ? Times.startOfDay(plantingDate)
                    : Times.startOfDay(caseRow.hazardDate().minusDays(fallbackDays));
            Instant windowEnd = Times.endOfDayExclusive(caseRow.hazardDate());
            AsOfSelector.WeatherPick weather = selector.selectWeather(ledger, caseRow.parcelId(),
                    new AsOfSelector.Window(windowStart, windowEnd), ceiling);
            SeriesVariables.GddResult gdd = SeriesVariables.gdd(weather.values(), windowStart,
                    windowEnd, baseTempC);
            MissingPolicyService.Policy weatherPolicy = policies.get("MISSING_WEATHER");
            ObjectNode weatherApp = db.mapper().createObjectNode();
            weatherApp.put("policyCode", "MISSING_WEATHER");
            if (weatherPolicy != null) {
                weatherApp.put("policyVersionNo", weatherPolicy.versionNo());
                weatherApp.put("action", weatherPolicy.action());
            }
            weatherApp.put("coverage", round(gdd.coverageRatio()));
            weatherApp.put("periodsWithTemp", gdd.periodsWithTemp());
            weatherApp.put("periodsTotal", gdd.periodsTotal());
            weatherApp.put("minCoverage", minCoverage);
            weatherApp.put("note",
                    "EXCLUDE_MISSING: periods without temp_mean_c are excluded; the feature is "
                            + "computed over the visible periods and the coverage is reported");
            policyApps.add(weatherApp);
            if (gdd.periodsWithTemp() == 0) {
                missing.add("TEMPERATURE_SERIES: no temperature observations with temp_mean_c were "
                        + "visible at the availability ceiling (缺测气象)");
            } else {
                vars.put("gdd_effective", gdd.gdd());
                vars.put("temp_coverage", gdd.coverageRatio());
                vars.put("gdd_base_temp_c", baseTempC);
            }
        }

        // --------------------------------------------------------- imagery sequence
        if (imageryDep != null && missing.isEmpty()) {
            int windowDays = imageryDep.paramInt("windowDays", 45);
            double maxCloudPct = imageryDep.paramDouble("maxCloudPct", 30.0);
            int minScenes = imageryDep.paramInt("minScenes", 2);
            Instant windowStart = Times.startOfDay(caseRow.hazardDate().minusDays(windowDays));
            Instant windowEnd = Times.endOfDayExclusive(caseRow.hazardDate());
            Geometry geometry = selector.parseGeometry(caseRow.boundaryGeometry());
            AsOfSelector.ScenePick scenes = selector.selectSatellite(ledger, geometry,
                    new AsOfSelector.Window(windowStart, windowEnd), ceiling, null, caseRow.id(),
                    caseRow.parcelId(), caseRow.boundaryVersionId());
            List<SeriesVariables.ScenePoint> series = SeriesVariables.sceneSeries(scenes.cells());
            List<SeriesVariables.ScenePoint> adjusted = applyCloudPolicy(series, maxCloudPct,
                    policies.get("CLOUD_OCCLUSION"), policyApps);
            vars.put("ndvi_recovery_samples", (double) adjusted.size());
            if (adjusted.size() < minScenes) {
                boolean policyCaused = series.size() >= minScenes;
                policyCausedMissing[0] = policyCausedMissing[0] || policyCaused;
                missing.add("IMAGERY_SEQUENCE: " + adjusted.size() + " usable scene(s) after the "
                        + "cloud-occlusion policy (need " + minScenes + "); shortfall caused by "
                        + (policyCaused ? "policy exclusion" : "availability at the ceiling"));
            } else {
                Double slope = SeriesVariables.slopePerDay(adjusted);
                if (slope == null) {
                    missing.add("IMAGERY_SEQUENCE: scene acquisitions do not span two distinct "
                            + "days; the recovery slope is undefined");
                } else {
                    vars.put("ndvi_recovery_slope", slope);
                }
            }
        }

        // --------------------------------------------------------------- evaluation
        selector.recordBoundary(ledger, caseRow.boundaryVersionId(), caseRow.boundaryVersionNo(),
                caseRow.boundaryObservedAt(), caseRow.boundaryIngestedAt(), ceiling);
        vars.put("boundary_area_ha", caseRow.boundaryAreaHa());
        vars.put("hazard_day_of_year", (double) caseRow.hazardDate().getDayOfYear());

        ObjectNode outputs = db.mapper().createObjectNode();
        outputs.put("featureCode", featureCode);
        outputs.put("definitionVersionId", definitionVersionId);
        outputs.put("definitionVersionNo", definitionVersionNo);
        outputs.put("formula", formula);
        outputs.put("unit", unit);
        ObjectNode varNode = db.mapper().createObjectNode();
        vars.forEach((k, v) -> varNode.put(k, BigDecimal.valueOf(v)
                .setScale(6, RoundingMode.HALF_UP).doubleValue()));
        outputs.set("variables", varNode);

        Double value = null;
        String valueStatus;
        if (!missing.isEmpty()) {
            valueStatus = policyCausedMissing[0] ? "MISSING_EXCLUDED" : "INSUFFICIENT_DATA";
        } else {
            FormulaExpression expression;
            try {
                expression = FormulaExpression.compile(formula);
            } catch (IllegalArgumentException e) {
                missing.add("formula compile failed: " + e.getMessage());
                return finish("INSUFFICIENT_DATA", null, missing, policyApps, ledger, outputs, ceiling);
            }
            List<String> missingVars = expression.variables().stream()
                    .filter(v -> !vars.containsKey(v)).toList();
            if (!missingVars.isEmpty()) {
                missing.add("formula variables unavailable from point-in-time inputs: " + missingVars);
                valueStatus = "INSUFFICIENT_DATA";
            } else {
                double computed = expression.evaluate(vars);
                if (Double.isNaN(computed) || Double.isInfinite(computed)) {
                    missing.add("formula produced a non-finite value");
                    valueStatus = "INSUFFICIENT_DATA";
                } else {
                    value = BigDecimal.valueOf(computed).setScale(6, RoundingMode.HALF_UP).doubleValue();
                    valueStatus = "COMPUTED";
                }
            }
        }
        if (value != null) {
            outputs.put("value", value);
        } else {
            outputs.putNull("value");
        }
        return finish(valueStatus, value, missing, policyApps, ledger, outputs, ceiling);
    }

    private ExperimentResult finish(String valueStatus, Double value, List<String> missing,
                                    ArrayNode policyApps, InputLedger.Builder ledger,
                                    ObjectNode outputs, Instant ceiling) {
        ArrayNode inputs = db.mapper().createArrayNode();
        for (InputLedger.Candidate candidate : ledger.build().candidates()) {
            ObjectNode input = db.mapper().createObjectNode();
            input.put("sourceType", candidate.sourceType().name());
            input.put("sourceTable", candidate.sourceTable());
            input.put("sourceId", candidate.sourceId());
            if (candidate.revisionNo() != null) {
                input.put("revisionNo", candidate.revisionNo());
            }
            input.put("observedAt", Times.format(candidate.observedAt()));
            input.put("ingestedAt", Times.format(candidate.ingestedAt()));
            input.put("eligibility", candidate.verdict().name());
            input.put("reason", candidate.reason());
            if (candidate.contributedValue() != null) {
                input.put("contributedValue", candidate.contributedValue());
            }
            inputs.add(input);
        }
        outputs.put("valueStatus", valueStatus);
        return new ExperimentResult(valueStatus, value, List.copyOf(missing), policyApps, inputs,
                outputs, ceiling);
    }

    /** Cloud-occlusion policy: interpolate occluded scenes or exclude them, recording every move. */
    private List<SeriesVariables.ScenePoint> applyCloudPolicy(
            List<SeriesVariables.ScenePoint> series, double maxCloudPct,
            MissingPolicyService.Policy policy, ArrayNode policyApps) {
        String action = policy == null ? "EXCLUDE_MISSING" : policy.action();
        ObjectNode app = db.mapper().createObjectNode();
        app.put("policyCode", "CLOUD_OCCLUSION");
        app.put("action", action);
        app.put("maxCloudPct", maxCloudPct);
        if (policy != null) {
            app.put("policyVersionNo", policy.versionNo());
        }
        ArrayNode sceneApps = db.mapper().createArrayNode();
        List<SeriesVariables.ScenePoint> clear = series.stream()
                .filter(p -> p.cloudCoverPct() == null || p.cloudCoverPct() <= maxCloudPct)
                .toList();
        List<SeriesVariables.ScenePoint> adjusted = new ArrayList<>();
        for (SeriesVariables.ScenePoint point : series) {
            boolean isClear = point.cloudCoverPct() == null || point.cloudCoverPct() <= maxCloudPct;
            if (isClear) {
                adjusted.add(point);
                continue;
            }
            ObjectNode sceneApp = db.mapper().createObjectNode();
            sceneApp.put("sceneKey", point.sceneKey());
            sceneApp.put("cloudCoverPct", point.cloudCoverPct());
            sceneApp.put("originalMeanNdvi", round(point.meanNdvi()));
            if ("INTERPOLATE".equals(action)) {
                SeriesVariables.ScenePoint before = null;
                SeriesVariables.ScenePoint after = null;
                for (SeriesVariables.ScenePoint c : clear) {
                    if (c.acquisitionAt().isBefore(point.acquisitionAt())
                            && (before == null || c.acquisitionAt().isAfter(before.acquisitionAt()))) {
                        before = c;
                    }
                    if (c.acquisitionAt().isAfter(point.acquisitionAt())
                            && (after == null || c.acquisitionAt().isBefore(after.acquisitionAt()))) {
                        after = c;
                    }
                }
                Double interpolated = null;
                if (before != null && after != null) {
                    double span = after.acquisitionAt().getEpochSecond()
                            - before.acquisitionAt().getEpochSecond();
                    double f = span <= 0 ? 0.5
                            : (point.acquisitionAt().getEpochSecond()
                            - before.acquisitionAt().getEpochSecond()) / span;
                    interpolated = before.meanNdvi() + f * (after.meanNdvi() - before.meanNdvi());
                } else if (before != null) {
                    interpolated = before.meanNdvi();
                } else if (after != null) {
                    interpolated = after.meanNdvi();
                }
                if (interpolated == null) {
                    sceneApp.put("action", "EXCLUDED");
                    sceneApp.put("note", "no clear anchor scene available for interpolation");
                } else {
                    sceneApp.put("action", "INTERPOLATED");
                    sceneApp.put("adjustedMeanNdvi", round(interpolated));
                    adjusted.add(new SeriesVariables.ScenePoint(point.sceneId(), point.sceneKey(),
                            point.acquisitionAt(), interpolated, point.cells(), point.cloudCoverPct()));
                }
            } else {
                sceneApp.put("action", "EXCLUDED");
                sceneApp.put("note", "cloud cover above " + maxCloudPct + "%; excluded by policy");
            }
            sceneApps.add(sceneApp);
        }
        app.set("scenes", sceneApps);
        app.put("usableScenes", adjusted.size());
        app.put("visibleScenes", series.size());
        policyApps.add(app);
        adjusted.sort((a, b) -> a.acquisitionAt().compareTo(b.acquisitionAt()));
        return adjusted;
    }

    /** Late declarations are flagged, never adopted: the exact look-ahead risk rule. */
    private String lateDeclarationReason(CaseService.CaseRow caseRow, Instant ceiling,
                                         Map<String, MissingPolicyService.Policy> policies,
                                         ArrayNode policyApps) {
        List<Map<String, Object>> late = db.jdbc().queryForList("""
                        select declaration_no, ingested_at from planting_declaration
                         where parcel_id = ? and ingested_at > ?
                         order by ingested_at
                        """, caseRow.parcelId(), Db.timestamp(ceiling));
        MissingPolicyService.Policy policy = policies.get("LATE_DECLARATION");
        ObjectNode app = db.mapper().createObjectNode();
        app.put("policyCode", "LATE_DECLARATION");
        app.put("action", "FLAG_LOOKAHEAD_RISK");
        if (policy != null) {
            app.put("policyVersionNo", policy.versionNo());
        }
        app.put("lateDeclarations", late.size());
        policyApps.add(app);
        if (!late.isEmpty()) {
            return "SOWING_DATE: planting declaration " + late.get(0).get("declaration_no")
                    + " was ingested at " + late.get(0).get("ingested_at")
                    + ", after the availability ceiling " + Times.format(ceiling)
                    + "; flagged as look-ahead risk (穿越风险) by the LATE_DECLARATION policy and not adopted";
        }
        return "SOWING_DATE: no planting declaration was ever recorded for this parcel";
    }

    private static DependencyGraphService.Dependency findDep(
            List<DependencyGraphService.Dependency> deps, String key) {
        return deps.stream().filter(d -> d.depKey().equals(key)).findFirst().orElse(null);
    }

    private static double round(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }
}
