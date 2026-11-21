#!/usr/bin/env bash
# End-to-end verification of the backfill lab:
#   R2  a planting declaration ingested after the case cutoff is LOOKAHEAD_RISK, never adopted
#   R3  a cloud-policy change (INTERPOLATE -> EXCLUDE_MISSING) requires a new experiment and
#       never overwrites already reported metrics
#   R4  two definition versions backfilling the same case are stored as separate snapshots
# Also covers the three query interfaces: dependency graph, backfill status, experiment compare.
# Requires a running instance (default http://localhost:8080) seeded with demo data.
# Safe to run twice: existing experiments are reused (they are immutable by design).
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
JQ="${JQ:-jq}"
fail=0

need() { command -v "$1" >/dev/null || { echo "missing dependency: $1"; exit 2; }; }
need curl
need "$JQ"

say() { printf '\n=== %s\n' "$*"; }
ok()  { printf '  PASS  %s\n' "$*"; }
bad() { printf '  FAIL  %s\n' "$*"; fail=1; }
expect_eq() { # actual expected label
  if [ "$1" = "$2" ]; then ok "$3 ($1)"; else bad "$3: expected [$2] got [$1]"; fi
}
expect_ge() { # actual min label
  if [ "$1" -ge "$2" ] 2>/dev/null; then ok "$3 ($1)"; else bad "$3: expected >= $2 got [$1]"; fi
}

json() { curl -fsS "$@"; }

# ensure_experiment KEY PAYLOAD -> "experimentId jobId"; creates or reuses the experiment.
ensure_experiment() {
  local key="$1" payload="$2" out exp job
  out=$(curl -s -o /tmp/exp_create.json -w '%{http_code}' -XPOST "$BASE/api/backfill/jobs" \
        -H 'Content-Type: application/json' -d "$payload")
  if [ "$out" = "200" ]; then
    job=$("$JQ" -r '.jobId' /tmp/exp_create.json)
  else
    local code
    code=$("$JQ" -r '.title // empty' /tmp/exp_create.json)
    [ "$code" = "EXPERIMENT_EXISTS" ] || { echo "unexpected create failure: $(cat /tmp/exp_create.json)"; exit 1; }
    job=$(json "$BASE/api/backfill/jobs" | "$JQ" -r "[.[]|select(.experimentKey==\"$key\")][0].jobId")
  fi
  exp=$(json "$BASE/api/experiments" | "$JQ" -r "[.[]|select(.experimentKey==\"$key\")][0].id")
  echo "$exp $job"
}

say "waiting for service"
for i in $(seq 1 60); do
  if curl -fsS "$BASE/actuator/health" >/dev/null 2>&1; then break; fi
  sleep 1
done
curl -fsS "$BASE/actuator/health" >/dev/null || { echo "service is not reachable at $BASE"; exit 1; }

# ---------------------------------------------------------------------------
say "dependency query interface: feature dependency graph"
GDD_DEPS=$(json "$BASE/api/backfill/dependencies?featureCode=GDD_AFTER_SOWING")
D1=$(echo "$GDD_DEPS" | "$JQ" -r '[.[].depKey]|sort|join(",")')
expect_eq "$D1" "SOWING_DATE,TEMPERATURE_SERIES" "GDD dependencies registered"
D2=$(echo "$GDD_DEPS" | "$JQ" -r '[.[]|select(.depKey=="TEMPERATURE_SERIES")][0].params.baseTempC')
expect_eq "$D2" "10" "GDD base temperature param"
SLOPE_DEPS=$(json "$BASE/api/backfill/dependencies?featureCode=NDVI_RECOVERY_SLOPE")
D3=$(echo "$SLOPE_DEPS" | "$JQ" -r '[.[].depKey]|join(",")')
expect_eq "$D3" "IMAGERY_SEQUENCE" "slope dependency registered"
expect_eq "$(echo "$SLOPE_DEPS" | "$JQ" -r '.[0].params.minScenes')" "2" "slope minScenes param"

