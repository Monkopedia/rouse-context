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
# The case that matters most is `zero_glob`: the control compares a count on
# disk with a count in Roborazzi's summary, and an equality check is satisfied
# by 0 == 0. The first draft of the control passed that case green. If the guard
# regresses, this goes red.
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
  write_summary "$sandbox/$app_summary" 5
  write_summary "$sandbox/$notif_summary" 4
}

write_summary() {
  printf '{"summary":{"total":%s,"recorded":0,"added":0,"changed":0,"unchanged":%s}}\n' \
    "$2" "$2" > "$1"
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
write_summary "$sandbox/$app_summary" 0
expect 1 "zero_glob: no goldens on disk, nothing compared" "found 0 golden PNGs"

# Same shape via a RENAMED tree rather than deleted files: the dirs the control
# names no longer exist, which must not be mistaken for a clean run.
setup_sandbox
mv "$sandbox/$notif_goldens" "$sandbox/notifications/screenshots-renamed"
write_summary "$sandbox/$notif_summary" 0
expect 1 "zero_glob: golden tree renamed out from under the control" "does not exist"

# --- drift between the two counts -------------------------------------------
setup_sandbox
write_summary "$sandbox/$app_summary" 3
expect 1 "a screenshot test stopped running (total understates the goldens)" \
  "compared 3 images, but 5 golden"

setup_sandbox
touch "$sandbox/$app_goldens/03_orphan_dark.png"
expect 1 "an orphan golden is committed with no test rendering it" \
  "compared 5 images, but 6 golden"

# Only the fastlane tree drifts: proves :app's second golden tree is counted,
# not just app/screenshots.
setup_sandbox
touch "$sandbox/$listing_goldens/3_orphan.png"
expect 1 "drift confined to the fastlane listing tree is still caught" \
  "compared 5 images, but 6 golden"

# Per-module isolation: :notifications drifting must not be reported as :app.
setup_sandbox
write_summary "$sandbox/$notif_summary" 2
expect 1 "a :notifications mismatch is attributed to :notifications" \
  "FAIL [notifications]"

# --- the run did not happen / cannot be read ---------------------------------
setup_sandbox
rm -f "$sandbox/$app_summary"
expect 1 "verify never ran (no results summary)" "no Roborazzi results summary"

setup_sandbox
printf '{"summary":{"recorded":0}}\n' > "$sandbox/$app_summary"
expect 1 "summary exists but carries no total" 'could not read "total"'

if [ "$failures" -ne 0 ]; then
  echo
  echo "$failures case(s) failed: scripts/check-roborazzi-coverage.sh is not"
  echo "behaving as its own tests describe. Fix the gate, not these tests."
  exit 1
fi

echo
echo "All check-roborazzi-coverage.sh cases behaved as expected."
