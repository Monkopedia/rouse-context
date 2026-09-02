#!/usr/bin/env bash
# Regression tests for scripts/check-shell-lint.sh (#592, #659).
#
# The gate is a linter wrapper, which is precisely the shape that rots into a
# no-op: point it at a path that no longer exists, hand `shellcheck` an empty
# argument list, and it prints nothing and exits 0 -- indistinguishable from a
# clean tree. #629's Roborazzi coverage control passed at `0 == 0` for exactly
# that reason, so this asserts the gate FIRES and asserts it cannot pass
# vacuously.
#
# Same harness shape as #577/#590/#594/#628: a throwaway git work tree, the REAL
# gate run end-to-end against it.
#
# #659 added a second phase -- actionlint over the inline `run:` shell in
# .github/workflows/ -- and it needs the same treatment for a sharper reason.
# actionlint's shellcheck integration is silent when it is not working: with a
# PATH that has no shellcheck on it, actionlint prints nothing and exits 0,
# which is indistinguishable from a clean tree. Measured, not assumed. So every workflow-phase case below
# comes in PAIRS -- a bad input that must go red, and a good one that must stay
# green -- because a positive control on its own cannot tell "the gate works"
# from "the gate reports everything", and a negative control on its own cannot
# tell "the gate works" from "the gate reports nothing".

set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
gate="$repo_root/scripts/check-shell-lint.sh"

sandbox=$(mktemp -d)
trap 'rm -rf "$sandbox"' EXIT

failures=0

# Reset the sandbox to an empty, tracked-nothing git work tree.
reset_sandbox() {
  rm -rf "${sandbox:?}/repo"
  mkdir -p "$sandbox/repo"
  git -C "$sandbox/repo" init -q
}

# write <relative-path> <<'EOF' ... : create a script and stage it, so
# `git ls-files` sees it.
write_script() {
  local rel=$1
  mkdir -p "$(dirname "$sandbox/repo/$rel")"
  cat > "$sandbox/repo/$rel"
  chmod +x "$sandbox/repo/$rel"
  git -C "$sandbox/repo" add -- "$rel"
}

# write_workflow <relative-path> <<'EOF' ... : create a workflow file under
# .github/workflows/ and stage it. The workflow phase discovers with the same
# `git ls-files` as the script phase, so an unstaged file is invisible to it.
write_workflow() {
  local rel=$1
  mkdir -p "$sandbox/repo/.github/workflows"
  cat > "$sandbox/repo/.github/workflows/$rel"
  git -C "$sandbox/repo" add -- ".github/workflows/$rel"
}

# A workflow with nothing wrong with it. Every case that needs to REACH the
# workflow phase and pass it uses this; every case that needs to reach the
# workflow phase at all needs at least one workflow present, because an empty
# workflow set is itself a red case.
write_clean_workflow() {
  write_workflow "${1:-ok.yml}" <<'EOF'
name: ok
on: [workflow_dispatch]
jobs:
  fine:
    runs-on: ubuntu-latest
    steps:
      - name: quoted, so nothing to report
        run: |
          target="${HOME}/out"
          echo "$target"
EOF
}

# A script with nothing wrong with it, so a workflow-phase case is not held up
# by the script phase that runs first.
write_clean_script() {
  write_script scripts/clean.sh <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
echo ok
EOF
}

# expect <exit-status> <name> [<substring the output must contain>]...
#
# The substrings are the point. A gate that exits non-zero without naming what
# is wrong is the #628 failure mode (exit 1, no output), so a red case here has
# to SAY the file and the rule code, not merely go red.
expect() {
  local want=$1 name=$2
  shift 2
  local out status must
  set +e
  out=$(bash "$gate" "$sandbox/repo" 2>&1)
  status=$?
  set -e
  if [[ "$status" -ne "$want" ]]; then
    echo "FAIL: $name -- expected exit $want, got $status"
    printf '%s\n' "$out" | sed 's/^/    /'
    failures=$((failures + 1))
    return
  fi
  for must in "$@"; do
    if ! printf '%s\n' "$out" | grep -qF -- "$must"; then
      echo "FAIL: $name -- exited $status but never said '$must'"
      printf '%s\n' "$out" | sed 's/^/    /'
      failures=$((failures + 1))
      return
    fi
  done
  echo "ok: $name (exit $status)"
}

