package com.agri.pit.backfill;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

public final class BackfillDtos {

    private BackfillDtos() {
    }

    // ------------------------------------------------------------ dependencies

    public record RegisterDependencyRequest(
            String featureCode,
            String depKey,
            String sourceType,
            Boolean required,
            JsonNode params,
            String notes) {
    }

    public record DependencyView(
            long id,
            String featureCode,
            String depKey,
            String sourceType,
            boolean required,
            JsonNode params,
            String notes,
            String createdAt) {
    }

    // ---------------------------------------------------------------- policies

    public record CreatePolicyRequest(
            String policyCode,
            String action,
            JsonNode params,
            String notes) {
    }

    public record PolicyView(
            long id,
            String policyCode,
            int versionNo,
            String action,
            JsonNode params,
            String status,
            String notes,
            String createdAt) {
    }

    // ------------------------------------------------------------------- scans

    public record CreateScanRequest(
            String featureCode,
            String hazardFrom,
            String hazardTo,
            String createdBy) {
    }

    public record ScanSummary(
            long scanId,
            String featureCode,
            Integer definitionVersionNo,
            String hazardFrom,
            String hazardTo,
            String status,
            int caseCount,
            int evaluableCount,
            int postHocCount,
            JsonNode featureStatusCounts,
            String createdBy,
            String createdAt) {
    }

    public record ScanItemView(
            long caseId,
            String caseNo,
            String hazardDate,
            String scoringCutoff,
            String featureStatus,
            String caseClass,
            JsonNode dependencies,
            List<String> reasons) {
    }

    public record ScanDetail(ScanSummary scan, List<ScanItemView> items) {
    }

    // -------------------------------------------------------------------- jobs

    public record CreateJobRequest(
            String experimentKey,
            String featureCode,
            List<Long> definitionVersionIds,
            Map<String, Integer> policyVersions,
            String caseScope,
            Long scanId,
            String requestedBy,
            String notes) {
    }

    public record JobView(
            long jobId,
            String jobCode,
            long experimentId,
            String experimentKey,
            String status,
            int totalShards,
            int computedShards,
            int missingShards,
            int skippedShards,
            int failedShards,
            String requestedBy,
            String createdAt,
            String startedAt,
            String finishedAt) {
    }

    public record ShardView(
            long shardId,
            int shardNo,
            long caseId,
            String caseNo,
            long definitionVersionId,
            int definitionVersionNo,
            String caseClass,
            String status,
            String missingReason,
            Long experimentSnapshotId,
            String errorMessage,
            String finishedAt) {
    }

    public record JobDetail(JobView job, JsonNode policySet, List<ShardView> shards) {
    }

    // ------------------------------------------------------------- experiments

    public record ExperimentView(
            long id,
            String experimentKey,
            String featureCode,
            JsonNode policySet,
            String caseScope,
            Long scanId,
            String status,
            String notes,
            String createdAt) {
    }

    public record SnapshotView(
            long id,
            long experimentId,
            long shardId,
            long caseId,
            String caseNo,
            String featureCode,
            long definitionVersionId,
            int definitionVersionNo,
            String caseClass,
            String cutoffAt,
            String availabilityCeiling,
            Double value,
            String valueStatus,
            List<String> missingReasons,
            JsonNode policyApplications,
            JsonNode inputs,
            JsonNode outputs,
            String inputsHash,
            String createdAt) {
    }
}
