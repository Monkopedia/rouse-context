#!/usr/bin/env bash
# Fails if `runBlocking` appears in any production Kotlin source tree (#136).
#
# Production code should be runBlocking-free: it blocks a thread until the
# coroutine completes, which on Android means an ANR when it happens on the main
# thread and a starved dispatcher when it happens anywhere else. Tests and the
# debug-only ADB harness may use it freely -- neither is in the scanned set.
# After #152 collapsed TlsAcceptor's java.io bridge to suspend-native channels,
# the tunnel module no longer needs an exclusion either.
#
# The scanned trees come from scripts/production-source-dirs.sh, which is shared
# with the #379 sensitive-logging gate. Do not hard-code a path list here: the
# two gates having separate hand-maintained lists is what caused #547.
#
# KDoc/comment lines beginning with "* " are permitted, so prose about
# runBlocking does not trip the gate.

set -euo pipefail

# ADDING A NEW GATE? It MUST ship a matching scripts/tests/<gate>-test.sh, wired
# into CI. The harness -- not the shared filter below -- is what catches an
# unanchored post-filter; see the header of scripts/lib/gate-filters.sh.
#
# Resolved from this script's own location rather than from the working
# directory, so the gate can be invoked by path from anywhere -- including from
# the throwaway repo its self-test builds.
gate_lib_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=scripts/lib/gate-filters.sh
source "$gate_lib_dir/lib/gate-filters.sh"

# Assigned, then cd'd, rather than `cd "$(git rev-parse ...)"`. Measured, so the
# reason is the real one and not the obvious guess: the masked form does NOT
# silently pass. `cd ""` is an error in bash, not a no-op, so a failing
# `git rev-parse` did already stop the script. What it stopped with was
# "<script>: line NN: cd: null directory" and exit 1 -- an error attributed to
# the wrong command, on a line whose real problem is that this is not a git work
# tree. As an assignment the failure is attributed to `git`, which has written
# its own "fatal: not a git repository" to stderr, and the script exits with
# git's status (128) instead of a `cd` diagnostic nobody can act on.
repo_root=$(git rev-parse --show-toplevel)
cd "$repo_root"

# Via a variable, not `mapfile < <(...)`: process substitution does not
# propagate its exit status, so a failing preflight would leave DIRS empty and
# this script would exit 0 having scanned nothing -- the exact silent-pass shape
# #547 is about. Command substitution under `set -e` aborts here instead.
dirs=$(bash scripts/production-source-dirs.sh)
mapfile -t DIRS <<<"$dirs"
[ "${#DIRS[@]}" -gt 0 ] || { echo "ERROR: no production source dirs to scan" >&2; exit 1; }

# No `2>/dev/null` and no blanket `|| true`: a grep that cannot read a tree must
# be a red build, not a silent pass. grep exits 1 for "no matches" (the good
# case) and >1 for a real error.
set +e
raw=$(grep -rn '\brunBlocking\b' --include='*.kt' "${DIRS[@]}")
status=$?
set -e
if [ "$status" -gt 1 ]; then
  echo "ERROR: grep failed with status $status (see stderr above)." >&2
  echo "The gate did not scan the tree; treat this as a failure, not a pass." >&2
  exit 1
fi

# Drop block-comment continuation lines (` * ...`) so prose about runBlocking does
# not fail the build. The anchoring lives in scripts/lib/gate-filters.sh, which
# documents why it is a CONTENT-field filter and what the unanchored form
# swallowed (#590, the same defect as #577 in the sibling sensitive-logging
# gate). `grep -v` exits 1 when it emits nothing, which for this gate is the good
# case, so the status is discarded -- via the same `set +e` bracket the detection
# grep above uses, rather than a trailing `|| true`, which would put a function in
# an `||` condition and turn set -e off inside it (SC2310).
set +e
matches=$(printf '%s\n' "$raw" | skip_comment_continuations)
set -e

if [ -n "$matches" ]; then
  echo "Found production runBlocking (issue #136 regression):"
  echo "$matches"
  echo
  echo "Make the caller suspend, or hoist the call into an existing coroutine"
  echo "scope. See .claude/rules/coroutines.md."
  exit 1
fi

echo "OK: no production runBlocking across ${#DIRS[@]} source sets"