# --- green -------------------------------------------------------------------
reset_sandbox
write_script scripts/clean.sh <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
target=${1:-.}
echo "checking ${target}"
EOF
write_clean_workflow
expect 0 "a clean tracked script passes" "OK: shellcheck clean" "scripts/clean.sh"

# --- the gate must FIRE ------------------------------------------------------
# SC2155 is the masked-return-value check that IS on by default: `local x=$(cmd)`
# discards cmd's exit status because `local` itself succeeds. This is the
# closest default-severity relative of the load-bearing-exit-status shape these
# gates are built out of.
reset_sandbox
write_script scripts/bad.sh <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
index_of() {
  local hit=$(grep -nF -- "$1" /dev/null | head -1)
  echo "$hit"
}
index_of x
EOF
expect 1 "a masked return value is caught, named by file and rule" \
  "scripts/bad.sh" "SC2155"

# SC2086: an unquoted expansion word-splits. Every gate in scripts/ passes
# directory lists and grep patterns through variables, so this is the live risk.
reset_sandbox
write_script scripts/bad.sh <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
dirs=$(cat /dev/null)
for d in $dirs; do
  grep -r foo $d
done
EOF
expect 1 "an unquoted expansion is caught" "scripts/bad.sh" "SC2086"

# --- the two OPTIONAL checks the gate turns on (#657) ------------------------
#
# `-o check-extra-masked-returns` (SC2312) and `-o check-set-e-suppressed`
# (SC2310/SC2311) are the ONLY optional checks enabled, and they are the two
# that matter for this repo: every gate here is a `grep` pipeline under
# `set -euo pipefail`, where `grep` exiting 1 on no match is the normal case and
# whether that status is masked decides whether the gate prints its finding or
# dies before it. All 27 live sites were triaged one at a time in #657; these
# cases are what stops the next one arriving unexamined.
#
# The cases below are a MATCHED SET and the set is the point. Both bad inputs
# are clean at the default severity the gate used before #657 -- measured, 0
# findings each -- so a red here is evidence the FLAG is on and not merely that
# the linter ran. The green case carries the same two shapes written the way the
# rules ask for; a red on IT would mean the checks match everything, which a
# positive control on its own cannot distinguish (#629 shipped an empty-regex
# probe that matched everything and read as confirmation).

# SC2312, phase 1: a command whose exit status is discarded inside a command
# substitution. This is the #628 mechanism seen from the other side.
reset_sandbox
write_script scripts/bad.sh <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
echo "matches: $(grep -c . /dev/null)"
EOF
# A clean workflow too, so the only thing that can turn this red is the finding
# above rather than an empty-workflow-set failure from the phase after it.
write_clean_workflow
expect 1 "a masked return inside a command substitution is caught (SC2312)" \
  "scripts/bad.sh" "SC2312"

# SC2310, phase 1: a function invoked in a condition, which turns `set -e` OFF
# inside it for that call. check-zombie-screens.sh does this deliberately and
# says so; anything that does it by accident now goes red.
reset_sandbox
write_script scripts/bad.sh <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
has_marker() {
  grep -q marker /dev/null
}
if has_marker; then
  echo found
fi
EOF
write_clean_workflow
expect 1 "a function invoked in a condition is caught (SC2310)" \
  "scripts/bad.sh" "SC2310"

# The GREEN half of the pair. The same two shapes with their statuses handled:
# an explicit `|| true` on the substitution, and the function invoked as a
# statement rather than as a condition. This must stay SILENT -- without it, a
# gate that reported every command substitution in the repo would pass both
# cases above and look tested.
reset_sandbox
write_script scripts/ok.sh <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
report_markers() {
  grep -c marker /dev/null || true
}
n=$(grep -c . /dev/null || true)
echo "matches: $n"
report_markers
EOF
write_clean_workflow
expect 0 "the same shapes with their statuses handled stay green" \
  "OK: shellcheck clean" "scripts/ok.sh"

# SC2312, phase 2: the SAME options must reach the inline `run:` shell. This is
# the half most exposed to a silent regression -- the options travel to
# actionlint as a `-shellcheck "<binary> -o ..."` STRING, so dropping them there
# would leave phase 1 catching every `*.sh` while the workflow shell quietly
# drifted to a weaker standard, with the gate still green. Measured: this
# workflow is clean through actionlint at the default severity and red only with
# the options attached.
reset_sandbox
write_clean_script
write_workflow bad.yml <<'EOF'
name: masked return in inline shell
on: [workflow_dispatch]
jobs:
  bad:
    runs-on: ubuntu-latest
    steps:
      - name: a command substitution that throws away grep's status
        run: |
          set -euo pipefail
          echo "count: $(grep -c . /dev/null)"
