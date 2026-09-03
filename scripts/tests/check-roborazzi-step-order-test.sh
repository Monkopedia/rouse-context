#!/usr/bin/env bash
# Regression tests for scripts/check-roborazzi-step-order.sh (#628).
#
# That gate exists because the ordering it protects is invisible: moving the
# screenshot-verify step above "Upload test reports" breaks nothing loudly, it
# just makes the uploaded artifact omit the reports for the tests that failed
# (#626). A gate for an invisible constraint is itself easy to break invisibly,
# so its red paths are exercised here against fixture workflows.
#
# Same harness shape as #577/#590/#594 and check-roborazzi-coverage-test.sh:
# a throwaway tree, the REAL script run end-to-end.

set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
gate="$repo_root/scripts/check-roborazzi-step-order.sh"

sandbox=$(mktemp -d)
trap 'rm -rf "$sandbox"' EXIT

failures=0

# Emit a single-job workflow whose steps are exactly the names passed in, in
# order. Only the `- name:` lines matter to the gate.
write_workflow() {
  local file=$1
  shift
  {
    echo "name: Android CI"
    echo "on: [push]"
    echo "jobs:"
    echo "  test:"
    echo "    runs-on: ubuntu-latest"
    echo "    steps:"
    local n
    for n in "$@"; do
      echo "      - name: \"$n\""
      echo "        run: true"
    done
  } > "$file"
}

# The real order, as android-ci.yml has it.
good_order=(
  "Unit tests"
  "Coverage report (Kover)"
  "Upload coverage HTML"
  "Upload test reports"
  "Upload test results (XML)"
  "Screenshot goldens match the committed PNGs (issue #628)"
  "Roborazzi really compared every committed golden (issue #628)"
  "Upload Roborazzi comparison images"
)

# expect <exit-status> <name> [<substring the output must contain>]
expect() {
  local want=$1 name=$2 must_say=${3:-} out status
  set +e
  out=$(bash "$gate" "$sandbox/wf.yml" 2>&1)
  status=$?
  set -e
  if [ "$status" -ne "$want" ]; then
    echo "FAIL: $name -- expected exit $want, got $status"
    printf '%s\n' "$out" | sed 's/^/    /'
    failures=$((failures + 1))
    return
  fi
  # The bug's signature is the ABSENCE of output, not the status: a gate that
  # goes red with zero bytes reads as a crash rather than as the finding it is
  # (#628, and #678 for this script's own instance of it). Asserted separately
  # from the substring so that even a case written without a `must_say` cannot
  # pass against that shape. (`$(...)` strips trailing newlines, so an empty
  # `$out` means the gate printed nothing but newlines.)
  if [ "$want" -ne 0 ] && [ -z "$out" ]; then
    echo "FAIL: $name -- exited $status with ZERO bytes of output"
    echo "      A gate that fails silently reads as a crash, not as a finding."
    failures=$((failures + 1))
    return
  fi
  if [ -n "$must_say" ] && ! printf '%s\n' "$out" | grep -qF "$must_say"; then
    echo "FAIL: $name -- exited $status but never said '$must_say'"
    printf '%s\n' "$out" | sed 's/^/    /'
    failures=$((failures + 1))
    return
  fi
  echo "ok: $name (exit $status)"
}

# --- green -------------------------------------------------------------------
write_workflow "$sandbox/wf.yml" "${good_order[@]}"
expect 0 "the shipped order passes"

# --- THE regression this gate exists for -------------------------------------
# Verify hoisted above "Upload test reports": the move that silently reproduces
# #626 by clobbering app/build/reports/tests/ before it is collected.
write_workflow "$sandbox/wf.yml" \
  "Unit tests" \
  "Coverage report (Kover)" \
  "Screenshot goldens match the committed PNGs (issue #628)" \
  "Upload Roborazzi comparison images" \
  "Upload coverage HTML" \
  "Upload test reports" \
  "Upload test results (XML)"
expect 1 "verify hoisted above Upload test reports" "must run AFTER 'Upload test reports'"

# Same move, seen from the XML upload: verify also overwrites
# app/build/test-results/, and the XML is the half carrying the assertion
# message (#631). Hoisting verify above it is the #631 failure mode.
write_workflow "$sandbox/wf.yml" \
  "Unit tests" \
  "Coverage report (Kover)" \
  "Upload test reports" \
  "Screenshot goldens match the committed PNGs (issue #628)" \
  "Upload Roborazzi comparison images" \
  "Upload test results (XML)"
