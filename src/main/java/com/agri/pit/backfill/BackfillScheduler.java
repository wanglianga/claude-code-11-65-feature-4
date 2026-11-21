package com.agri.pit.backfill;

import com.agri.pit.cases.CaseService;
import com.agri.pit.catalog.CatalogService;
import com.agri.pit.common.ApiException;
import com.agri.pit.common.Db;
import com.agri.pit.common.Times;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Batch backfill scheduler. A job belongs to exactly one experiment and fans out into shards
 * keyed by (definition version, case): when several definition versions of the same feature are
 * backfilled over the same cases, each version gets its own shard and its own snapshot, so
 * concurrent version backfills never share feature values.
 * <p>
 * Case selection follows the availability scan: with the default {@code EVALUABLE_ONLY} scope,
 * cases the scanner classified as look-ahead risk or missing are skipped up front (recorded as
 * {@code SKIPPED_SCOPE} shards with the scan reasons) instead of being force-filled with
 * post-cutoff data.
 */
@Service
public class BackfillScheduler {

    private final Db db;
    private final CatalogService catalog;
    private final DependencyGraphService dependencies;
    private final MissingPolicyService policies;
    private final AvailabilityScanner scanner;
    private final ExperimentExecutor executor;
    private final ExperimentSnapshotService snapshots;
    private final CaseService cases;

    public BackfillScheduler(Db db, CatalogService catalog, DependencyGraphService dependencies,
                             MissingPolicyService policies, AvailabilityScanner scanner,
                             ExperimentExecutor executor, ExperimentSnapshotService snapshots,
                             CaseService cases) {
        this.db = db;
        this.catalog = catalog;
        this.dependencies = dependencies;
        this.policies = policies;
        this.scanner = scanner;
        this.executor = executor;
        this.snapshots = snapshots;
        this.cases = cases;
    }

