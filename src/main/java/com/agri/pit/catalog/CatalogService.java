package com.agri.pit.catalog;

import com.agri.pit.common.ApiException;
import com.agri.pit.common.Db;
import com.agri.pit.common.Times;
import com.agri.pit.model.SourceType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Service
public class CatalogService {

    private final Db db;

    public CatalogService(Db db) {
        this.db = db;
    }

    @Transactional
    public long createFeature(CatalogDtos.CreateFeatureRequest req) {
        String code = req.code().trim().toUpperCase();
        if (!code.matches("[A-Z0-9_]{2,64}")) {
            throw ApiException.badRequest("INVALID_FEATURE_CODE",
                    "feature code must be 2-64 chars of A-Z, 0-9 or underscore");
        }
        List<SourceType> sources = req.sourceTypes().stream().map(SourceType::parse).toList();
        if (sources.isEmpty()) {
            throw ApiException.badRequest("SOURCE_REQUIRED", "at least one source type is required");
        }
        Integer existing = db.jdbc().queryForObject(
                "select count(*) from feature_definition where code = ?", Integer.class, code);
        if (existing != null && existing > 0) {
            throw ApiException.conflict("FEATURE_EXISTS", "feature already exists: " + code);
        }
        ArrayNode sourceJson = db.mapper().createArrayNode();
        sources.forEach(s -> sourceJson.add(s.name()));
        return db.insert("""
                        insert into feature_definition (code, name, description, unit, source_types, status)
                        values (?, ?, ?, ?, ?::jsonb, 'DRAFT')
                        """,
                code, req.name().trim(), req.description(), req.unit(),
                db.toJson(sourceJson));
    }

    @Transactional
    public long createVersion(String featureCode, CatalogDtos.CreateVersionRequest req) {
        FeatureRow feature = requireFeature(featureCode);
        String formula = req.formula().trim();
        if (formula.isEmpty() || formula.length() > 2000) {
            throw ApiException.badRequest("INVALID_FORMULA", "formula must be 1-2000 characters");
        }
        String asOfPolicy = req.asOfPolicy() == null ? "AS_OF_CUTOFF" : req.asOfPolicy().toUpperCase();
        if (!asOfPolicy.equals("AS_OF_CUTOFF")) {
            throw ApiException.badRequest("UNSUPPORTED_AS_OF_POLICY",
                    "only AS_OF_CUTOFF is supported for claim scoring; controlled backfills set the ceiling explicitly");
        }
        int lookback = req.lookbackDays() == null ? 0 : req.lookbackDays();
        long lag = req.availabilityLagSeconds() == null ? 0 : req.availabilityLagSeconds();
        Integer nextVersion = db.jdbc().queryForObject(
                "select coalesce(max(version_no), 0) + 1 from feature_definition_version where feature_definition_id = ?",
                Integer.class, feature.id());
        Long previousId = db.jdbc().query(
                "select id from feature_definition_version where feature_definition_id = ? order by version_no desc limit 1",
                rs -> rs.next() ? rs.getLong(1) : null, feature.id());
        // Validate the formula eagerly by compiling it against the supported grammar.
        FormulaExpression.compile(formula);
        return db.insert("""
                        insert into feature_definition_version
                            (feature_definition_id, version_no, formula, required, availability_lag_seconds,
                             as_of_policy, lookback_days, status, checksum, notes, supersedes_version_id)
                        values (?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?)
                        """,
                feature.id(), nextVersion, formula, req.required() == null || req.required(),
                lag, asOfPolicy, lookback, checksum(formula), req.notes(), previousId);
    }

    @Transactional
    public void approve(String featureCode, int versionNo, String approvedBy, String notes) {
        FeatureRow feature = requireFeature(featureCode);
        VersionRow version = requireVersion(feature.id(), versionNo);
        if (!version.status().equals("DRAFT")) {
            throw ApiException.conflict("VERSION_NOT_DRAFT",
                    "version " + versionNo + " is " + version.status() + " and cannot be approved again");
        }
        String approver = approvedBy == null || approvedBy.isBlank() ? "catalog-admin" : approvedBy.trim();
        Instant now = Instant.now();
        db.jdbc().update("""
                        update feature_definition_version
                           set status = 'APPROVED', approved_by = ?, approved_at = ?, notes = coalesce(?, notes)
                         where id = ?
                        """,
                approver, Db.timestamp(now),
                notes == null || notes.isBlank() ? null : notes, version.id());
        db.jdbc().update("""
                        update feature_definition
                           set status = 'APPROVED', current_version_id = ?, updated_at = now()
                         where id = ?
                        """, version.id(), feature.id());
    }

    public List<CatalogDtos.FeatureSummary> listFeatures() {
        return db.jdbc().query("""
                        select fd.id, fd.code, fd.name, fd.description, fd.unit, fd.source_types,
                               fd.status, fv.version_no, fv.approved_at
                          from feature_definition fd
                          left join feature_definition_version fv on fv.id = fd.current_version_id
                         order by fd.code
                        """,
                (rs, i) -> new CatalogDtos.FeatureSummary(
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("unit"),
                        sourceList(db.readTree(rs.getString("source_types"))),
                        rs.getString("status"),
                        (Integer) rs.getObject("version_no"),
                        rs.getObject("approved_at", OffsetDateTime.class) == null
                                ? null : rs.getObject("approved_at", OffsetDateTime.class).toString()));
    }

