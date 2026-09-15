#!/usr/bin/env bash
# Regression tests for scripts/check-gate-selftests.sh (#705).
#
# The gate this covers is the mechanical form of the rule "every gate ships a
# self-test", so shipping it without one of its own would be the joke writing
# itself. More usefully: it is a COVERAGE check, and a coverage check is the
# easiest kind to make vacuous -- discover nothing, compare nothing, report
# clean. #629's coverage gate did exactly that, proving comparisons happened
# rather than that they could detect anything. So the red cases below include
# every way this gate could look at an empty set and call it a pass.
#
# Each case builds a throwaway git repo and runs the REAL gate against it via
# its root argument. Nothing is planted in this repo.

set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
gate=$repo_root/scripts/check-gate-selftests.sh

sandbox=$(mktemp -d)
trap 'rm -rf "$sandbox"' EXIT

failures=0
out_file=$sandbox/gate-output.txt
box=$sandbox/repo
run_status=0
run_ok=0
has_verdict=
has_status=0

# `grep` over a FILE, never `printf ... | grep -q` -- see #716/#725: that
# pipeline cannot tell "no match" from "grep could not answer" from "grep
# matched early and `pipefail` reported the writer's SIGPIPE". No pipe, no
# writer, no ambiguity.
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
# A repo shaped like this one as far as the gate cares: one gate, its harness,
# and a workflow that runs the harness. Cases then break exactly one thing.
# ---------------------------------------------------------------------------
setup_box() {
  rm -rf "$box"
  mkdir -p "$box/scripts/tests" "$box/.github/workflows"
  git -C "$box" init -q .
  git -C "$box" config user.email t@example.com
  git -C "$box" config user.name test
  printf '#!/usr/bin/env bash\necho "OK: alpha"\n' > "$box/scripts/check-alpha.sh"
  printf '#!/usr/bin/env bash\necho "All check-alpha.sh tests passed"\n' \
    > "$box/scripts/tests/check-alpha-test.sh"
  cat > "$box/.github/workflows/ci.yml" <<'YML'
name: CI
on: [push]
jobs:
  lint:
    runs-on: ubuntu-latest
    steps:
      - name: "alpha gate self-test"
        run: bash scripts/tests/check-alpha-test.sh
YML
  git -C "$box" add -A
}

