#!/usr/bin/env bash
# Regression tests for scripts/check-zombie-screens.sh (#705, #657).
#
# WHY THIS FILE EXISTS
# --------------------
# This gate stops an edit landing in a dead file, and it had shipped with no
# self-test at all. #657 already established that it CAN produce a well-formed
# lie: with a deliberately broken `awk` the pre-#657 form printed
# "OK: no zombie screens" and exited 0 over a tree containing one, because a
# process substitution reports no status and an unreadable file is
# indistinguishable from a file that declares nothing. That red case is known,
# so it is pinned here rather than described in a comment (Part 3).
#
# The gate reads a hardcoded RELATIVE path (`app/src/main/...`), so every case
# runs it with the working directory inside a throwaway tree built here. No
# probe files are ever planted in the real repo.

set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
gate=$repo_root/scripts/check-zombie-screens.sh

sandbox=$(mktemp -d)
trap 'rm -rf "$sandbox"' EXIT

failures=0
out_file=$sandbox/gate-output.txt
tree=$sandbox/tree
screens=app/src/main/java/com/rousecontext/app/ui/screens
prod=app/src/main/java/com/rousecontext/app/ui
run_status=0
run_ok=0
has_verdict=
has_status=0

# ---------------------------------------------------------------------------
# Reading the gate's output.
#
# `grep` over a FILE, never `printf ... | grep -q` (#716/#725): that pipeline
# folds three different outcomes into one answer -- grep found nothing, grep
# could not answer, and grep matched EARLY and killed the writer, whose SIGPIPE
# `pipefail` then reports as the pipeline's status for `!` to invert into "no
# match". With no pipe there is no writer to kill, and grep's own status is
# read, so "could not answer" is reported as its own thing instead of as
# "absent".
# ---------------------------------------------------------------------------
out_has() {
  set +e
  grep -qF -e "$1" "$out_file"
  has_status=$?
  set -e
  case "$has_status" in
    0) has_verdict=yes ;;
    1) has_verdict=no ;;
    *) has_verdict=broken ;;
  esac
}

fail_case() {
  echo "FAIL: $*"
  sed 's/^/    /' "$out_file"
  failures=$((failures + 1))
}

# ---------------------------------------------------------------------------
# The throwaway tree. `reset_tree` gives an app with one screen already wired
# into navigation, so a case that plants a zombie is measuring the zombie and
# not an empty tree -- the same reason the APK harness stages app code before
# adding markers.
# ---------------------------------------------------------------------------
reset_tree() {
  rm -rf "$tree"
  mkdir -p "$tree/$screens" "$tree/$prod"
  cat > "$tree/$screens/HomeScreen.kt" <<'KT'
package com.rousecontext.app.ui.screens

import androidx.compose.runtime.Composable

@Composable
fun HomeScreen() {
}
KT
  cat > "$tree/$prod/AppNavigation.kt" <<'KT'
package com.rousecontext.app.ui

import com.rousecontext.app.ui.screens.HomeScreen

fun AppNavigation() {
    HomeScreen()
}
KT
}

# screen <file-name> <body>
screen() {
  printf '%s\n' "$2" > "$tree/$screens/$1"
}

# prod <relative-path> <body>
prod_file() {
  mkdir -p "$(dirname "$tree/$prod/$1")"
  printf '%s\n' "$2" > "$tree/$prod/$1"
}

# run <want-exit> <label> [gate-path] -- runs the gate from inside the tree.
run() {
  local want=$1 label=$2 which=${3:-$gate}
  set +e
  ( cd "$tree" && bash "$which" ) > "$out_file" 2>&1
  run_status=$?
  set -e
  if [ "$run_status" -ne "$want" ]; then
    run_ok=0
    fail_case "$label -- expected exit $want, got $run_status"
    return
  fi
  run_ok=1
  echo "ok: $label (exit $run_status)"
}