    public CatalogDtos.FeatureDetail getFeature(String code) {
        FeatureRow feature = requireFeature(code);
        List<CatalogDtos.VersionView> versions = db.jdbc().query("""
                        select fv.id, fd.code, fv.version_no, fv.formula, fv.required, fv.availability_lag_seconds,
                               fv.as_of_policy, fv.lookback_days, fv.status, fv.approved_by, fv.approved_at,
                               fv.created_at, fv.checksum, fv.notes, fv.supersedes_version_id
                          from feature_definition_version fv
                          join feature_definition fd on fd.id = fv.feature_definition_id
                         where fv.feature_definition_id = ?
                         order by fv.version_no
                        """,
                (rs, i) -> new CatalogDtos.VersionView(
                        rs.getLong("id"), rs.getString("code"), rs.getInt("version_no"),
                        rs.getString("formula"), rs.getBoolean("required"),
                        rs.getLong("availability_lag_seconds"), rs.getString("as_of_policy"),
                        rs.getInt("lookback_days"), rs.getString("status"), rs.getString("approved_by"),
                        ts(rs.getObject("approved_at", OffsetDateTime.class)),
                        ts(rs.getObject("created_at", OffsetDateTime.class)),
                        rs.getString("checksum"), rs.getString("notes"),
                        (Long) rs.getObject("supersedes_version_id")),
                feature.id());
        CatalogDtos.FeatureSummary summary = new CatalogDtos.FeatureSummary(
                feature.code(), feature.name(), feature.description(), feature.unit(),
                feature.sourceTypes(), feature.status(), feature.currentVersion(), null);
        return new CatalogDtos.FeatureDetail(summary, versions);
    }

    public FeatureRow requireFeature(String code) {
        List<FeatureRow> rows = db.jdbc().query("""
                        select fd.id, fd.code, fd.name, fd.description, fd.unit, fd.source_types, fd.status,
                               fv.version_no
                          from feature_definition fd
                          left join feature_definition_version fv on fv.id = fd.current_version_id
                         where fd.code = ?
                        """,
                (rs, i) -> new FeatureRow(
                        rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                        rs.getString("description"), rs.getString("unit"),
                        sourceList(db.readTree(rs.getString("source_types"))),
                        rs.getString("status"), (Integer) rs.getObject("version_no")),
                code.trim().toUpperCase());
        if (rows.isEmpty()) {
            throw ApiException.notFound("FEATURE_NOT_FOUND", "feature not found: " + code);
        }
        return rows.get(0);
    }

    public VersionRow requireVersion(long featureDefinitionId, int versionNo) {
        List<VersionRow> rows = db.jdbc().query("""
                        select id, feature_definition_id, version_no, formula, required, availability_lag_seconds,
                               as_of_policy, lookback_days, status
                          from feature_definition_version
                         where feature_definition_id = ? and version_no = ?
                        """,
                (rs, i) -> new VersionRow(
                        rs.getLong("id"), rs.getLong("feature_definition_id"), rs.getInt("version_no"),
                        rs.getString("formula"), rs.getBoolean("required"),
                        rs.getLong("availability_lag_seconds"), rs.getString("as_of_policy"),
                        rs.getInt("lookback_days"), rs.getString("status")),
                featureDefinitionId, versionNo);
        if (rows.isEmpty()) {
            throw ApiException.notFound("VERSION_NOT_FOUND",
                    "feature version not found: definition " + featureDefinitionId + " v" + versionNo);
        }
        return rows.get(0);
    }

    /** Snapshot of every currently approved definition version, used when a case is opened. */
    public List<ApprovedFeature> approvedFeatures() {
        return db.jdbc().query("""
                        select fd.code, fd.name, fd.unit, fd.source_types,
                               fv.id as version_id, fv.version_no, fv.formula, fv.required,
                               fv.availability_lag_seconds, fv.as_of_policy, fv.lookback_days
                          from feature_definition fd
                          join feature_definition_version fv on fv.id = fd.current_version_id
                         where fd.status = 'APPROVED' and fv.status = 'APPROVED'
                         order by fd.code
                        """,
                (rs, i) -> new ApprovedFeature(
                        rs.getString("code"), rs.getString("name"), rs.getString("unit"),
                        sourceList(db.readTree(rs.getString("source_types"))),
                        rs.getLong("version_id"), rs.getInt("version_no"), rs.getString("formula"),
                        rs.getBoolean("required"), rs.getLong("availability_lag_seconds"),
                        rs.getString("as_of_policy"), rs.getInt("lookback_days")));
    }

    private static String checksum(String formula) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(formula.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> sourceList(JsonNode node) {
        List<String> list = new ArrayList<>();
        node.forEach(n -> list.add(n.asText()));
        return list;
    }

    private static String ts(OffsetDateTime value) {
        return value == null ? null : Times.format(value.toInstant());
    }

    public record FeatureRow(long id, String code, String name, String description, String unit,
                             List<String> sourceTypes, String status, Integer currentVersion) {
    }

    public record VersionRow(long id, long featureDefinitionId, int versionNo, String formula,
                             boolean required, long availabilityLagSeconds, String asOfPolicy,
                             int lookbackDays, String status) {
    }

    public record ApprovedFeature(String code, String name, String unit, List<String> sourceTypes,
                                  long versionId, int versionNo, String formula, boolean required,
                                  long availabilityLagSeconds, String asOfPolicy, int lookbackDays) {
    }
}