expect 1 "verify hoisted above Upload test results (XML)" \
  "must run AFTER 'Upload test results (XML)'"

# Verify hoisted above Kover: breaks the documented UP-TO-DATE reuse.
write_workflow "$sandbox/wf.yml" \
  "Unit tests" \
  "Screenshot goldens match the committed PNGs (issue #628)" \
  "Coverage report (Kover)" \
  "Upload test reports" \
  "Upload test results (XML)" \
  "Upload Roborazzi comparison images"
expect 1 "verify hoisted above Coverage report (Kover)" "must run AFTER 'Coverage report (Kover)'"

# Upload placed before the step that produces the images.
write_workflow "$sandbox/wf.yml" \
  "Unit tests" \
  "Coverage report (Kover)" \
  "Upload test reports" \
  "Upload test results (XML)" \
  "Upload Roborazzi comparison images" \
  "Screenshot goldens match the committed PNGs (issue #628)"
expect 1 "upload placed before the verify step" \
  "must run AFTER 'Screenshot goldens'"

# --- the gate must not pass vacuously ----------------------------------------
# A renamed or deleted step takes the workflow step and the protection with it;
# that must be red, not a silent pass over steps that no longer exist.
write_workflow "$sandbox/wf.yml" \
  "Unit tests" "Coverage report (Kover)" "Upload test reports" "Upload test results (XML)"
expect 1 "the verify step is missing entirely" "no step named like 'Screenshot goldens'"

write_workflow "$sandbox/wf.yml" \
  "Unit tests" \
  "Coverage report (Kover)" \
  "Upload test results (XML)" \
  "Screenshot goldens match the committed PNGs (issue #628)" \
  "Upload Roborazzi comparison images"
expect 1 "the Upload test reports step was renamed away" \
  "no step named like 'Upload test reports'"

# The XML upload deleted or renamed: the #631 collection stops happening and
# the artifact goes back to HTML-only. That must be red here, not a silent pass.
write_workflow "$sandbox/wf.yml" \
  "Unit tests" \
  "Coverage report (Kover)" \
  "Upload test reports" \
  "Screenshot goldens match the committed PNGs (issue #628)" \
  "Upload Roborazzi comparison images"
expect 1 "the Upload test results (XML) step was renamed away" \
  "no step named like 'Upload test results (XML)'"

# The flat scan is only valid for a single job; a second job must force a human
# to revisit it rather than silently comparing steps across jobs.
write_workflow "$sandbox/wf.yml" "${good_order[@]}"
{
  echo "  second:"
  echo "    runs-on: ubuntu-latest"
  echo "    steps:"
  echo "      - name: \"Something else\""
  echo "        run: true"
} >> "$sandbox/wf.yml"
expect 1 "a second job invalidates the flat scan" "no longer has exactly one job"

# The same precondition seen from the other end, and the two inputs #678
# measured. `grep` exits 1 when it matches nothing, so under `set -euo pipefail`
# the two scans that locate the jobs: key used to kill the script here -- exit 1
# with NO output, on precisely the inputs this check exists to notice. Both
# cases assert the MESSAGE, not just the status: the status was already 1 while
# the bug was live, and `expect` above independently rejects a silent red.
{
  echo "name: Android CI"
  echo "on: [push]"
} > "$sandbox/wf.yml"
expect 1 "a workflow with no jobs: key at all" "has no top-level 'jobs:' key"

# Flow-style `jobs: {}`: the jobs: key is there, but no job key is on its own
# line for the flat scan to see. Vacuously passing over zero steps would be the
# worst outcome -- the ordering would stop being protected with nothing said.
{
  echo "name: Android CI"
  echo "on: [push]"
  echo "jobs: {}"
} > "$sandbox/wf.yml"
expect 1 "jobs: written flow-style, invisible to the flat scan" \
  "no job this scan can see"

# A workflow that is not there at all.
rm -f "$sandbox/wf.yml"
expect 1 "the workflow file is missing" "no workflow at"

if [ "$failures" -ne 0 ]; then
  echo
  echo "$failures case(s) failed: scripts/check-roborazzi-step-order.sh is not"
  echo "behaving as its own tests describe. Fix the gate, not these tests."
  exit 1
fi

echo
echo "All check-roborazzi-step-order.sh cases behaved as expected."