    @Transactional
    public long createJob(BackfillDtos.CreateJobRequest req) {
        if (req.experimentKey() == null || req.experimentKey().isBlank()) {
            throw ApiException.badRequest("EXPERIMENT_KEY_REQUIRED", "experimentKey is required");
        }
        String experimentKey = req.experimentKey().trim();
        Integer exists = db.jdbc().queryForObject(
                "select count(*) from experiment where experiment_key = ?", Integer.class, experimentKey);
        if (exists != null && exists > 0) {
            // Reported metrics are immutable: a changed policy or formula needs a new key.
            throw ApiException.conflict("EXPERIMENT_EXISTS",
                    "experiment " + experimentKey + " already exists; a policy or definition change "
                            + "must be backfilled under a new experiment key so reported metrics are "
                            + "never overwritten");
        }
        CatalogService.FeatureRow feature = catalog.requireFeature(req.featureCode());
        List<DependencyGraphService.Dependency> deps = dependencies.dependenciesFor(feature.id());
        if (deps.isEmpty()) {
            throw ApiException.unprocessable("NO_DEPENDENCIES",
                    "feature " + feature.code() + " has no registered dependencies");
        }
        List<Long> versionIds = resolveVersions(feature, req.definitionVersionIds());
        String scope = req.caseScope() == null || req.caseScope().isBlank()
                ? "EVALUABLE_ONLY" : req.caseScope().trim().toUpperCase();
        if (!scope.equals("EVALUABLE_ONLY") && !scope.equals("INCLUDE_POST_HOC")) {
            throw ApiException.badRequest("UNKNOWN_CASE_SCOPE",
                    "caseScope must be EVALUABLE_ONLY or INCLUDE_POST_HOC");
        }
        Long scanId = req.scanId() != null ? req.scanId() : scanner.latestScanId(feature.id());
        if (scanId == null) {
            throw ApiException.unprocessable("SCAN_REQUIRED",
                    "no availability scan exists for " + feature.code()
                            + "; run POST /api/backfill/scans first so the scheduler can separate "
                            + "evaluable cases from post-hoc-only cases");
        }
        requireScan(scanId, feature.id());
        Map<String, MissingPolicyService.Policy> policySet =
                policies.resolvePolicySet(req.policyVersions());
        ObjectNode policyJson = db.mapper().createObjectNode();
        policySet.forEach((code, policy) -> {
            ObjectNode node = db.mapper().createObjectNode();
            node.put("policyId", policy.id());
            node.put("versionNo", policy.versionNo());
            node.put("action", policy.action());
            policyJson.set(code, node);
        });

        long experimentId = db.insert("""
                        insert into experiment
                            (experiment_key, feature_code, policy_set, case_scope, scan_id, notes)
                        values (?, ?, ?::jsonb, ?, ?, ?)
                        """,
                experimentKey, feature.code(), db.jsonArg(policyJson), scope, scanId, req.notes());
        long jobId = db.insert("""
                        insert into backfill_job (job_code, experiment_id, requested_by)
                        values (?, ?, ?)
                        """,
                experimentKey + "-job", experimentId,
                req.requestedBy() == null || req.requestedBy().isBlank()
                        ? "backfill-scheduler" : req.requestedBy().trim());

        List<Map<String, Object>> items = db.jdbc().queryForList("""
                        select case_id, case_class, reasons from availability_scan_item
                         where scan_id = ? order by case_id
                        """, scanId);
        if (items.isEmpty()) {
            throw ApiException.unprocessable("EMPTY_SCAN",
                    "availability scan " + scanId + " contains no cases");
        }
        int shardNo = 0;
        int skipped = 0;
        for (Map<String, Object> item : items) {
            long caseId = ((Number) item.get("case_id")).longValue();
            String caseClass = (String) item.get("case_class");
            boolean outOfScope = "POST_HOC_ONLY".equals(caseClass) && "EVALUABLE_ONLY".equals(scope);
            for (Long versionId : versionIds) {
                shardNo++;
                String missingReason = null;
                String status = "QUEUED";
                if (outOfScope) {
                    status = "SKIPPED_SCOPE";
                    skipped++;
                    missingReason = "POST_HOC_ONLY case excluded by EVALUABLE_ONLY scope: "
                            + db.readTree(String.valueOf(item.get("reasons"))).toString();
                }
                db.insert("""
                                insert into backfill_shard
                                    (job_id, shard_no, case_id, definition_version_id, case_class,
                                     status, missing_reason, finished_at)
                                values (?, ?, ?, ?, ?, ?, ?, case when ? then now() else null end)
                                """,
                        jobId, shardNo, caseId, versionId, caseClass, status, missingReason, outOfScope);
            }
        }
        db.jdbc().update("""
                        update backfill_job set total_shards = ?, skipped_shards = ? where id = ?
                        """, shardNo, skipped, jobId);
        return jobId;
    }

    /** Execute every queued shard of a job synchronously, in shard order. */
    @Transactional
    public void executeJob(long jobId) {
        Map<String, Object> job = requireJob(jobId);
        String status = (String) job.get("status");
        if ("COMPLETED".equals(status) || "COMPLETED_WITH_MISSING".equals(status)) {
            return;
        }
        long experimentId = ((Number) job.get("experiment_id")).longValue();
        Map<String, Object> experiment = requireExperiment(experimentId);
        String featureCode = (String) experiment.get("feature_code");
        JsonNode policySetJson = db.readTree(String.valueOf(experiment.get("policy_set")));
        Map<String, MissingPolicyService.Policy> policySet = new LinkedHashMap<>();
        policySetJson.fields().forEachRemaining(entry -> {
            String code = entry.getKey();
            int versionNo = entry.getValue().path("versionNo").asInt();
            policySet.put(code, policies.requirePolicy(code, versionNo));
        });
        CatalogService.FeatureRow feature = catalog.requireFeature(featureCode);
        List<DependencyGraphService.Dependency> deps = dependencies.dependenciesFor(feature.id());

        db.jdbc().update("""
                        update backfill_job set status = 'RUNNING', started_at = coalesce(started_at, now())
                         where id = ?
                        """, jobId);
        List<Map<String, Object>> shards = db.jdbc().queryForList("""
                        select id, case_id, definition_version_id, case_class
                          from backfill_shard where job_id = ? and status = 'QUEUED'
                         order by shard_no
                        """, jobId);
        int failed = 0;
        for (Map<String, Object> shard : shards) {
            long shardId = ((Number) shard.get("id")).longValue();
            try {
                executeShard(shardId, ((Number) shard.get("case_id")).longValue(),
                        ((Number) shard.get("definition_version_id")).longValue(),
                        (String) shard.get("case_class"), feature, deps, policySet, experimentId);
            } catch (Exception e) {
                failed++;
                db.jdbc().update("""
                                update backfill_shard
                                   set status = 'FAILED', error_message = ?, finished_at = now()
                                 where id = ?
                                """, e.getMessage(), shardId);
            }
        }
        db.jdbc().update("""
                        update backfill_job
                           set status = case
                                   when (select count(*) from backfill_shard
                                          where job_id = ? and status = 'FAILED') > 0 then 'FAILED'
                                   when (select count(*) from backfill_shard
                                          where job_id = ? and status = 'MISSING') > 0
                                       then 'COMPLETED_WITH_MISSING'
                                   else 'COMPLETED' end,
                               computed_shards = (select count(*) from backfill_shard
                                                   where job_id = ? and status = 'COMPUTED'),
                               missing_shards = (select count(*) from backfill_shard
                                                  where job_id = ? and status = 'MISSING'),
                               failed_shards = (select count(*) from backfill_shard
                                                 where job_id = ? and status = 'FAILED'),
                               finished_at = now()
                         where id = ?
                        """,
                jobId, jobId, jobId, jobId, jobId, jobId);
    }