# must_say <label> <fixed string> ...
#
# Exiting non-zero is not the bar. #678's step-order gate exited 1 with zero
# bytes of output on the two inputs it existed to catch -- a crash that reads
# as a finding. A red run has to name the file, and the function, or nobody can
# act on it.
must_say() {
  local label=$1 s
  shift
  if [ "$run_ok" -ne 1 ]; then
    return
  fi
  for s in "$@"; do
    out_has "$s"
    if [ "$has_verdict" = broken ]; then
      fail_case "$label -- the output read itself failed (grep exited $has_status)"
      return
    fi
    if [ "$has_verdict" = no ]; then
      fail_case "$label -- output never contained: $s"
      return
    fi
  done
  echo "ok: $label names what it found"
}

# must_not_say <label> <fixed string> ...
must_not_say() {
  local label=$1 s
  shift
  if [ "$run_ok" -ne 1 ]; then
    return
  fi
  for s in "$@"; do
    out_has "$s"
    if [ "$has_verdict" = broken ]; then
      fail_case "$label -- the output read itself failed (grep exited $has_status)"
      return
    fi
    if [ "$has_verdict" = yes ]; then
      fail_case "$label -- output should not have contained: $s"
      return
    fi
  done
  echo "ok: $label stays silent about $*"
}

zombie_kt='package com.rousecontext.app.ui.screens

import androidx.compose.runtime.Composable

@Composable
fun GhostScreen() {
}'

# ===========================================================================
# Part 1 -- RED. A tree containing the thing the gate exists to catch.
# ===========================================================================

reset_tree
screen GhostScreen.kt "$zombie_kt"
run 1 'an unreferenced screen is caught'
must_say 'an unreferenced screen is caught' \
  'zombie screen files detected' \
  "$screens/GhostScreen.kt" \
  '(functions: GhostScreen)'
must_not_say 'an unreferenced screen is caught' 'OK: no zombie screens'

# A file that references only ITSELF is still dead. The gate excludes the file
# under test from its own reference search; without that, every screen would
# vouch for itself and the gate would pass everything.
reset_tree
screen SelfScreen.kt 'package com.rousecontext.app.ui.screens

import androidx.compose.runtime.Composable

@Composable
fun SelfScreen() {
    SelfScreen()
}'
run 1 'a screen that only references itself is caught'
must_say 'a screen that only references itself is caught' \
  "$screens/SelfScreen.kt"

# Referenced from a TEST, not from production. `app/src/test` is outside
# PROD_ROOT on purpose: a screen kept alive only by its own test is exactly the
# dead-code shape this gate is for.
reset_tree
screen GhostScreen.kt "$zombie_kt"
mkdir -p "$tree/app/src/test/java/com/rousecontext/app"
printf '%s\n' 'class GhostScreenTest { fun t() { GhostScreen() } }' \
  > "$tree/app/src/test/java/com/rousecontext/app/GhostScreenTest.kt"
run 1 'a screen referenced only from a test is caught'
must_say 'a screen referenced only from a test is caught' \
  "$screens/GhostScreen.kt"

# Referenced from a non-Kotlin production file. The reference search is
# `--include='*.kt'`, so a name that survives only in a comment in some XML or
# a .pro file is not a live call site.
reset_tree
screen GhostScreen.kt "$zombie_kt"
prod_file navigation.xml '<!-- GhostScreen -->'
run 1 'a name appearing only in a non-Kotlin file does not rescue a screen'
must_say 'a name appearing only in a non-Kotlin file does not rescue a screen' \
  "$screens/GhostScreen.kt"

# Word boundaries. `GhostScreenKt` is a different symbol; a reference search
# that matched on substrings would let a stale generated-class name keep a dead
# screen alive.
reset_tree
screen GhostScreen.kt "$zombie_kt"
prod_file Registry.kt 'package com.rousecontext.app.ui

val names = listOf("GhostScreenKt", "MyGhostScreen")'
run 1 'a substring of the name does not count as a reference'
must_say 'a substring of the name does not count as a reference' \
  "$screens/GhostScreen.kt"

# Every zombie is listed, not just the first. A gate that stops at one turns a
# cleanup into N round trips.
reset_tree
screen GhostScreen.kt "$zombie_kt"
screen PhantomScreen.kt 'package com.rousecontext.app.ui.screens

import androidx.compose.runtime.Composable

@Composable
fun PhantomScreen() {
}'
run 1 'every zombie is listed'
must_say 'every zombie is listed' \
  "$screens/GhostScreen.kt" \
  "$screens/PhantomScreen.kt"