EOF
expect 1 "the optional checks reach the inline run: shell too (SC2312)" \
  "bad.yml" "SC2312"

# ...and its green twin, so the phase-2 assertion above cannot be satisfied by a
# linter that reports on every `run:` block it sees.
reset_sandbox
write_clean_script
write_workflow ok.yml <<'EOF'
name: handled status in inline shell
on: [workflow_dispatch]
jobs:
  fine:
    runs-on: ubuntu-latest
    steps:
      - name: the same shape with the status handled
        run: |
          set -euo pipefail
          n=$(grep -c . /dev/null || true)
          echo "count: $n"
EOF
expect 0 "the same inline shape with its status handled stays green" \
  "OK: actionlint clean"

# --- coverage is recursive, not just scripts/ --------------------------------
# The self-tests under scripts/tests/ are the things that caught #577, #590 and
# both #628 bugs, and they are shell too. A `scripts/*.sh` glob would miss them
# entirely, so a violation one directory down must still go red.
reset_sandbox
write_script scripts/clean.sh <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
echo ok
EOF
write_script scripts/tests/nested-test.sh <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [ $? -ne 0 ]; then echo bad; fi
EOF
expect 1 "a violation under scripts/tests/ is covered too" \
  "scripts/tests/nested-test.sh" "SC2181"

# Any depth, not a hardcoded two levels: deploy/ and relay/scripts/ hold shell
# too, and a future directory must not need this gate edited to be covered.
reset_sandbox
write_script deploy/deep/nested/thing.sh <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [ $? -ne 0 ]; then echo bad; fi
EOF
expect 1 "a violation at arbitrary depth is covered" \
  "deploy/deep/nested/thing.sh" "SC2181"

# --- the gate must not pass vacuously ----------------------------------------
# THE case this test exists for. `shellcheck` with no file arguments reads stdin
# and exits 0, so a discovery that matches nothing would report success over an
# empty set -- a gate that passes everything, forever, silently.
reset_sandbox
expect 1 "an empty work tree goes RED, not vacuously green" \
  "no tracked *.sh files"

# Untracked-only is the same hazard wearing a different hat: the files exist on
# disk, so a human glancing at the directory would say the gate had work to do.
reset_sandbox
mkdir -p "$sandbox/repo/scripts"
cat > "$sandbox/repo/scripts/untracked.sh" <<'EOF'
#!/usr/bin/env bash
if [ $? -ne 0 ]; then echo bad; fi
EOF
expect 1 "an untracked-only tree goes RED rather than reporting clean" \
  "no tracked *.sh files"

# Outside a git work tree, `git ls-files` yields nothing; without the explicit
# guard that would look identical to a clean tree.
rm -rf "${sandbox:?}/repo"
mkdir -p "$sandbox/repo"
expect 1 "a non-git directory goes RED" "is not a git work tree"

# A path that does not exist at all -- the "someone moved scripts/" case.
rm -rf "${sandbox:?}/repo"
expect 1 "a missing root goes RED" "no such directory"
mkdir -p "$sandbox/repo"

# --- phase 2: the inline run: shell in the workflow files (#659) -------------
#
# GREEN CONTROL, first and deliberately. Everything below asserts the workflow
# phase goes red on bad input; this asserts it does not go red on good input.
# Without it, a phase that reported every workflow as broken would pass every
# red case here and look fully tested.
reset_sandbox
write_clean_script
write_clean_workflow
expect 0 "a clean workflow passes the inline-shell phase" \
  "OK: actionlint clean" "inline run: block(s)"

# THE case this phase exists for: an unquoted expansion inside a run: block.
# The assertion is not "exit 1" -- it is that the message NAMES the workflow,
# the job and the rule code, because a red build that does not say what is wrong
# is the #628 failure mode (exit 1, no output).
reset_sandbox
write_clean_script
write_workflow deploy.yml <<'EOF'
name: deploy
on: [workflow_dispatch]
jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - name: unquoted expansion
        run: |
          target=${HOME}/out
          mkdir -p $target
EOF
expect 1 "an unquoted expansion in a run: block is caught, named by workflow, job and rule" \
  ".github/workflows/deploy.yml" "job 'deploy'" "SC2086"