say "missing-data policy library"
POL=$(json "$BASE/api/backfill/policies")
P1=$(echo "$POL" | "$JQ" -r '[.[]|select(.policyCode=="CLOUD_OCCLUSION")]|map(.action)|join(",")')
expect_eq "$P1" "INTERPOLATE,EXCLUDE_MISSING" "cloud policy v1 interpolate, v2 exclude"
P2=$(echo "$POL" | "$JQ" -r '[.[]|select(.policyCode=="LATE_DECLARATION")][0].action')
expect_eq "$P2" "FLAG_LOOKAHEAD_RISK" "late declaration policy flags look-ahead risk"
P3=$(echo "$POL" | "$JQ" -r '[.[]|select(.policyCode=="MISSING_WEATHER")][0].action')
expect_eq "$P3" "EXCLUDE_MISSING" "missing weather policy"

# ---------------------------------------------------------------------------
say "availability scanner: evaluable vs post-hoc-only (R2)"
SCAN_GDD=$(json -XPOST "$BASE/api/backfill/scans" -H 'Content-Type: application/json' \
  -d '{"featureCode":"GDD_AFTER_SOWING","createdBy":"verify-script"}' | "$JQ" -r '.scanId')
GDD_SCAN=$(json "$BASE/api/backfill/scans/$SCAN_GDD")
expect_eq "$(echo "$GDD_SCAN" | "$JQ" -r '.scan.evaluableCount')" "5" "GDD evaluable cases"
expect_eq "$(echo "$GDD_SCAN" | "$JQ" -r '.scan.postHocCount')" "2" "GDD post-hoc-only cases"
LATE=$(echo "$GDD_SCAN" | "$JQ" -c '[.items[]|select(.caseNo=="HIST-2025-0001")][0]')
expect_eq "$(echo "$LATE" | "$JQ" -r '.featureStatus')" "LOOKAHEAD_RISK" "late declaration flagged as look-ahead risk"
expect_eq "$(echo "$LATE" | "$JQ" -r '.caseClass')" "POST_HOC_ONLY" "late declaration case is post-hoc only"
echo "$LATE" | "$JQ" -e '.reasons[0] | test("ingested") and test("after the case cutoff")' >/dev/null \
  && ok "look-ahead reason cites the late ingest" || bad "look-ahead reason missing detail"
NOTEMP=$(echo "$GDD_SCAN" | "$JQ" -c '[.items[]|select(.caseNo=="HIST-2026-0001")][0]')
expect_eq "$(echo "$NOTEMP" | "$JQ" -r '.featureStatus')" "MISSING" "missing temperature flagged"
expect_eq "$(echo "$NOTEMP" | "$JQ" -r '.caseClass')" "POST_HOC_ONLY" "missing-weather case is post-hoc only"
C1=$(echo "$GDD_SCAN" | "$JQ" -r '[.items[]|select(.caseNo=="HIST-2024-0003")][0].caseClass')
expect_eq "$C1" "EVALUABLE" "clean case evaluable"

SCAN_SLOPE=$(json -XPOST "$BASE/api/backfill/scans" -H 'Content-Type: application/json' \
  -d '{"featureCode":"NDVI_RECOVERY_SLOPE","createdBy":"verify-script"}' | "$JQ" -r '.scanId')
SLOPE_SCAN=$(json "$BASE/api/backfill/scans/$SCAN_SLOPE")
C2=$(echo "$SLOPE_SCAN" | "$JQ" -r '[.items[]|select(.caseNo=="DROUGHT-2024-0002")][0].caseClass')
expect_eq "$C2" "POST_HOC_ONLY" "no-imagery case is post-hoc only"
C3=$(echo "$SLOPE_SCAN" | "$JQ" -r '[.items[]|select(.caseNo=="HIST-2025-0003")][0].featureStatus')
expect_eq "$C3" "DEGRADED" "cloudy case degraded but evaluable"

# ---------------------------------------------------------------------------
say "scheduler: EXP-SLOPE-INTERP (cloud policy v1)"
read EXP1 JOB1 <<< "$(ensure_experiment EXP-SLOPE-INTERP \
  '{"experimentKey":"EXP-SLOPE-INTERP","featureCode":"NDVI_RECOVERY_SLOPE","policyVersions":{"CLOUD_OCCLUSION":1},"requestedBy":"verify-script"}')"