# ===========================================================================
# Part 2 -- GREEN. The cases that must NOT fire.
# ===========================================================================

reset_tree
run 0 'a wired-up screen passes'
must_say 'a wired-up screen passes' 'OK: no zombie screens'

# Not every *Screen.kt holds a composable. Enums, sealed classes and mode
# objects live here too, and a gate that flagged them would be worked around.
reset_tree
screen SetupMode.kt 'package com.rousecontext.app.ui.screens

enum class SetupMode { FIRST_RUN, RETURNING }'
run 0 'a file with no top-level composable is skipped'

# `private fun` is not an entry point, so a file whose public composable is
# referenced passes even though its private helpers are named nowhere else.
reset_tree
screen HelperScreen.kt 'package com.rousecontext.app.ui.screens

import androidx.compose.runtime.Composable

@Composable
fun HelperScreen() {
    HelperRow()
}

@Composable
private fun HelperRow() {
}'
prod_file Extra.kt 'package com.rousecontext.app.ui

fun show() { HelperScreen() }'
run 0 'private helpers do not have to be referenced'

# ===========================================================================
# Part 3 -- the #657 red case, pinned.
#
# The gate reads its composable names through `awk`, and the pre-#657 form took
# that through a process substitution, which reports NO status: an `awk` that
# failed produced an empty list, indistinguishable from "this file declares no
# composables", and the file was then skipped in silence. #657 measured the
# result -- "OK: no zombie screens", exit 0, over a tree containing one.
#
# Two runs, and both are needed. The first asserts the CURRENT gate fails by
# name when `awk` cannot run. The second reintroduces the old shape and
# requires it to produce exactly the reported lie, which is what proves the
# first assertion is not passing for some unrelated reason -- the tree really
# does hide a zombie the moment the status goes unchecked.
# ===========================================================================

# A PATH shim, so the REAL gate is exercised: nothing about it is modified, it
# simply cannot read the file. Only `awk` is shadowed; the rest of the tools it
# calls resolve normally.
mkdir -p "$sandbox/brokenbin"
printf '#!/bin/sh\nexit 7\n' > "$sandbox/brokenbin/awk"
chmod +x "$sandbox/brokenbin/awk"

run_with_broken_awk() {
  local want=$1 label=$2 which=${3:-$gate}
  set +e
  ( cd "$tree" && PATH="$sandbox/brokenbin:$PATH" bash "$which" ) > "$out_file" 2>&1
  run_status=$?
  set -e
  if [ "$run_status" -ne "$want" ]; then
    run_ok=0
    fail_case "$label -- expected exit $want, got $run_status"
    return
  fi
  run_ok=1
  echo "ok: $label (exit $run_status)"
}

reset_tree
screen GhostScreen.kt "$zombie_kt"
run_with_broken_awk 1 'an awk that cannot run is a failure, not a pass'
must_say 'an awk that cannot run is a failure, not a pass' \
  'ERROR: could not extract composable names from' \
  'The file was not scanned, so this is a failure and not a pass.'
must_not_say 'an awk that cannot run is a failure, not a pass' 'OK: no zombie screens'

# The old shape, reconstructed by replacing the failure branch with a silent
# skip -- which is precisely what a status-free read amounts to.
unchecked_gate=$sandbox/unchecked-gate.sh
sed '/could not extract composable names/,+2c\    func_names=' "$gate" > "$unchecked_gate"
set +e
cmp -s "$gate" "$unchecked_gate"
mutation_took=$?
set -e
if [ "$mutation_took" -eq 0 ]; then
  echo "FAIL: reconstructing the pre-#657 shape did not change the gate --" \
    "the control below would be vacuous"
  failures=$((failures + 1))
else
  echo "ok: the pre-#657 shape reconstructs"
  # Same tree, same broken awk, status unchecked: the reported lie.
  run_with_broken_awk 0 'the pre-#657 shape reports OK over a zombie (#657)' "$unchecked_gate"
  must_say 'the pre-#657 shape reports OK over a zombie (#657)' 'OK: no zombie screens'
  # And with a working awk it still finds the zombie, so the run above differs
  # from the run under it in exactly one thing: whether awk could read the file.
  run 1 'the reconstructed shape still catches the zombie when awk works' "$unchecked_gate"
  must_say 'the reconstructed shape still catches the zombie when awk works' \
    "$screens/GhostScreen.kt"