    private void executeShard(long shardId, long caseId, long definitionVersionId, String caseClass,
                              CatalogService.FeatureRow feature,
                              List<DependencyGraphService.Dependency> deps,
                              Map<String, MissingPolicyService.Policy> policySet, long experimentId) {
        db.jdbc().update("""
                        update backfill_shard set status = 'RUNNING', started_at = now() where id = ?
                        """, shardId);
        Map<String, Object> versionRow = db.jdbc().queryForMap("""
                        select version_no, formula from feature_definition_version where id = ?
                        """, definitionVersionId);
        int versionNo = ((Number) versionRow.get("version_no")).intValue();
        String formula = (String) versionRow.get("formula");
        String caseNo = db.jdbc().queryForObject(
                "select case_no from claim_case where id = ?", String.class, caseId);
        CaseService.CaseRow caseRow = cases.requireCase(caseNo);
        ExperimentExecutor.ExperimentResult result = executor.compute(caseRow, feature.code(),
                feature.unit(), definitionVersionId, versionNo, formula, deps,
                policySet, caseClass);
        long snapshotId = snapshots.persist(experimentId, shardId, caseRow, feature.code(),
                definitionVersionId, versionNo, caseClass, result);
        String shardStatus = "COMPUTED".equals(result.valueStatus()) ? "COMPUTED" : "MISSING";
        db.jdbc().update("""
                        update backfill_shard
                           set status = ?, missing_reason = ?, experiment_snapshot_id = ?,
                               finished_at = now()
                         where id = ?
                        """,
                shardStatus,
                result.missingReasons().isEmpty() ? null : String.join(" | ", result.missingReasons()),
                snapshotId, shardId);
    }

    // ------------------------------------------------------------------ queries

    public BackfillDtos.JobDetail getJob(long jobId) {
        Map<String, Object> job = requireJob(jobId);
        Map<String, Object> experiment = requireExperiment(
                ((Number) job.get("experiment_id")).longValue());
        List<BackfillDtos.ShardView> shards = db.jdbc().query("""
                        select s.id, s.shard_no, s.case_id, c.case_no, s.definition_version_id,
                               fv.version_no, s.case_class, s.status, s.missing_reason,
                               s.experiment_snapshot_id, s.error_message, s.finished_at
                          from backfill_shard s
                          join claim_case c on c.id = s.case_id
                          join feature_definition_version fv on fv.id = s.definition_version_id
                         where s.job_id = ?
                         order by s.shard_no
                        """,
                (rs, i) -> new BackfillDtos.ShardView(rs.getLong("id"), rs.getInt("shard_no"),
                        rs.getLong("case_id"), rs.getString("case_no"),
                        rs.getLong("definition_version_id"), rs.getInt("version_no"),
                        rs.getString("case_class"), rs.getString("status"),
                        rs.getString("missing_reason"),
                        (Long) rs.getObject("experiment_snapshot_id"),
                        rs.getString("error_message"), Times.format(Db.instant(rs, "finished_at"))),
                jobId);
        return new BackfillDtos.JobDetail(toJobView(job, experiment),
                db.readTree(String.valueOf(experiment.get("policy_set"))), shards);
    }