json -XPOST "$BASE/api/backfill/jobs/$JOB1/execute" >/dev/null
J1=$(json "$BASE/api/backfill/jobs/$JOB1")
expect_eq "$(echo "$J1" | "$JQ" -r '.job.computedShards')" "5" "interpolate experiment computed shards"
expect_eq "$(echo "$J1" | "$JQ" -r '.job.missingShards')" "1" "interpolate experiment missing shards"
expect_eq "$(echo "$J1" | "$JQ" -r '.job.skippedShards')" "1" "post-hoc case skipped, not force-filled"
C4=$(echo "$J1" | "$JQ" -r '[.shards[]|select(.caseNo=="DROUGHT-2024-0002")][0].status')
expect_eq "$C4" "SKIPPED_SCOPE" "post-hoc shard skipped by scope"

M1_BEFORE=$(json "$BASE/api/experiments/$EXP1" | "$JQ" -c '{computed: .metrics.evaluableComputed, mean: .metrics.overall.mean, min: .metrics.overall.min, max: .metrics.overall.max}')
SNAP1_BEFORE=$(json "$BASE/api/experiments/$EXP1/snapshots" | "$JQ" 'length')
echo "  EXP-SLOPE-INTERP metrics before policy change: $M1_BEFORE ($SNAP1_BEFORE snapshots)"

say "R3: a policy change must not overwrite reported metrics"
DUP=$(curl -s -o /tmp/dup.json -w '%{http_code}' -XPOST "$BASE/api/backfill/jobs" \
  -H 'Content-Type: application/json' \
  -d '{"experimentKey":"EXP-SLOPE-INTERP","featureCode":"NDVI_RECOVERY_SLOPE","policyVersions":{"CLOUD_OCCLUSION":2}}')
expect_eq "$DUP" "409" "reusing the experiment key is rejected"
expect_eq "$("$JQ" -r '.title' /tmp/dup.json)" "EXPERIMENT_EXISTS" "conflict code EXPERIMENT_EXISTS"

read EXP2 JOB2 <<< "$(ensure_experiment EXP-SLOPE-EXCLUDE \
  '{"experimentKey":"EXP-SLOPE-EXCLUDE","featureCode":"NDVI_RECOVERY_SLOPE","policyVersions":{"CLOUD_OCCLUSION":2},"requestedBy":"verify-script"}')"
json -XPOST "$BASE/api/backfill/jobs/$JOB2/execute" >/dev/null
J2=$(json "$BASE/api/backfill/jobs/$JOB2")
expect_eq "$(echo "$J2" | "$JQ" -r '.job.computedShards')" "4" "exclude experiment computed shards"
expect_eq "$(echo "$J2" | "$JQ" -r '.job.missingShards')" "2" "exclude experiment missing shards"
TRAIL=$(json "$BASE/api/experiments/$EXP2/snapshots" | "$JQ" -c '[.[]|select(.caseNo=="HIST-2025-0003")][0]')
expect_eq "$(echo "$TRAIL" | "$JQ" -r '.valueStatus')" "MISSING_EXCLUDED" "thin sequence excluded by policy"
LEAD=$(json "$BASE/api/experiments/$EXP2/snapshots" | "$JQ" -c '[.[]|select(.caseNo=="HIST-2025-0002")][0]')
expect_eq "$(echo "$LEAD" | "$JQ" -r '.value')" "0.006" "excluded leading cloud changes the slope"

M1_AFTER=$(json "$BASE/api/experiments/$EXP1" | "$JQ" -c '{computed: .metrics.evaluableComputed, mean: .metrics.overall.mean, min: .metrics.overall.min, max: .metrics.overall.max}')
SNAP1_AFTER=$(json "$BASE/api/experiments/$EXP1/snapshots" | "$JQ" 'length')
expect_eq "$M1_AFTER" "$M1_BEFORE" "reported INTERP metrics unchanged after the new experiment"
expect_eq "$SNAP1_AFTER" "$SNAP1_BEFORE" "INTERP snapshot count unchanged"

