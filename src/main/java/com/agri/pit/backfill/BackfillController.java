package com.agri.pit.backfill;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Backfill lab endpoints: the feature dependency graph, the missing-data policy library, the
 * historical availability scanner and the sharded backfill scheduler.
 */
@RestController
@RequestMapping("/api/backfill")
@Tag(name = "backfill-lab", description = "Feature dependency graph, availability scanner, "
        + "missing-data policies and the sharded experiment backfill scheduler")
public class BackfillController {

    private final DependencyGraphService dependencies;
    private final MissingPolicyService policies;
    private final AvailabilityScanner scanner;
    private final BackfillScheduler scheduler;

    public BackfillController(DependencyGraphService dependencies, MissingPolicyService policies,
                              AvailabilityScanner scanner, BackfillScheduler scheduler) {
        this.dependencies = dependencies;
        this.policies = policies;
        this.scanner = scanner;
        this.scheduler = scheduler;
    }

    // ------------------------------------------------------- dependency graph

    @Operation(summary = "Query the feature dependency graph (optionally per feature)")
    @GetMapping("/dependencies")
    public List<BackfillDtos.DependencyView> listDependencies(
            @RequestParam(required = false) String featureCode) {
        return dependencies.list(featureCode);
    }

    @Operation(summary = "Register a feature dependency (sowing date, temperature series, imagery sequence)")
    @PostMapping("/dependencies")
    public Map<String, Object> registerDependency(@RequestBody BackfillDtos.RegisterDependencyRequest req) {
        long id = dependencies.register(req);
        return Map.of("id", id, "registered", true);
    }

    // ------------------------------------------------------------ policy library

    @Operation(summary = "List missing-data policies (cloud occlusion, late declaration, missing weather)")
    @GetMapping("/policies")
    public List<BackfillDtos.PolicyView> listPolicies(@RequestParam(required = false) String policyCode) {
        return policies.list(policyCode);
    }

    @Operation(summary = "Publish a new missing-data policy version")
    @PostMapping("/policies")
    public Map<String, Object> createPolicy(@RequestBody BackfillDtos.CreatePolicyRequest req) {
        long id = policies.create(req);
        return Map.of("id", id, "created", true);
    }

    // --------------------------------------------------------- availability scan

    @Operation(summary = "Scan historical cases for point-in-time input availability of a feature")
    @PostMapping("/scans")
    public Map<String, Object> scan(@RequestBody BackfillDtos.CreateScanRequest req) {
        java.time.LocalDate from = req.hazardFrom() == null || req.hazardFrom().isBlank()
                ? null : java.time.LocalDate.parse(req.hazardFrom());
        java.time.LocalDate to = req.hazardTo() == null || req.hazardTo().isBlank()
                ? null : java.time.LocalDate.parse(req.hazardTo());
        long scanId = scanner.scan(req.featureCode(), from, to, req.createdBy());
        return Map.of("scanId", scanId);
    }

    @Operation(summary = "Availability scan result: per-case evaluable vs post-hoc-only classification")
    @GetMapping("/scans/{scanId}")
    public BackfillDtos.ScanDetail getScan(@PathVariable long scanId) {
        return scanner.getScan(scanId);
    }

    // ------------------------------------------------------------------ scheduler

    @Operation(summary = "Create an experiment and a sharded backfill job (shards per definition version × case)")
    @PostMapping("/jobs")
    public Map<String, Object> createJob(@RequestBody BackfillDtos.CreateJobRequest req) {
        long jobId = scheduler.createJob(req);
        return Map.of("jobId", jobId, "status", "QUEUED");
    }

    @Operation(summary = "Backfill job status: shard counts and per-case outcomes")
    @GetMapping("/jobs/{jobId}")
    public BackfillDtos.JobDetail getJob(@PathVariable long jobId) {
        return scheduler.getJob(jobId);
    }

    @Operation(summary = "List backfill jobs")
    @GetMapping("/jobs")
    public List<BackfillDtos.JobView> listJobs() {
        return scheduler.listJobs();
    }

    @Operation(summary = "Execute all queued shards of a backfill job synchronously")
    @PostMapping("/jobs/{jobId}/execute")
    public BackfillDtos.JobDetail executeJob(@PathVariable long jobId) {
        scheduler.executeJob(jobId);
        return scheduler.getJob(jobId);
    }
}
