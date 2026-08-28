#!/usr/bin/env bash
# Positive control for the `verifyRoborazziDebug` CI gate (issue #628).
#
# `verifyRoborazziDebug` reports BUILD SUCCESSFUL both when it compared every
# committed golden and found no drift, and when it compared nothing at all --
# which is exactly the state CI was in before #628, and exactly the rot mode
# #579/#594 describe for the grep gates. A green verify is therefore not, by
# itself, evidence that verification happened.
#
# Roborazzi writes a machine-readable record of the run at
#
#   <module>/build/test-results/roborazzi/debug/results-summary.json
#     { "summary": { "total": N, ... },
#       "results": [ { "golden_file_path": "...", "type": "unchanged" }, ... ] }
#
# This script asserts that the set of goldens the run actually looked at matches
# the set of golden PNGs committed to the repo, so a verify pass that silently
# stopped covering them -- a test class dropped, a `--tests` filter, a plugin
# change that stops wiring the test task -- goes red instead of green.
#
# COUNT DISTINCT GOLDENS, NOT COMPARISONS. `summary.total` is the number of
# comparisons PERFORMED, and `:app` applies the `test-retry` plugin, which
# re-runs a failing test up to 3x. So every genuinely drifted image inflates
# `total` by 1-2 while the number of goldens on disk is unchanged. Measured on
# one deliberately perturbed pixel: total 131, distinct goldens 129, goldens on
# disk 129 (CI, being slower, retried harder still: 135). Asserting on `total`
# therefore fired a bogus "compared 131, but 129 committed" alongside every real
# drift -- misdiagnosing the exact failures this gate exists to surface, and
# being correct only on fully-green runs, which is when it has nothing to catch.
# `results[].golden_file_path` is per-image: retries repeat the same path, so
# de-duplicating it recovers the quantity we actually mean.
#
# The expected counts are DERIVED from the filesystem, never hardcoded: adding
# or removing a golden needs no edit here.
#
# An equality check has a vacuous solution -- zero goldens on disk compared
# against zero images verified -- and `0 -eq 0` is a pass. That is this script's
# own defect one level up: a renamed golden directory, a moved tree, or a
# `*.png` glob that stops matching would take the goldens and the comparison out
# together and report OK. So each module must also find a NON-ZERO number of
# goldens; a module that has stopped having goldens at all is a red flag, not a
# clean run.

set -euo pipefail

EXIT=0

# `:app` renders into two golden trees: the browsable gallery in
# `app/screenshots/` (ScreenScreenshotTest, SwitchRowScreenshotTest,
# BackgroundDeliveryScreenshotTest) and the F-Droid store listing in
# `fastlane/.../phoneScreenshots/` (ListingScreenshotTest, which captures via a
# `../fastlane/...` relative path). Both are compared by the same
# `:app:verifyRoborazziDebug` run, so both count toward its total.
check_module() {
  local module="$1"
  shift
  local summary="$module/build/test-results/roborazzi/debug/results-summary.json"

  if [[ ! -f "$summary" ]]; then
    echo "FAIL [$module]: no Roborazzi results summary at $summary"
    echo "      The verify task did not run, or did not get as far as comparing."
    EXIT=1
    return
  fi

  local expected=0
  local dir
  for dir in "$@"; do
    if [[ ! -d "$dir" ]]; then
      echo "FAIL [$module]: golden directory $dir does not exist"
      EXIT=1
      return
    fi
    expected=$((expected + $(find "$dir" -maxdepth 1 -name '*.png' -type f | wc -l)))
  done

  # The zero-glob guard. Without it the equality below is satisfied by
  # "no goldens, nothing compared" -- a green run that verifies nothing.
  if [[ "$expected" -eq 0 ]]; then
    echo "FAIL [$module]: found 0 golden PNGs under: $*"
    echo "      A module with no goldens cannot be verified, so comparing counts"
    echo "      here would pass vacuously. The tree has moved or been emptied."
    EXIT=1
    return
  fi

  # Distinct goldens compared. Extracted with grep/sort rather than jq: jq is not
  # guaranteed on every runner, the summary is a single line, and the field shape
  # is fixed by Roborazzi. If Roborazzi ever renames the field this yields 0,
  # which fails loudly below rather than passing quietly -- the right direction
  # for a control to break in.
  #
  # `|| true` is load-bearing: grep exits 1 when it matches nothing, and under
  # `set -euo pipefail` that killed the script before it could print the message
  # below -- exiting 1 with NO output, which looks like a crash rather than a
  # finding. Caught by the self-test's field-rename case.
  local compared
  compared=$( { grep -o '"golden_file_path"[[:space:]]*:[[:space:]]*"[^"]*"' "$summary" || true; } \
    | sort -u | wc -l)

  if [[ "$compared" -eq 0 ]]; then
    echo "FAIL [$module]: $summary records no golden_file_path entries"
    echo "      Either nothing was compared, or Roborazzi's result format changed"
    echo "      and this control can no longer read it. Both need a human."
    EXIT=1
    return
  fi

  if [[ "$compared" -ne "$expected" ]]; then
    echo "FAIL [$module]: Roborazzi compared $compared distinct goldens, but"
    echo "      $expected golden PNGs are committed under: $*"
    echo "      Either a screenshot test stopped running (the gate is no longer"
    echo "      covering those goldens), or an orphan golden is committed with no"
    echo "      test that renders it. Both are drift; fix the cause, not this count."
    echo "      NOTE: this counts DISTINCT goldens, so retried failures do not"
    echo "      inflate it -- a mismatch here is a coverage problem, not drift."
    EXIT=1
    return
  fi

  echo "OK   [$module]: verified $compared distinct goldens (matches $* on disk)"
}

check_module app app/screenshots fastlane/metadata/android/en-US/images/phoneScreenshots
check_module notifications notifications/screenshots

exit "$EXIT"