# The half extract-and-lint cannot do, and the reason this phase is actionlint
# rather than a YAML-parse-and-shellcheck loop. shellcheck cannot parse `${{ }}`
# -- fed the raw text it emits SC2296/SC1083 parse errors -- so extracting the
# block to a temp file forces a choice between a spurious parse failure and
# skipping the block, and the skip list is the #579 shape. actionlint
# substitutes a placeholder for the expression and hands the result off to
# be linted like any other script, so a block that CONTAINS an expression is
# still fully covered. That is what this asserts: the SC2086 is on a different
# line of the same block.
reset_sandbox
write_clean_script
write_workflow interp.yml <<'EOF'
name: interp
on: [workflow_dispatch]
jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - name: an expression, and an unquoted expansion beside it
        run: |
          ref=${{ github.ref_name }}
          echo "building $ref"
          mkdir -p $GITHUB_WORKSPACE/out
EOF
expect 1 "a run: block containing a \${{ }} expression is still linted, not skipped" \
  ".github/workflows/interp.yml" "job 'publish'" "SC2086"

# ...and the injection shape itself, which is the reason the expression blocks
# were worth not skipping. shellcheck cannot see this one at all -- after
# substitution the interpolated value IS a literal, and shellcheck correctly
# says nothing about a literal. actionlint carries a purpose-built rule for it.
# Measured: with the expression removed this workflow is clean, so the finding
# is the interpolation and not something incidental.
reset_sandbox
write_clean_script
write_workflow inject.yml <<'EOF'
name: inject
on: [pull_request]
jobs:
  greet:
    runs-on: ubuntu-latest
    steps:
      - name: attacker-controlled text straight into a shell
        run: |
          echo "PR: ${{ github.event.pull_request.title }}"
EOF
expect 1 "an untrusted expression interpolated into a run: block is caught" \
  ".github/workflows/inject.yml" "job 'greet'" "potentially untrusted"

# The green half of that pair: the same workflow with the interpolation moved to
# an env var -- the fix the finding recommends -- must stay silent. Without
# this, a phase that flagged every `${{ }}` would pass the case above.
reset_sandbox
write_clean_script
write_workflow inject.yml <<'EOF'
name: inject
on: [pull_request]
jobs:
  greet:
    runs-on: ubuntu-latest
    steps:
      - name: same value, passed through the environment
        env:
          TITLE: ${{ github.event.pull_request.title }}
        run: |
          echo "PR: $TITLE"
EOF
expect 0 "the recommended env-var form of the same workflow stays green" \
  "OK: actionlint clean"

# --- phase 2 must not pass vacuously -----------------------------------------
# actionlint handed no files lints nothing and exits 0 -- the same shape as
# `shellcheck` with an empty argument list, one directory over.
reset_sandbox
write_clean_script
expect 1 "a tree with no workflow files goes RED rather than vacuously green" \
  "no tracked workflow files"

# And a workflow set that parses but contains no shell at all: actionlint has
# nothing to hand shellcheck, reports clean, and exits 0. Covering zero blocks
# is not a clean tree.
reset_sandbox
write_clean_script
write_workflow noshell.yml <<'EOF'
name: no shell anywhere
on: [workflow_dispatch]
jobs:
  checkout-only:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
EOF
expect 1 "workflows with no run: block at all go RED, not clean" \
  "not one run: block"

# THE silent-skip case, and the reason the gate carries a built-in control.
# actionlint does not fail, warn, or exit non-zero when its shellcheck
# integration is unavailable -- it prints nothing and exits 0, so every workflow
# in the repo "passes". Proven here with a stub actionlint that reports the
# pinned version and then behaves exactly that way: the gate must notice that
# its own known-bad control workflow came back clean, and refuse to continue.
reset_sandbox
write_clean_script
write_workflow deploy.yml <<'EOF'
name: deploy
on: [workflow_dispatch]
jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - name: unquoted expansion the stub will not report
        run: |
          target=${HOME}/out
          mkdir -p $target
EOF
mkdir -p "$sandbox/stubbin"
cat > "$sandbox/stubbin/actionlint" <<'EOF'
#!/usr/bin/env bash
# Stands in for an actionlint whose shellcheck integration is not wired up:
# right version, no findings, exit 0 -- on a file that definitely has one.
if [[ "${1:-}" == "--version" ]]; then
  echo "1.7.12"
  exit 0