# run <want-exit> <label> [root]
run() {
  local want=$1 label=$2 where=${3:-$box}
  set +e
  bash "$gate" "$where" > "$out_file" 2>&1
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

# must_say <label> <fixed string> ... -- a red run has to NAME the gap. #678's
# gate exited 1 with zero bytes of output; that is a crash, not a finding.
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

# ===========================================================================
# Part 1 -- RED. The two gaps this gate exists to catch.
# ===========================================================================

# The #705 case itself: a gate with no harness. This is the shape `main`
# carried for check-apk-distribution.sh -- the FOSS-purity gate -- until the
# change that added this file.
setup_box
printf '#!/usr/bin/env bash\necho "OK: beta"\n' > "$box/scripts/check-beta.sh"
git -C "$box" add -A
run 1 'a gate with no self-test is caught'
must_say 'a gate with no self-test is caught' \
  'GATE SELF-TEST CHECK FAILED (issue #705)' \
  '1 gate(s) ship with no self-test:' \
  'scripts/check-beta.sh -> scripts/tests/check-beta-test.sh' \
  'NONE       check-beta'
must_not_say 'a gate with no self-test is caught' 'OK: all '

# The other half: a harness that exists but that no workflow runs. A self-test
# nobody executes is a file, not a gate -- the covered gate keeps passing CI
# while the thing that would notice a lie never runs.
setup_box
printf '#!/usr/bin/env bash\necho "OK: gamma"\n' > "$box/scripts/check-gamma.sh"
printf '#!/usr/bin/env bash\necho "ok"\n' > "$box/scripts/tests/check-gamma-test.sh"
git -C "$box" add -A
run 1 'a self-test no workflow runs is caught'
must_say 'a self-test no workflow runs is caught' \
  '1 self-test(s) are not invoked by any workflow:' \
  'scripts/tests/check-gamma-test.sh' \
  'UNWIRED    check-gamma-test.sh'

# Both gaps at once must both be reported. A gate that stops at the first turns
# a cleanup into N round trips.
setup_box
printf '#!/usr/bin/env bash\necho "OK: beta"\n' > "$box/scripts/check-beta.sh"
printf '#!/usr/bin/env bash\necho "OK: gamma"\n' > "$box/scripts/check-gamma.sh"
printf '#!/usr/bin/env bash\necho "ok"\n' > "$box/scripts/tests/check-gamma-test.sh"
git -C "$box" add -A
run 1 'both kinds of gap are reported together'
must_say 'both kinds of gap are reported together' \
  'scripts/check-beta.sh -> scripts/tests/check-beta-test.sh' \
  'scripts/tests/check-gamma-test.sh'

# An UNTRACKED harness does not count. It satisfies `[[ -f ]]` on the author's
# machine and does not exist in CI's checkout, and the whole point of this gate
# is what CI will find. Pinned because the obvious implementation gets this
# wrong in the direction that reports a pass.
setup_box
printf '#!/usr/bin/env bash\necho "OK: delta"\n' > "$box/scripts/check-delta.sh"
git -C "$box" add -A
printf '#!/usr/bin/env bash\necho "ok"\n' > "$box/scripts/tests/check-delta-test.sh"
run 1 'an untracked harness does not count'
must_say 'an untracked harness does not count' \
  'scripts/check-delta.sh -> scripts/tests/check-delta-test.sh'

# ===========================================================================
# Part 2 -- RED on the discovery itself. Every one of these is a way to look at
# nothing and call it clean, which is the failure this whole class of gate has
# shipped before (#547, #579, #629).
# ===========================================================================

empty_repo=$sandbox/empty-repo
rm -rf "$empty_repo"
mkdir -p "$empty_repo"
git -C "$empty_repo" init -q .
git -C "$empty_repo" config user.email t@example.com
git -C "$empty_repo" config user.name test
printf 'x\n' > "$empty_repo/README.md"
git -C "$empty_repo" add -A
run 1 'a repo with no gates fails rather than passing vacuously' "$empty_repo"
must_say 'a repo with no gates fails rather than passing vacuously' \
  'no CI gates found' \
  'vacuously'

# Gates but no harnesses at all. Caught by the discovery guard before the
# per-gate loop, so the message is about the empty set and not about one gate.
no_tests=$sandbox/no-tests
rm -rf "$no_tests"
mkdir -p "$no_tests/scripts" "$no_tests/.github/workflows"
git -C "$no_tests" init -q .
git -C "$no_tests" config user.email t@example.com
git -C "$no_tests" config user.name test
printf '#!/usr/bin/env bash\necho hi\n' > "$no_tests/scripts/check-alpha.sh"
printf 'name: CI\non: [push]\n' > "$no_tests/.github/workflows/ci.yml"
git -C "$no_tests" add -A
run 1 'a repo with no self-tests at all fails' "$no_tests"
must_say 'a repo with no self-tests at all fails' 'no gate self-tests found'

# No workflow files. Without this guard, every harness would be "unwired" --
# which is red, but for the wrong reason and with a misleading message. The
# guard says what is actually wrong.
no_wf=$sandbox/no-workflows
rm -rf "$no_wf"
mkdir -p "$no_wf/scripts/tests"
git -C "$no_wf" init -q .
git -C "$no_wf" config user.email t@example.com
git -C "$no_wf" config user.name test
printf '#!/usr/bin/env bash\necho hi\n' > "$no_wf/scripts/check-alpha.sh"
printf '#!/usr/bin/env bash\necho ok\n' > "$no_wf/scripts/tests/check-alpha-test.sh"
git -C "$no_wf" add -A
run 1 'a repo with no workflow files fails' "$no_wf"
must_say 'a repo with no workflow files fails' 'no workflow files found'

# Outside a work tree, `git ls-files` finds nothing and everything would pass.
not_a_repo=$sandbox/not-a-repo
rm -rf "$not_a_repo"
mkdir -p "$not_a_repo"
run 1 'a directory that is not a work tree fails' "$not_a_repo"
must_say 'a directory that is not a work tree fails' 'is not a git work tree'

run 1 'a root that does not exist fails' "$sandbox/nowhere"
must_say 'a root that does not exist fails' 'no such directory'

# ===========================================================================
# Part 3 -- GREEN. Including the boundaries of the name-based predicate, which
# are a deliberate choice and so are asserted rather than left to the comment
# that explains them.
# ===========================================================================

setup_box
run 0 'a fully covered repo passes'
must_say 'a fully covered repo passes' \
  'OK: all 1 gate(s) have a self-test' \
  'WIRED      check-alpha-test.sh'

# `scripts/backfill-relay-secrets.sh` is the real instance of this: not named
# `check-*`, not a gate, and nothing here forces it to have a self-test. The
# predicate is name-based on purpose (#658 raises the general objection); the
# miss direction is safe, so it is pinned as intended behaviour.
setup_box
printf '#!/usr/bin/env bash\necho "not a gate"\n' > "$box/scripts/backfill-relay-secrets.sh"
git -C "$box" add -A
run 0 'a non-check-* script is outside the gate set'

# The flip side of the same boundary, and the reason the wiring half is checked
# over ALL harnesses rather than only the ones belonging to a gate: a harness
# for a non-`check-` subject still has to be RUN. (`backfill-relay-secrets-test.sh`
# and `gate-filters-test.sh` are the live instances.)
setup_box
printf '#!/usr/bin/env bash\necho "not a gate"\n' > "$box/scripts/backfill-relay-secrets.sh"
printf '#!/usr/bin/env bash\necho ok\n' > "$box/scripts/tests/backfill-relay-secrets-test.sh"
git -C "$box" add -A
run 1 'a harness for a non-gate subject still has to be wired'
must_say 'a harness for a non-gate subject still has to be wired' \
  'scripts/tests/backfill-relay-secrets-test.sh'

setup_box
printf '#!/usr/bin/env bash\necho "not a gate"\n' > "$box/scripts/backfill-relay-secrets.sh"
printf '#!/usr/bin/env bash\necho ok\n' > "$box/scripts/tests/backfill-relay-secrets-test.sh"
cat >> "$box/.github/workflows/ci.yml" <<'YML'
      - name: "backfill self-test"
        run: bash scripts/tests/backfill-relay-secrets-test.sh
YML
git -C "$box" add -A
run 0 'a wired harness for a non-gate subject passes'

# Workflow discovery must cover `.yaml` as well as `.yml`; a harness wired only
# from a `.yaml` file is wired.
setup_box
printf '#!/usr/bin/env bash\necho "OK: eps"\n' > "$box/scripts/check-epsilon.sh"
printf '#!/usr/bin/env bash\necho ok\n' > "$box/scripts/tests/check-epsilon-test.sh"
cat > "$box/.github/workflows/extra.yaml" <<'YML'
name: Extra
on: [push]
jobs:
  x:
    runs-on: ubuntu-latest
    steps:
      - run: bash scripts/tests/check-epsilon-test.sh
YML
git -C "$box" add -A
run 0 'a harness wired from a .yaml workflow counts'

# ===========================================================================
# Part 4 -- the real repo. The gate's answer about the tree it actually guards,
# asserted here as well as in its own CI step: a suite that only ever runs
# against fixtures can drift into agreeing with a gate that has stopped
# describing this repo.
# ===========================================================================
run 0 'this repository passes' "$repo_root"
must_say 'this repository passes' 'OK: all '

if [ "$failures" -gt 0 ]; then
  echo "$failures test(s) failed"
  exit 1
fi
echo "All check-gate-selftests.sh tests passed"
