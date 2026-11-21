package com.agri.pit.backfill;

import com.agri.pit.catalog.CatalogService;
import com.agri.pit.common.ApiException;
import com.agri.pit.common.Db;
import com.agri.pit.common.Times;
import com.agri.pit.model.SourceType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Feature dependency graph. Registers which point-in-time inputs a feature needs in order to
 * be computable for a historical case: the sowing date (planting declaration), a temperature
 * series (weather observations) or an imagery sequence (satellite scenes). The scanner and the
 * backfill executor both read this graph, so availability checks and computation always agree
 * on what a feature depends on.
 */
@Service
public class DependencyGraphService {

    /** Well-known dependency kinds with their source type and default parameters. */
    public static final Map<String, DepKind> DEP_KINDS = Map.of(
            "SOWING_DATE", new DepKind(SourceType.PLANTING_DECLARATION,
                    Map.of()),
            "TEMPERATURE_SERIES", new DepKind(SourceType.WEATHER_OBSERVATION,
                    Map.of("baseTempC", 10.0, "minCoverage", 0.8, "fallbackWindowDays", 120)),
            "IMAGERY_SEQUENCE", new DepKind(SourceType.SATELLITE_NDVI,
                    Map.of("windowDays", 45, "maxCloudPct", 30.0, "minScenes", 2)));

    public record DepKind(SourceType sourceType, Map<String, Object> defaultParams) {
    }

    public record Dependency(long id, long featureDefinitionId, String featureCode, String depKey,
                             SourceType sourceType, boolean required, JsonNode params, String notes) {
        public double paramDouble(String key, double fallback) {
            JsonNode node = params == null ? null : params.get(key);
            return node != null && node.isNumber() ? node.asDouble() : fallback;
        }

        public int paramInt(String key, int fallback) {
            JsonNode node = params == null ? null : params.get(key);
            return node != null && node.isInt() ? node.asInt()
                    : node != null && node.isNumber() ? node.intValue() : fallback;
        }
    }

    private final Db db;
    private final CatalogService catalog;

    public DependencyGraphService(Db db, CatalogService catalog) {
        this.db = db;
        this.catalog = catalog;
    }

    @Transactional
    public long register(BackfillDtos.RegisterDependencyRequest req) {
        if (req.featureCode() == null || req.depKey() == null) {
            throw ApiException.badRequest("DEPENDENCY_FIELDS_REQUIRED",
                    "featureCode and depKey are required");
        }
        CatalogService.FeatureRow feature = catalog.requireFeature(req.featureCode());
        String depKey = req.depKey().trim().toUpperCase();
        DepKind kind = DEP_KINDS.get(depKey);
        if (kind == null) {
            throw ApiException.badRequest("UNKNOWN_DEP_KEY",
                    "depKey must be one of " + DEP_KINDS.keySet() + "; got " + depKey);
        }
        SourceType sourceType = kind.sourceType();
        if (req.sourceType() != null && !req.sourceType().isBlank()) {
            SourceType requested = SourceType.parse(req.sourceType());
            if (requested != sourceType) {
                throw ApiException.badRequest("DEP_SOURCE_MISMATCH",
                        "depKey " + depKey + " reads from " + sourceType + ", not " + requested);
            }
        }
        Integer exists = db.jdbc().queryForObject("""
                        select count(*) from feature_dependency
                         where feature_definition_id = ? and dep_key = ?
                        """, Integer.class, feature.id(), depKey);
        if (exists != null && exists > 0) {
            throw ApiException.conflict("DEPENDENCY_EXISTS",
                    "dependency " + depKey + " is already registered for " + feature.code());
        }
        ObjectNode params = db.mapper().createObjectNode();
        kind.defaultParams().forEach(params::putPOJO);
        if (req.params() != null && req.params().isObject()) {
            params.setAll((ObjectNode) req.params());
        }
        return db.insert("""
                        insert into feature_dependency
                            (feature_definition_id, dep_key, source_type, required, params, notes)
                        values (?, ?, ?, ?, ?::jsonb, ?)
                        """,
                feature.id(), depKey, sourceType.name(),
                req.required() == null || req.required(), db.jsonArg(params), req.notes());
    }

    public List<Dependency> dependenciesFor(long featureDefinitionId) {
        return db.jdbc().query("""
                        select d.id, d.feature_definition_id, fd.code, d.dep_key, d.source_type,
                               d.required, d.params, d.notes
                          from feature_dependency d
                          join feature_definition fd on fd.id = d.feature_definition_id
                         where d.feature_definition_id = ?
                         order by d.dep_key
                        """,
                (rs, i) -> new Dependency(rs.getLong("id"), rs.getLong("feature_definition_id"),
                        rs.getString("code"), rs.getString("dep_key"),
                        SourceType.valueOf(rs.getString("source_type")), rs.getBoolean("required"),
                        db.readTree(rs.getString("params")), rs.getString("notes")),
                featureDefinitionId);
    }

    public List<BackfillDtos.DependencyView> list(String featureCode) {
        String sql = """
                select d.id, fd.code, d.dep_key, d.source_type, d.required, d.params, d.notes, d.created_at
                  from feature_dependency d
                  join feature_definition fd on fd.id = d.feature_definition_id
                """;
        java.util.List<BackfillDtos.DependencyView> result;
        if (featureCode == null || featureCode.isBlank()) {
            result = db.jdbc().query(sql + " order by fd.code, d.dep_key", (rs, i) -> map(rs));
        } else {
            CatalogService.FeatureRow feature = catalog.requireFeature(featureCode);
            result = db.jdbc().query(sql + " where fd.id = ? order by d.dep_key",
                    (rs, i) -> map(rs), feature.id());
        }
        return result;
    }

    private BackfillDtos.DependencyView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new BackfillDtos.DependencyView(rs.getLong("id"), rs.getString("code"),
                rs.getString("dep_key"), rs.getString("source_type"), rs.getBoolean("required"),
                db.readTree(rs.getString("params")), rs.getString("notes"),
                Times.format(Db.instant(rs, "created_at")));
    }

    /** Convenience for tests and the seeder: register if absent, otherwise keep the existing row. */
    @Transactional
    public void ensureRegistered(String featureCode, String depKey, JsonNode params, String notes) {
        CatalogService.FeatureRow feature = catalog.requireFeature(featureCode);
        Integer exists = db.jdbc().queryForObject("""
                        select count(*) from feature_dependency
                         where feature_definition_id = ? and dep_key = ?
                        """, Integer.class, feature.id(), depKey.trim().toUpperCase());
        if (exists != null && exists > 0) {
            return;
        }
        register(new BackfillDtos.RegisterDependencyRequest(featureCode, depKey, null, null,
                params, notes));
    }

    /** Policy codes relevant for a dependency, used when freezing an experiment's policy set. */
    public static Set<String> policyCodesFor(String depKey) {
        return switch (depKey) {
            case "SOWING_DATE" -> Set.of("LATE_DECLARATION");
            case "TEMPERATURE_SERIES" -> Set.of("MISSING_WEATHER");
            case "IMAGERY_SEQUENCE" -> Set.of("CLOUD_OCCLUSION");
            default -> Set.of();
        };
    }
}
