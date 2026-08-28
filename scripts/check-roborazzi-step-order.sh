#!/usr/bin/env bash
# Pins the ORDER of the Roborazzi steps in android-ci.yml (issue #628).
#
# Where the screenshot-verify step sits is load-bearing, and nothing fails if it
# is moved. Two invariants, both established by measurement in #629:
#
#   1. It must run AFTER "Upload test reports". `verifyRoborazziDebug` re-runs
#      :app's whole unit suite and OVERWRITES app/build/reports/tests/. Moved
#      above that upload, it would replace a failing run's report with its own
#      and the artifact would omit the reports for the tests that failed --
#      #626's exact live failure mode.
#
#   2. It must run AFTER "Coverage report (Kover)". Verify mode is a Gradle task
#      INPUT: a plain `:app:testDebugUnitTest -Pgoogle` re-EXECUTES after a bare
#      verify run instead of reporting UP-TO-DATE (measured: 1s UP-TO-DATE
#      before, 56s executed after). Above Kover, it would falsify that step's
#      documented reuse and add a full suite re-run inside its 10-minute cap.
#
# A comment saying so is documentation, not a gate; this is the gate. Named
# steps are matched by substring, and a MISSING step is a failure too, so
# renaming one disables the workflow step and this check together rather than
# leaving a check that silently matches nothing (#579).
#
# Usage: check-roborazzi-step-order.sh [workflow.yml]

set -euo pipefail

workflow=${1:-.github/workflows/android-ci.yml}

if [[ ! -f "$workflow" ]]; then
  echo "FAIL: no workflow at $workflow"
  exit 1
fi

# `android-ci.yml` has a single job, so every top-level step in the file belongs
# to it and document order is execution order. Asserted rather than assumed: if
# a second job is ever added, the flat scan below stops being meaningful and
# this must be revisited instead of quietly comparing steps across jobs.
job_count=$(grep -cE '^  [A-Za-z0-9_-]+:$' "$workflow" | tr -d ' ')
jobs_line=$(grep -n '^jobs:' "$workflow" | cut -d: -f1)
job_names=$(awk -v start="$jobs_line" 'NR > start && /^  [A-Za-z0-9_-]+:$/ { gsub(/[ :]/, ""); print }' "$workflow")
if [[ "$(printf '%s\n' "$job_names" | grep -c .)" -ne 1 ]]; then
  echo "FAIL: $workflow no longer has exactly one job (found: $(echo "$job_names" | tr '\n' ' '))"
  echo "      This check scans steps flat, which is only valid for a single job."
  exit 1
fi
: "$job_count"

# Ordered list of step names, one per line, in document = execution order.
# Steps declared as a bare `- uses:` carry no name and are absent from this
# list, so the positions below are positions among NAMED steps, not true step
# indices. Only their relative order is asserted, which is unaffected.
steps=$(sed -n 's/^      - name:[[:space:]]*//p' "$workflow" | sed 's/^"\(.*\)"$/\1/')

# index_of <substring> -> 1-based position, or empty if absent.
#
# `|| true` is load-bearing: grep exits 1 on no match, and under
# `set -euo pipefail` that propagates out of the command substitution at the
# call site and kills the script -- exiting 1 with NO output, which reads as a
# crash rather than as the "step is missing" finding it actually is. Caught by
# the self-test's missing-step cases (same trap as check-roborazzi-coverage.sh).
index_of() {
  { printf '%s\n' "$steps" | grep -nF -- "$1" || true; } | head -1 | cut -d: -f1
}

EXIT=0

require_before() {
  local earlier=$1 later=$2 why=$3
  local i j
  i=$(index_of "$earlier")
  j=$(index_of "$later")

  if [[ -z "$i" ]]; then
    echo "FAIL: no step named like '$earlier' in $workflow"
    echo "      Renamed or deleted. This ordering check cannot protect what it"
    echo "      cannot find, so it fails rather than passing vacuously."
    EXIT=1
    return
  fi
  if [[ -z "$j" ]]; then
    echo "FAIL: no step named like '$later' in $workflow"
    echo "      Renamed or deleted. This ordering check cannot protect what it"
    echo "      cannot find, so it fails rather than passing vacuously."
    EXIT=1
    return
  fi
  if [[ "$i" -ge "$j" ]]; then
    echo "FAIL: '$later' (named step $j) must run AFTER '$earlier' (named step $i)."
    echo "      $why"
    EXIT=1
    return
  fi
  echo "OK   '$later' (named step $j) runs after '$earlier' (named step $i)"
}

require_before "Upload test reports" "Screenshot goldens" \
  "The verify run re-runs :app's suite and overwrites app/build/reports/tests/, so above the upload it would clobber a failing run's report (#626)."

require_before "Coverage report (Kover)" "Screenshot goldens" \
  "Verify mode is a Gradle task input; above Kover it breaks that step's documented UP-TO-DATE reuse and adds a full suite re-run inside its 10-minute cap."

require_before "Screenshot goldens" "Upload Roborazzi comparison images" \
  "The comparison images do not exist until the verify step has run."

exit "$EXIT"
