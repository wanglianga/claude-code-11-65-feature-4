package com.agri.pit;

import com.agri.pit.leakage.AsOfSelector;
import com.agri.pit.runs.SeriesVariables;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeriesVariablesTest {

    private static AsOfSelector.WeatherValue weather(String station, String key, Double temp,
                                                     String periodStart, String periodEnd) {
        return new AsOfSelector.WeatherValue(station, key, 1L, 1, 5.0, temp,
                Instant.parse(periodStart), Instant.parse(periodEnd), 1.0,
                Instant.parse(periodEnd), Instant.parse(periodEnd));
    }

    private static SeriesVariables.ScenePoint scene(long id, String day, double ndvi, Double cloud) {
        return new SeriesVariables.ScenePoint(id, "S-" + id, Instant.parse(day + "T10:00:00Z"),
                ndvi, 3, cloud);
    }

    @Test
    void gddAccumulatesPositiveDegreesOverWindow() {
        // Two 7-day weeks at 17C and 24C, base 10C -> (7*7) + (14*7) = 147.
        List<AsOfSelector.WeatherValue> values = List.of(
                weather("WS-1", "w1", 17.0, "2024-05-01T00:00:00Z", "2024-05-07T23:59:59Z"),
                weather("WS-1", "w2", 24.0, "2024-05-08T00:00:00Z", "2024-05-14T23:59:59Z"));
        SeriesVariables.GddResult gdd = SeriesVariables.gdd(values,
                Instant.parse("2024-05-01T00:00:00Z"), Instant.parse("2024-05-15T00:00:00Z"), 10.0);
        assertEquals(147.0, gdd.gdd(), 0.5);
        assertEquals(1.0, gdd.coverageRatio(), 0.01);
        assertEquals(2, gdd.periodsWithTemp());
    }

    @Test
    void gddSkipsNullTemperatureAndReportsCoverage() {
        List<AsOfSelector.WeatherValue> values = List.of(
                weather("WS-1", "w1", null, "2024-05-01T00:00:00Z", "2024-05-07T23:59:59Z"),
                weather("WS-1", "w2", 20.0, "2024-05-08T00:00:00Z", "2024-05-14T23:59:59Z"));
        SeriesVariables.GddResult gdd = SeriesVariables.gdd(values,
                Instant.parse("2024-05-01T00:00:00Z"), Instant.parse("2024-05-15T00:00:00Z"), 10.0);
        assertEquals(70.0, gdd.gdd(), 0.5);
        assertEquals(0.5, gdd.coverageRatio(), 0.01);
        assertEquals(1, gdd.periodsWithTemp());
        assertEquals(2, gdd.periodsTotal());
    }

    @Test
    void gddIgnoresPeriodsOutsideWindow() {
        List<AsOfSelector.WeatherValue> values = List.of(
                weather("WS-1", "w1", 30.0, "2024-03-01T00:00:00Z", "2024-03-07T23:59:59Z"));
        SeriesVariables.GddResult gdd = SeriesVariables.gdd(values,
                Instant.parse("2024-05-01T00:00:00Z"), Instant.parse("2024-05-15T00:00:00Z"), 10.0);
        assertEquals(0.0, gdd.gdd(), 0.001);
        assertEquals(0.0, gdd.coverageRatio(), 0.001);
    }

    @Test
    void slopeIsLeastSquaresPerDay() {
        // Perfectly linear series: 0.30 -> 0.40 over 20 days -> slope 0.005/day.
        List<SeriesVariables.ScenePoint> series = List.of(
                scene(1, "2024-06-01", 0.30, 5.0),
                scene(2, "2024-06-11", 0.35, 5.0),
                scene(3, "2024-06-21", 0.40, 5.0));
        Double slope = SeriesVariables.slopePerDay(series);
        assertEquals(0.005, slope, 1e-6);
    }

    @Test
    void slopeNeedsTwoDistinctDays() {
        assertNull(SeriesVariables.slopePerDay(List.of(scene(1, "2024-06-01", 0.30, 5.0))));
        assertNull(SeriesVariables.slopePerDay(List.of(
                scene(1, "2024-06-01", 0.30, 5.0),
                scene(2, "2024-06-01", 0.35, 5.0))));
    }

    @Test
    void sceneSeriesAveragesCellsPerScene() {
        List<AsOfSelector.SceneCellMatch> cells = List.of(
                cell(1, 0.30), cell(1, 0.40), cell(2, 0.50));
        List<SeriesVariables.ScenePoint> series = SeriesVariables.sceneSeries(cells);
        assertEquals(2, series.size());
        assertEquals(0.35, series.get(0).meanNdvi(), 1e-9);
        assertEquals(0.50, series.get(1).meanNdvi(), 1e-9);
        assertTrue(series.get(0).acquisitionAt().isBefore(series.get(1).acquisitionAt())
                || series.get(0).sceneId() < series.get(1).sceneId());
    }

    private static AsOfSelector.SceneCellMatch cell(long sceneId, double ndvi) {
        return new AsOfSelector.SceneCellMatch(sceneId, sceneId * 10, "S-" + sceneId, 1,
                Instant.parse("2024-06-0" + sceneId + "T10:00:00Z"),
                Instant.parse("2024-06-0" + sceneId + "T12:00:00Z"),
                116.0, 40.0, ndvi, 5.0);
    }
}