fi

# ===========================================================================
# Part 4 -- the exemption list. An escape hatch nobody has tested is how a gate
# quietly stops covering things (#579).
# ===========================================================================

exempt_gate=$sandbox/exempt-gate.sh
sed 's/^KNOWN_EXEMPT=()$/KNOWN_EXEMPT=(GhostScreen.kt)/' "$gate" > "$exempt_gate"
set +e
cmp -s "$gate" "$exempt_gate"
exempt_took=$?
set -e
if [ "$exempt_took" -eq 0 ]; then
  echo "FAIL: adding an exemption did not change the gate -- the control below would be vacuous"
  failures=$((failures + 1))
else
  echo "ok: the exemption mutation took"
  reset_tree
  screen GhostScreen.kt "$zombie_kt"
  run 0 'an exempted zombie passes' "$exempt_gate"
  # And the exemption is per file, not a blanket switch.
  screen PhantomScreen.kt 'package com.rousecontext.app.ui.screens

import androidx.compose.runtime.Composable

@Composable
fun PhantomScreen() {
}'
  run 1 'an exemption covers only the file it names' "$exempt_gate"
  must_say 'an exemption covers only the file it names' "$screens/PhantomScreen.kt"
  must_not_say 'an exemption covers only the file it names' 'GhostScreen.kt'
fi

# ===========================================================================
# Part 5 -- residues. Measured behaviours that are NOT what a reader would
# assume. Asserted rather than left implicit: if someone changes one of these,
# it should be a decision with a failing test in front of it.
# ===========================================================================

# 5a. GREEN BY ABSENCE -- tracked as #758. With no screens directory the glob
# does not expand, the loop body never runs, and the gate prints OK and exits
# 0. It is latent only because android-ci.yml happens to invoke it from the
# repo root. When #758 lands, this assertion flips to `run 1` plus a message
# check; it is here so the hole is measured rather than assumed absent.
rm -rf "$tree"
mkdir -p "$tree"
run 0 'residue (#758): a missing screens directory reports OK'
must_say 'residue (#758): a missing screens directory reports OK' 'OK: no zombie screens'

# 5b. The check is per FILE, not per function: one referenced composable
# vouches for every other public composable in the same file. Coarse by
# design -- the gate's own header says so -- but worth pinning, because it is
# the difference between "no dead screens" and "no dead composables".
reset_tree
screen MixedScreen.kt 'package com.rousecontext.app.ui.screens

import androidx.compose.runtime.Composable

@Composable
fun MixedScreen() {
}

@Composable
fun MixedScreenDetail() {
}'
prod_file Extra.kt 'package com.rousecontext.app.ui

fun show() { MixedScreen() }'
run 0 'residue: one live composable vouches for the whole file'

# 5c. `@Preview` skips the ANNOTATION line, not the function under it. Only
# `private fun` is dropped, so a PUBLIC preview composable counts as an entry
# point and an unreferenced one is reported as a zombie. The gate's header says
# previews "are ignored", which holds for the private ones it describes and not
# for this shape.
reset_tree
screen PreviewOnlyScreen.kt 'package com.rousecontext.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

@Preview
@Composable
fun PreviewOnlyScreen() {
}'
run 1 'residue: a PUBLIC @Preview composable is not exempt'
must_say 'residue: a PUBLIC @Preview composable is not exempt' \
  "$screens/PreviewOnlyScreen.kt"

# And the private form the header actually describes: dropped, so a file
# holding nothing else is skipped entirely.
reset_tree
screen PrivatePreviewScreen.kt 'package com.rousecontext.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

@Preview
@Composable
private fun PrivatePreviewScreen() {
}'
run 0 'a private @Preview composable is skipped'

if [ "$failures" -gt 0 ]; then
  echo "$failures test(s) failed"
  exit 1
fi
echo "All check-zombie-screens.sh tests passed"