fi
exit 0
EOF
chmod +x "$sandbox/stubbin/actionlint"
saved_path=$PATH
PATH="$sandbox/stubbin:$PATH"
expect 1 "an actionlint whose shellcheck integration is dead goes RED" \
  "shellcheck integration is not live"
PATH=$saved_path

# The pin, same argument as shellcheck's: a version that silently drops a check
# weakens this phase with no signal.
reset_sandbox
write_clean_script
write_clean_workflow
mkdir -p "$sandbox/stalebin"
cat > "$sandbox/stalebin/actionlint" <<'EOF'
#!/usr/bin/env bash
echo "0.0.1-fake"
exit 0
EOF
chmod +x "$sandbox/stalebin/actionlint"
saved_path=$PATH
PATH="$sandbox/stalebin:$PATH"
expect 1 "a mismatched actionlint version goes RED" "actionlint 0.0.1-fake, expected"
PATH=$saved_path

# ...and no actionlint at all must fail rather than skip the phase. The minimal
# PATH carries what the gate and harness need (bash, git, sed, grep, awk,
# mktemp, cat, chmod, rm, mkdir) plus the real shellcheck, so the script phase
# still passes and the run reaches the actionlint check.
reset_sandbox
write_clean_script
write_clean_workflow
mkdir -p "$sandbox/noactionlint"
for tool in bash git sed grep awk mktemp cat chmod rm mkdir printf shellcheck; do
  tool_path=$(command -v "$tool" || true)
  [[ -n "$tool_path" ]] && ln -sf "$tool_path" "$sandbox/noactionlint/$tool"
done
saved_path=$PATH
# Same deliberate narrowing as the missing-shellcheck case below, and SC2123
# flags it for the same reason it is wrong everywhere else.
# shellcheck disable=SC2123
PATH="$sandbox/noactionlint"
export PATH
expect 1 "a missing actionlint goes RED rather than being skipped" \
  "actionlint is not installed"
PATH=$saved_path
export PATH

# --- the version pin is enforced ---------------------------------------------
# The pin is the only thing standing between this gate and whatever shellcheck
# the runner image happens to ship. If it were advisory, a release that DROPPED
# a check would weaken the gate with no signal at all. Proven by putting a fake
# `shellcheck` reporting a different version first on PATH.
reset_sandbox
write_script scripts/clean.sh <<'EOF'
#!/usr/bin/env bash
echo ok
EOF
mkdir -p "$sandbox/fakebin"
cat > "$sandbox/fakebin/shellcheck" <<'EOF'
#!/usr/bin/env bash
echo "ShellCheck - shell script analysis tool"
echo "version: 0.0.1-fake"
exit 0
EOF
chmod +x "$sandbox/fakebin/shellcheck"
saved_path=$PATH
PATH="$sandbox/fakebin:$PATH"
expect 1 "a mismatched shellcheck version goes RED" "expected"
PATH=$saved_path

# ...and with no shellcheck on PATH at all, it must fail rather than skip. The
# minimal PATH below carries exactly what the gate and this harness need to run
# (bash, git, sed, grep) and nothing else, so `command -v shellcheck` genuinely
# finds nothing.
mkdir -p "$sandbox/minbin"
for tool in bash git sed grep; do
  # Status checked, not substituted into `ln`. Masked, a tool this harness
  # cannot find becomes `ln -sf "" ...` -- a dangling symlink -- and the case
  # below then fails for the wrong reason, two screens away from the cause.
  if ! tool_path=$(command -v "$tool"); then
    echo "FAIL: this harness needs '$tool' on PATH and cannot find it." >&2
    exit 1
  fi
  ln -sf "$tool_path" "$sandbox/minbin/$tool"
done
saved_path=$PATH
# Suppressed, and the suppression IS the case: SC2123
# warns when PATH is REPLACED by a single directory rather than extended,
# because that is normally a typo. Here it is exactly what is being tested --
# a PATH deliberately narrowed until `command -v shellcheck` finds nothing.
# Restored on the next line but one.
# shellcheck disable=SC2123
PATH="$sandbox/minbin"
export PATH
expect 1 "a missing shellcheck goes RED rather than being skipped" \
  "shellcheck is not installed"
PATH=$saved_path
export PATH

if [[ "$failures" -ne 0 ]]; then
  echo
  echo "$failures case(s) failed: scripts/check-shell-lint.sh is not behaving as"
  echo "its own tests describe. Fix the gate, not these tests."
  exit 1
fi

echo
echo "All check-shell-lint.sh cases behaved as expected."
