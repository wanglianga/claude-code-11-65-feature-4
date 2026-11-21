package com.agri.pit.backfill;

import com.agri.pit.common.ApiException;
import com.agri.pit.common.Db;
import com.agri.pit.common.Times;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Missing-data policy library. Each policy code (cloud occlusion, late declaration, missing
 * weather) owns a versioned list of strategies. Experiments freeze the exact versions they
 * used; changing a strategy means publishing a new version and starting a new experiment,
 * never rewriting an old one.
 */
@Service
public class MissingPolicyService {

    public static final Set<String> POLICY_CODES =
            Set.of("CLOUD_OCCLUSION", "LATE_DECLARATION", "MISSING_WEATHER");

    public static final Set<String> ACTIONS =
            Set.of("INTERPOLATE", "EXCLUDE_MISSING", "FLAG_LOOKAHEAD_RISK");

    public record Policy(long id, String policyCode, int versionNo, String action, JsonNode params,
                         String status, String notes) {
    }

    private final Db db;

    public MissingPolicyService(Db db) {
        this.db = db;
    }

    @Transactional
    public long create(BackfillDtos.CreatePolicyRequest req) {
        if (req.policyCode() == null || req.action() == null) {
            throw ApiException.badRequest("POLICY_FIELDS_REQUIRED", "policyCode and action are required");
        }
        String code = req.policyCode().trim().toUpperCase();
        if (!POLICY_CODES.contains(code)) {
            throw ApiException.badRequest("UNKNOWN_POLICY_CODE",
                    "policyCode must be one of " + POLICY_CODES + "; got " + code);
        }
        String action = req.action().trim().toUpperCase();
        if (!ACTIONS.contains(action)) {
            throw ApiException.badRequest("UNKNOWN_POLICY_ACTION",
                    "action must be one of " + ACTIONS + "; got " + action);
        }
        if (code.equals("LATE_DECLARATION") && !action.equals("FLAG_LOOKAHEAD_RISK")) {
            throw ApiException.badRequest("LATE_DECLARATION_MUST_FLAG",
                    "a declaration ingested after the case cutoff is look-ahead risk; the only allowed "
                            + "action for LATE_DECLARATION is FLAG_LOOKAHEAD_RISK");
        }
        int versionNo = db.jdbc().queryForObject(
                "select coalesce(max(version_no), 0) + 1 from missing_policy where policy_code = ?",
                Integer.class, code);
        return db.insert("""
                        insert into missing_policy (policy_code, version_no, action, params, notes)
                        values (?, ?, ?, ?::jsonb, ?)
                        """,
                code, versionNo, action,
                db.jsonArg(req.params() == null ? db.mapper().createObjectNode() : req.params()),
                req.notes());
    }

    public List<BackfillDtos.PolicyView> list(String policyCode) {
        String sql = """
                select id, policy_code, version_no, action, params, status, notes, created_at
                  from missing_policy
                """;
        if (policyCode == null || policyCode.isBlank()) {
            return db.jdbc().query(sql + " order by policy_code, version_no", (rs, i) -> map(rs));
        }
        return db.jdbc().query(sql + " where policy_code = ? order by version_no",
                (rs, i) -> map(rs), policyCode.trim().toUpperCase());
    }

    public Policy requirePolicy(String policyCode, Integer versionNo) {
        String code = policyCode.trim().toUpperCase();
        List<Policy> rows;
        if (versionNo == null) {
            rows = db.jdbc().query("""
                            select id, policy_code, version_no, action, params, status, notes
                              from missing_policy
                             where policy_code = ? and status = 'ACTIVE'
                             order by version_no desc limit 1
                            """,
                    (rs, i) -> mapPolicy(rs), code);
        } else {
            rows = db.jdbc().query("""
                            select id, policy_code, version_no, action, params, status, notes
                              from missing_policy
                             where policy_code = ? and version_no = ?
                            """,
                    (rs, i) -> mapPolicy(rs), code, versionNo);
        }
        if (rows.isEmpty()) {
            throw ApiException.notFound("POLICY_NOT_FOUND",
                    "no policy " + code + (versionNo == null ? " (any active version)" : " v" + versionNo));
        }
        return rows.get(0);
    }

    /**
     * Freeze the policy set for an experiment: every known policy code resolved to a concrete
     * version, either from the caller's explicit choices or the latest active version.
     */
    public Map<String, Policy> resolvePolicySet(Map<String, Integer> requested) {
        Map<String, Policy> resolved = new LinkedHashMap<>();
        for (String code : POLICY_CODES) {
            Integer version = requested == null ? null : requested.get(code);
            resolved.put(code, requirePolicy(code, version));
        }
        return resolved;
    }

    private Policy mapPolicy(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Policy(rs.getLong("id"), rs.getString("policy_code"), rs.getInt("version_no"),
                rs.getString("action"), db.readTree(rs.getString("params")), rs.getString("status"),
                rs.getString("notes"));
    }

    private BackfillDtos.PolicyView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new BackfillDtos.PolicyView(rs.getLong("id"), rs.getString("policy_code"),
                rs.getInt("version_no"), rs.getString("action"), db.readTree(rs.getString("params")),
                rs.getString("status"), rs.getString("notes"),
                Times.format(Db.instant(rs, "created_at")));
    }
}