    public List<BackfillDtos.JobView> listJobs() {
        List<Map<String, Object>> jobs = db.jdbc().queryForList("""
                        select j.*, e.experiment_key, e.feature_code from backfill_job j
                         join experiment e on e.id = j.experiment_id
                         order by j.id desc
                        """);
        List<BackfillDtos.JobView> result = new ArrayList<>();
        for (Map<String, Object> job : jobs) {
            result.add(toJobView(job, job));
        }
        return result;
    }

    private BackfillDtos.JobView toJobView(Map<String, Object> job, Map<String, Object> experiment) {
        return new BackfillDtos.JobView(
                ((Number) job.get("id")).longValue(),
                (String) job.get("job_code"),
                ((Number) job.get("experiment_id")).longValue(),
                (String) experiment.get("experiment_key"),
                (String) job.get("status"),
                ((Number) job.get("total_shards")).intValue(),
                ((Number) job.get("computed_shards")).intValue(),
                ((Number) job.get("missing_shards")).intValue(),
                ((Number) job.get("skipped_shards")).intValue(),
                ((Number) job.get("failed_shards")).intValue(),
                (String) job.get("requested_by"),
                String.valueOf(job.get("created_at")),
                job.get("started_at") == null ? null : String.valueOf(job.get("started_at")),
                job.get("finished_at") == null ? null : String.valueOf(job.get("finished_at")));
    }

    private Map<String, Object> requireJob(long jobId) {
        List<Map<String, Object>> rows = db.jdbc().queryForList("""
                        select j.*, e.experiment_key from backfill_job j
                         join experiment e on e.id = j.experiment_id where j.id = ?
                        """, jobId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("JOB_NOT_FOUND", "backfill job not found: " + jobId);
        }
        return rows.get(0);
    }

    private Map<String, Object> requireExperiment(long experimentId) {
        List<Map<String, Object>> rows = db.jdbc().queryForList(
                "select * from experiment where id = ?", experimentId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("EXPERIMENT_NOT_FOUND", "experiment not found: " + experimentId);
        }
        return rows.get(0);
    }

    private void requireScan(long scanId, long featureDefinitionId) {
        List<Map<String, Object>> rows = db.jdbc().queryForList("""
                        select feature_definition_id, status from availability_scan where id = ?
                        """, scanId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("SCAN_NOT_FOUND", "availability scan not found: " + scanId);
        }
        long scanFeature = ((Number) rows.get(0).get("feature_definition_id")).longValue();
        if (scanFeature != featureDefinitionId) {
            throw ApiException.badRequest("SCAN_FEATURE_MISMATCH",
                    "scan " + scanId + " belongs to a different feature definition");
        }
    }

    private List<Long> resolveVersions(CatalogService.FeatureRow feature, List<Long> requested) {
        if (requested != null && !requested.isEmpty()) {
            for (Long versionId : requested) {
                Integer count = db.jdbc().queryForObject("""
                                select count(*) from feature_definition_version
                                 where id = ? and feature_definition_id = ? and status = 'APPROVED'
                                """, Integer.class, versionId, feature.id());
                if (count == null || count == 0) {
                    throw ApiException.badRequest("VERSION_NOT_APPROVED",
                            "definition version " + versionId + " is not an approved version of "
                                    + feature.code());
                }
            }
            return requested;
        }
        List<Long> approved = db.jdbc().query("""
                        select id from feature_definition_version
                         where feature_definition_id = ? and status = 'APPROVED'
                         order by version_no
                        """, (rs, i) -> rs.getLong(1), feature.id());
        if (approved.isEmpty()) {
            throw ApiException.unprocessable("NO_APPROVED_VERSIONS",
                    "feature " + feature.code() + " has no approved definition versions to backfill");
        }
        return approved;
    }
}
