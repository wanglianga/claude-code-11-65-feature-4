package com.agri.pit.backfill;

import com.agri.pit.common.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Experiment snapshot repository endpoints: experiment detail with metrics and the
 * data-availability statement, frozen snapshots, and the experiment comparison report.
 */
@RestController
@RequestMapping("/api/experiments")
@Tag(name = "experiments", description = "Immutable experiment snapshots, metrics over the "
        + "evaluable sample, and experiment-vs-experiment comparison")
public class ExperimentController {

    private final ExperimentSnapshotService snapshots;

    public ExperimentController(ExperimentSnapshotService snapshots) {
        this.snapshots = snapshots;
    }

    @Operation(summary = "List experiments")
    @GetMapping
    public List<BackfillDtos.ExperimentView> list() {
        return snapshots.listExperiments();
    }

    @Operation(summary = "Experiment detail: policy set, metrics (evaluable sample only) and the "
            + "data-availability statement")
    @GetMapping("/{experimentId}")
    public Map<String, Object> detail(@PathVariable long experimentId) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("experiment", snapshots.requireExperimentView(experimentId));
        detail.put("metrics", snapshots.metrics(experimentId));
        detail.put("availability", snapshots.availabilityStatement(experimentId));
        return detail;
    }

    @Operation(summary = "Frozen experiment snapshots: inputs, outputs and missing reasons per case")
    @GetMapping("/{experimentId}/snapshots")
    public List<BackfillDtos.SnapshotView> snapshots(@PathVariable long experimentId,
                                                     @RequestParam(required = false) String caseClass) {
        return snapshots.snapshots(experimentId, caseClass);
    }

    @Operation(summary = "Compare two experiments: sample-scope vs formula/policy attribution")
    @GetMapping("/compare")
    public Map<String, Object> compare(@RequestParam long left, @RequestParam long right) {
        if (left == right) {
            throw ApiException.badRequest("COMPARE_SAME_EXPERIMENT",
                    "left and right must be two different experiments");
        }
        return snapshots.compare(left, right);
    }
}
