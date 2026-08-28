#!/usr/bin/env bash
# Regression tests for scripts/check-roborazzi-coverage.sh (#628).
#
# That script is the positive control for the `verifyRoborazziDebug` CI gate: it
# asserts Roborazzi actually compared every committed golden, because a verify
# run that compares NOTHING also prints BUILD SUCCESSFUL. A control with a bug
# is the same defect one level up -- #597 is open precisely because existing
# gates acquired bugs that made them silently green -- so the control's own red
# paths are exercised here rather than trusted.
#
# Two cases matter most, and both were live bugs in earlier drafts:
#
#   * `zero_glob` -- the control compares a count on disk with a count from
#     Roborazzi, and an equality check is satisfied by 0 == 0. The first draft
#     passed that case green.
#   * `retried_failure` -- `summary.total` counts comparisons PERFORMED, and the
#     `test-retry` plugin re-runs a failing test up to 3x. The second draft
#     asserted on `total`, so every genuinely drifted image made the control
#     fire a bogus coverage failure ALONGSIDE the real one (measured: total 131
#     against 129 goldens, from a single perturbed pixel). The control must
#     count DISTINCT goldens, which is what that case pins.
#
# If either guard regresses, this goes red.
#
# Fourth instance of the harness shape behind #577/#588, #590/#593 and #594:
# build a throwaway tree and run the REAL script end-to-end.

set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
gate=scripts/check-roborazzi-coverage.sh

sandbox=$(mktemp -d)
trap 'rm -rf "$sandbox"' EXIT

failures=0

app_goldens=app/screenshots
listing_goldens=fastlane/metadata/android/en-US/images/phoneScreenshots
notif_goldens=notifications/screenshots
app_summary=app/build/test-results/roborazzi/debug/results-summary.json
notif_summary=notifications/build/test-results/roborazzi/debug/results-summary.json

