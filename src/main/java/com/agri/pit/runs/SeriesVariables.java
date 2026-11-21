package com.agri.pit.runs;

import com.agri.pit.leakage.AsOfSelector;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes the time-series aggregate variables used by the model team's two new features:
 * <ul>
 *   <li><b>gdd_effective</b> — effective growing degree days after sowing:
 *       {@code Σ max(0, tempMeanC − baseTempC) × overlapDays} over the weather periods that
 *       intersect the sowing→hazard window, weight-averaged across the parcel's stations.</li>
 *   <li><b>ndvi_recovery_slope</b> — least-squares slope (NDVI per day) of the per-scene mean
 *       NDVI series inside the pre-hazard imagery window. Needs at least two scenes.</li>
 * </ul>
 * Pure functions over already-selected, point-in-time-correct inputs; shared by the scoring
 * executor and the experiment backfill executor so both see identical arithmetic.
 */
public final class SeriesVariables {

    /** Base temperature for effective accumulated temperature (wheat convention). */
    public static final double DEFAULT_BASE_TEMP_C = 10.0d;

    private static final double SECONDS_PER_DAY = 86_400d;

    private SeriesVariables() {
    }

    /** One usable scene in the imagery sequence: its mean NDVI over owned cells. */
    public record ScenePoint(long sceneId, String sceneKey, Instant acquisitionAt, double meanNdvi,
                             int cells, Double cloudCoverPct) {
    }

    /** Collapse cell-level matches into one point per scene, ordered by acquisition time. */
    public static List<ScenePoint> sceneSeries(List<AsOfSelector.SceneCellMatch> cells) {
        Map<Long, List<AsOfSelector.SceneCellMatch>> byScene = new LinkedHashMap<>();
        for (AsOfSelector.SceneCellMatch cell : cells) {
            byScene.computeIfAbsent(cell.sceneId(), k -> new ArrayList<>()).add(cell);
        }
        List<ScenePoint> series = new ArrayList<>();
        for (List<AsOfSelector.SceneCellMatch> group : byScene.values()) {
            AsOfSelector.SceneCellMatch first = group.get(0);
            double mean = group.stream().mapToDouble(AsOfSelector.SceneCellMatch::ndvi)
                    .average().orElse(Double.NaN);
            series.add(new ScenePoint(first.sceneId(), first.sceneKey(), first.acquisitionAt(),
                    mean, group.size(), first.cloudCoverPct()));
        }
        series.sort((a, b) -> a.acquisitionAt().compareTo(b.acquisitionAt()));
        return series;
    }

    /**
     * Least-squares slope of mean NDVI per day. Returns null when fewer than two scenes or all
     * acquisitions share the same timestamp (the slope is then undefined).
     */
    public static Double slopePerDay(List<ScenePoint> series) {
        if (series.size() < 2) {
            return null;
        }
        double[] xs = new double[series.size()];
        double[] ys = new double[series.size()];
        long t0 = series.get(0).acquisitionAt().getEpochSecond();
        for (int i = 0; i < series.size(); i++) {
            xs[i] = (series.get(i).acquisitionAt().getEpochSecond() - t0) / SECONDS_PER_DAY;
            ys[i] = series.get(i).meanNdvi();
        }
        double meanX = 0;
        double meanY = 0;
        for (int i = 0; i < xs.length; i++) {
            meanX += xs[i];
            meanY += ys[i];
        }
        meanX /= xs.length;
        meanY /= ys.length;
        double sxy = 0;
        double sxx = 0;
        for (int i = 0; i < xs.length; i++) {
            sxy += (xs[i] - meanX) * (ys[i] - meanY);
            sxx += (xs[i] - meanX) * (xs[i] - meanX);
        }
        if (sxx < 1e-9) {
            return null;
        }
        return sxy / sxx;
    }

    public record GddResult(double gdd, double coverageRatio, int periodsWithTemp, int periodsTotal) {
    }

    /**
     * Effective growing degree days inside [windowStart, windowEnd]. Only periods with a non-null
     * mean temperature contribute; the coverage ratio says how much of the window they span.
     * Stations are combined with their parcel link weights.
     */
    public static GddResult gdd(List<AsOfSelector.WeatherValue> values, Instant windowStart,
                                Instant windowEnd, double baseTempC) {
        Map<String, List<AsOfSelector.WeatherValue>> byStation = new LinkedHashMap<>();
        Map<String, Double> weights = new LinkedHashMap<>();
        for (AsOfSelector.WeatherValue value : values) {
            byStation.computeIfAbsent(value.stationCode(), k -> new ArrayList<>()).add(value);
            weights.putIfAbsent(value.stationCode(), value.weight());
        }
        double windowSeconds = Math.max(1, Duration.between(windowStart, windowEnd).toSeconds());
        double weightedGdd = 0;
        double weightedCoverage = 0;
        double totalWeight = 0;
        int periodsWithTemp = 0;
        int periodsTotal = 0;
        for (Map.Entry<String, List<AsOfSelector.WeatherValue>> entry : byStation.entrySet()) {
            double weight = weights.get(entry.getKey());
            double stationGdd = 0;
            double coveredSeconds = 0;
            for (AsOfSelector.WeatherValue value : entry.getValue()) {
                periodsTotal++;
                if (value.tempMeanC() == null) {
                    continue;
                }
                periodsWithTemp++;
                Instant start = value.periodStart().isBefore(windowStart) ? windowStart : value.periodStart();
                Instant end = value.periodEnd().isAfter(windowEnd) ? windowEnd : value.periodEnd();
                double overlapSeconds = Math.max(0, Duration.between(start, end).toSeconds());
                double overlapDays = overlapSeconds / SECONDS_PER_DAY;
                stationGdd += Math.max(0d, value.tempMeanC() - baseTempC) * overlapDays;
                coveredSeconds += overlapSeconds;
            }
            weightedGdd += weight * stationGdd;
            weightedCoverage += weight * Math.min(1d, coveredSeconds / windowSeconds);
            totalWeight += weight;
        }
        if (totalWeight == 0) {
            return new GddResult(0d, 0d, 0, 0);
        }
        return new GddResult(weightedGdd / totalWeight, weightedCoverage / totalWeight,
                periodsWithTemp, periodsTotal);
    }
}