say "experiment comparison interface (INTERP vs EXCLUDE)"
CMP=$(json "$BASE/api/experiments/compare?left=$EXP1&right=$EXP2")
expect_eq "$(echo "$CMP" | "$JQ" -r '.attribution')" "SAMPLE_SCOPE_AND_FORMULA_OR_POLICY" "comparison attribution"
echo "$CMP" | "$JQ" -e '.sampleScope.onlyLeftComputed | index("HIST-2025-0003 (v1)")' >/dev/null \
  && ok "HIST-2025-0003 computed only under interpolation" || bad "sample-scope diff missing HIST-2025-0003"
C5=$(echo "$CMP" | "$JQ" -r '[.valueDiffs[]|select(.caseNo=="HIST-2025-0002")]|length')
expect_eq "$C5" "1" "value diff on the shared cloudy case"
expect_eq "$(echo "$CMP" | "$JQ" -r '.sampleScope.bothComputed')" "4" "cases computed in both experiments"

# ---------------------------------------------------------------------------
say "R4: two definition versions backfilling the same case"
read EXP3 JOB3 <<< "$(ensure_experiment EXP-GDD-V1V2 \
  '{"experimentKey":"EXP-GDD-V1V2","featureCode":"GDD_AFTER_SOWING","requestedBy":"verify-script"}')"
json -XPOST "$BASE/api/backfill/jobs/$JOB3/execute" >/dev/null
J3=$(json "$BASE/api/backfill/jobs/$JOB3")
expect_eq "$(echo "$J3" | "$JQ" -r '.job.totalShards')" "14" "shards = 7 cases x 2 definition versions"
expect_eq "$(echo "$J3" | "$JQ" -r '.job.computedShards')" "10" "5 evaluable cases x 2 versions computed"
expect_eq "$(echo "$J3" | "$JQ" -r '.job.skippedShards')" "4" "2 post-hoc cases x 2 versions skipped"
PAIR=$(json "$BASE/api/experiments/$EXP3/snapshots" | "$JQ" -c '[.[]|select(.caseNo=="HIST-2024-0003")]')
expect_eq "$(echo "$PAIR" | "$JQ" 'length')" "2" "same case stored once per definition version"
PV=$(echo "$PAIR" | "$JQ" -r '[.[].definitionVersionNo]|sort|join(",")')
expect_eq "$PV" "1,2" "versions v1 and v2 stored separately"
V1=$(echo "$PAIR" | "$JQ" -r '[.[]|select(.definitionVersionNo==1)][0].value')
V2=$(echo "$PAIR" | "$JQ" -r '[.[]|select(.definitionVersionNo==2)][0].value')
RATIO=$(echo "$PAIR" | "$JQ" -r '([.[]|select(.definitionVersionNo==2)][0].value) / ([.[]|select(.definitionVersionNo==1)][0].value) * 100 | round / 100')
expect_eq "$RATIO" "0.85" "v2 = v1 x 0.85 (no cross-version contamination)"
F1=$(echo "$PAIR" | "$JQ" -r '[.[]|select(.definitionVersionNo==1)][0].outputs.formula')
expect_eq "$F1" "gdd_effective" "v1 snapshot carries its own formula"
LATE_SNAPS=$(json "$BASE/api/experiments/$EXP3/snapshots" | "$JQ" '[.[]|select(.caseNo=="HIST-2025-0001" or .caseNo=="HIST-2026-0001")]|length')
expect_eq "$LATE_SNAPS" "0" "post-hoc cases have no experiment snapshots (not force-filled)"

say "backfill status interface"
expect_eq "$(echo "$J3" | "$JQ" -r '.job.status')" "COMPLETED" "job status queryable"
expect_eq "$(json "$BASE/api/experiments/$EXP3" | "$JQ" -r '.availability.evaluableCases')" "5" "availability statement: evaluable cases"
expect_eq "$(json "$BASE/api/experiments/$EXP3" | "$JQ" -r '.availability.postHocOnlyCases')" "2" "availability statement: post-hoc cases"
expect_eq "$(json "$BASE/api/experiments/$EXP3" | "$JQ" -r '.metrics.evaluableComputed')" "10" "metrics computed over evaluable snapshots only"

printf '\n'
if [ "$fail" -eq 0 ]; then
  echo "ALL BACKFILL CHECKS PASSED"
  exit 0
else
  echo "SOME BACKFILL CHECKS FAILED"
  exit 1
fi