# A tree shaped like the real repo: 3 + 2 goldens for :app (across BOTH of its
# golden trees, so the fastlane tree is covered too), 4 for :notifications, and
# summaries that agree. The PNGs are empty files -- the control counts them, it
# never decodes them.
setup_sandbox() {
  rm -rf "${sandbox:?}"/*
  mkdir -p "$sandbox/scripts" \
    "$sandbox/$app_goldens" "$sandbox/$listing_goldens" "$sandbox/$notif_goldens" \
    "$sandbox/$(dirname "$app_summary")" "$sandbox/$(dirname "$notif_summary")"
  cp "$repo_root/$gate" "$sandbox/scripts/"
  touch "$sandbox/$app_goldens"/{01_a_dark.png,01_a_light.png,02_b_dark.png}
  touch "$sandbox/$listing_goldens"/{1_welcome.png,2_home.png}
  touch "$sandbox/$notif_goldens"/{01_c_dark.png,01_c_light.png,02_d_dark.png,02_d_light.png}
  write_summary "$sandbox/$app_summary" \
    01_a_dark.png 01_a_light.png 02_b_dark.png 1_welcome.png 2_home.png
  write_summary "$sandbox/$notif_summary" \
    01_c_dark.png 01_c_light.png 02_d_dark.png 02_d_light.png
}

# write_summary <file> <golden-name>...
#
# One `results[]` entry per name, so passing the same name twice simulates the
# `test-retry` plugin re-running a failed comparison. `summary.total` is set to
# the number of ENTRIES (what Roborazzi really does) rather than the number of
# distinct names, so a fixture with retries has an inflated total exactly like a
# real one -- which is what makes the retried_failure case meaningful.
write_summary() {
  local file=$1
  shift
  local entries="" name
  for name in "$@"; do
    [ -n "$entries" ] && entries="$entries,"
    entries="$entries{\"golden_file_path\":\"/sandbox/$name\",\"type\":\"unchanged\"}"
  done
  printf '{"summary":{"total":%s,"recorded":0,"added":0,"changed":0,"unchanged":%s},"results":[%s]}\n' \
    "$#" "$#" "$entries" > "$file"
}

# expect <exit-status> <name> [<substring the output must contain>]
expect() {
  local want=$1 name=$2 must_say=${3:-} out status
  set +e
  out=$(cd "$sandbox" && bash "$gate" 2>&1)
  status=$?
  set -e

  if [ "$status" -ne "$want" ]; then
    echo "FAIL: $name -- expected exit $want, got $status"
    printf '%s\n' "$out" | sed 's/^/    /'
    failures=$((failures + 1))
    return
  fi
  # A red run must say what is wrong, not merely exit nonzero: the whole point
  # of the control is that someone can act on its output.
  if [ -n "$must_say" ] && ! printf '%s\n' "$out" | grep -qF "$must_say"; then
    echo "FAIL: $name -- exited $status but never said '$must_say'"
    printf '%s\n' "$out" | sed 's/^/    /'
    failures=$((failures + 1))
    return
  fi
  echo "ok: $name (exit $status)"
}

# --- green -------------------------------------------------------------------
setup_sandbox
expect 0 "counts agree across both :app golden trees and :notifications"

# --- THE vacuity case --------------------------------------------------------
# Goldens gone AND nothing compared. Equality alone says 0 == 0 and passes; this
# is the case the control exists to catch, so it is the case most worth pinning.
setup_sandbox
rm -f "$sandbox/$app_goldens"/*.png "$sandbox/$listing_goldens"/*.png
write_summary "$sandbox/$app_summary"
expect 1 "zero_glob: no goldens on disk, nothing compared" "found 0 golden PNGs"

# Same shape via a RENAMED tree rather than deleted files: the dirs the control
# names no longer exist, which must not be mistaken for a clean run.
setup_sandbox
mv "$sandbox/$notif_goldens" "$sandbox/notifications/screenshots-renamed"
write_summary "$sandbox/$notif_summary"
expect 1 "zero_glob: golden tree renamed out from under the control" "does not exist"

# --- drift between the two counts -------------------------------------------
setup_sandbox
write_summary "$sandbox/$app_summary" 01_a_dark.png 01_a_light.png 02_b_dark.png
expect 1 "a screenshot test stopped running (fewer goldens compared than committed)" \
  "compared 3 distinct goldens"

setup_sandbox
touch "$sandbox/$app_goldens/03_orphan_dark.png"
expect 1 "an orphan golden is committed with no test rendering it" \
  "compared 5 distinct goldens"

# Only the fastlane tree drifts: proves :app's second golden tree is counted,
# not just app/screenshots.
setup_sandbox
touch "$sandbox/$listing_goldens/3_orphan.png"
expect 1 "drift confined to the fastlane listing tree is still caught" \
  "compared 5 distinct goldens"

# Per-module isolation: :notifications drifting must not be reported as :app.
setup_sandbox
write_summary "$sandbox/$notif_summary" 01_c_dark.png 01_c_light.png
expect 1 "a :notifications mismatch is attributed to :notifications" \
  "FAIL [notifications]"

# --- the run did not happen / cannot be read ---------------------------------
setup_sandbox
rm -f "$sandbox/$app_summary"
expect 1 "verify never ran (no results summary)" "no Roborazzi results summary"

setup_sandbox
printf '{"summary":{"total":5,"recorded":0}}\n' > "$sandbox/$app_summary"
expect 1 "summary exists but records no per-image results" "records no golden_file_path"

# Roborazzi renaming the field must break LOUDLY, not silently pass. A control
# that stops being able to read its input and reports OK is the whole bug class.
setup_sandbox
sed -i 's/golden_file_path/golden_image_path/g' "$sandbox/$app_summary"
expect 1 "the per-image field was renamed by a Roborazzi upgrade" \
  "records no golden_file_path"

# --- THE retry case ----------------------------------------------------------
# A genuinely drifted image, retried 3x by the test-retry plugin: 7 comparisons
# over 5 distinct goldens. The verify task reports the drift; the control must
# stay QUIET, because coverage is fine. Asserting on summary.total made this red
# and buried the real failure under a bogus one.
setup_sandbox
write_summary "$sandbox/$app_summary" \
  01_a_dark.png 01_a_dark.png 01_a_dark.png 01_a_light.png 02_b_dark.png \
  1_welcome.png 2_home.png
expect 0 "retried_failure: 7 comparisons over 5 distinct goldens is full coverage"

# The retry must not MASK a coverage gap either: one golden genuinely missing,
# another retried to the same total. Counting comparisons would call this fine.
setup_sandbox
write_summary "$sandbox/$app_summary" \
  01_a_dark.png 01_a_dark.png 01_a_light.png 02_b_dark.png 1_welcome.png
expect 1 "retries cannot paper over a golden that was never compared" \
  "compared 4 distinct goldens"

if [ "$failures" -ne 0 ]; then
  echo
  echo "$failures case(s) failed: scripts/check-roborazzi-coverage.sh is not"
  echo "behaving as its own tests describe. Fix the gate, not these tests."
  exit 1
fi

echo
echo "All check-roborazzi-coverage.sh cases behaved as expected."
