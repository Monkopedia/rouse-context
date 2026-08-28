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
  "Upload test reports"
expect 1 "verify hoisted above Upload test reports" "must run AFTER 'Upload test reports'"

# Verify hoisted above Kover: breaks the documented UP-TO-DATE reuse.
write_workflow "$sandbox/wf.yml" \
  "Unit tests" \
  "Screenshot goldens match the committed PNGs (issue #628)" \
  "Coverage report (Kover)" \
  "Upload test reports" \
  "Upload Roborazzi comparison images"
expect 1 "verify hoisted above Coverage report (Kover)" "must run AFTER 'Coverage report (Kover)'"

# Upload placed before the step that produces the images.
write_workflow "$sandbox/wf.yml" \
  "Unit tests" \
  "Coverage report (Kover)" \
  "Upload test reports" \
  "Upload Roborazzi comparison images" \
  "Screenshot goldens match the committed PNGs (issue #628)"
expect 1 "upload placed before the verify step" \
  "must run AFTER 'Screenshot goldens'"

# --- the gate must not pass vacuously ----------------------------------------
# A renamed or deleted step takes the workflow step and the protection with it;
# that must be red, not a silent pass over steps that no longer exist.
write_workflow "$sandbox/wf.yml" \
  "Unit tests" "Coverage report (Kover)" "Upload test reports"
expect 1 "the verify step is missing entirely" "no step named like 'Screenshot goldens'"

write_workflow "$sandbox/wf.yml" \
  "Unit tests" \
  "Coverage report (Kover)" \
  "Screenshot goldens match the committed PNGs (issue #628)" \
  "Upload Roborazzi comparison images"
expect 1 "the Upload test reports step was renamed away" \
  "no step named like 'Upload test reports'"

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
