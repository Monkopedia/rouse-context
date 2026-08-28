#!/usr/bin/env bash
# Positive control for the `verifyRoborazziDebug` CI gate (issue #628).
#
# `verifyRoborazziDebug` reports BUILD SUCCESSFUL both when it compared every
# committed golden and found no drift, and when it compared nothing at all --
# which is exactly the state CI was in before #628, and exactly the rot mode
# #579/#594 describe for the grep gates. A green verify is therefore not, by
# itself, evidence that verification happened.
#
# Roborazzi writes a machine-readable tally next to the run:
#
#   <module>/build/test-results/roborazzi/debug/results-summary.json
#     { "summary": { "total": N, "recorded": .., "added": .., "changed": ..,
#                    "unchanged": .. } }
#
# `total` is the number of images the run actually looked at. This script
# asserts it equals the number of golden PNGs committed to the repo for that
# module, so a verify pass that silently stopped covering the goldens -- a test
# class dropped, a `--tests` filter, a plugin change that stops wiring the test
# task -- goes red instead of green.
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

# module | summary json | golden dirs (space separated)
#
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

  # `total` is the last integer on the summary's `"total":` field. Parsed with
  # sed rather than jq: `jq` is not guaranteed on every runner, and the shape of
  # this file is fixed by Roborazzi.
  local total
  total="$(sed -n 's/.*"total"[[:space:]]*:[[:space:]]*\([0-9]\{1,\}\).*/\1/p' "$summary" | head -1)"

  if [[ -z "$total" ]]; then
    echo "FAIL [$module]: could not read \"total\" from $summary"
    EXIT=1
    return
  fi

  if [[ "$total" -ne "$expected" ]]; then
    echo "FAIL [$module]: Roborazzi compared $total images, but $expected golden"
    echo "      PNGs are committed under: $*"
    echo "      Either a screenshot test stopped running (the gate is no longer"
    echo "      covering those goldens), or an orphan golden is committed with no"
    echo "      test that renders it. Both are drift; fix the cause, not this count."
    EXIT=1
    return
  fi

  echo "OK   [$module]: verified $total goldens (matches $* on disk)"
}

check_module app app/screenshots fastlane/metadata/android/en-US/images/phoneScreenshots
check_module notifications notifications/screenshots

exit "$EXIT"
