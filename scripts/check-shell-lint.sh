#!/usr/bin/env bash
# Lints every line of shell this repo owns -- the tracked `*.sh` files (#592)
# AND the shell that lives inline in `run:` blocks in the workflow files (#659).
#
# WHY THIS EXISTS
# ---------------
# Seven of this repo's CI gates ARE shell scripts, and five of them ship with
# their own self-tests -- also shell. Nothing linted any of them. Every agent
# that touched one ran `shellcheck` locally and reported it clean, which is a
# convention, not a gate: the next change is one forgotten local run away from
# landing unlinted, and these scripts decide whether a build ships.
#
# WHAT IT DOES AND DOES NOT CATCH -- measured, not assumed
# --------------------------------------------------------
# It would NOT have caught #577/#590/#594/#597: those are unanchored-filter
# LOGIC defects in entirely valid shell, and no linter sees them.
#
# It would also NOT have caught the #628 defect, contrary to the obvious guess.
# Both #628 gates shipped the same bug -- `grep` exits 1 on no match, and under
# `set -euo pipefail` that propagated out of a command substitution and killed
# the script BEFORE it printed its finding (exit 1, no output, reading as a
# crash rather than as the finding it was). That was probed against this exact
# shape at default severity AND with `-o check-extra-masked-returns,
# check-set-e-suppressed`: shellcheck 0.11.0 reports nothing on it. It cannot,
# because dying on a non-zero status is what `set -e` is FOR; only the author
# knows the empty result was meant to be a finding. Those two bugs were caught
# by their self-tests asserting the red cases actually SAY what is wrong, and
# that remains the mechanism that catches them.
#
# What this gate does catch is the neighbouring class the gates are genuinely
# exposed to, and at default severity: SC2155 (`local x=$(cmd)` masking a return
# value), SC2086 (unquoted expansion word-splitting a path or a grep pattern),
# SC2181 (`$?` checked indirectly), SC2015 (`a && b || c` read as if/else),
# SC2034 (an accumulator assigned and never read -- one of those was live in
# check-zombie-screens.sh and is fixed in the change that added this gate), and
# SC1073 (a comment line beginning `# shellcheck ...` silently parsed as a
# directive, which this file tripped on its own first run).
#
# WHAT IS COVERED
# ---------------
# TWO surfaces, in two phases, both discovered rather than listed.
#
# Phase 1 -- every tracked `*.sh` in the repo. A hand-maintained
# file list is the thing that rots (#547 drifted exactly that way), and an
# exemption list is the thing that quietly grows until the gate matches nothing
# (#579). There are no exemptions. `gradlew` is a vendored, upstream-owned
# Gradle wrapper and is excluded by virtue of not being named `*.sh`; that is
# deliberate, not an oversight.
#
# Discovery is `git ls-files`, so gitignored build output is never scanned. The
# trade is that an UNTRACKED new script is invisible to this gate locally --
# harmless in CI, where the checkout is fully tracked by construction, and the
# reason `git add` before running this locally is worth the habit.
#
# Phase 2 -- every tracked `.github/workflows/*.y{a,}ml`, via `actionlint`,
# which hands each `run:` block to the SAME pinned shellcheck. Measured on the
# tree this landed against: 7 workflow files, 70 `run:` blocks, 372 lines of
# shell (71 and 383 once this phase's own actionlint install step is counted) --
# a surface two-thirds the size of phase 1's, and until #659 none of it was
# linted, including the eight-line pinned-shellcheck install block that #592
# itself added.
#
# It was not hypothetical. On `main` at 0493dfec this phase reported five live
# findings: 4x SC2086 in `relay-cert-renew.yml` job `renew` (`>> $GITHUB_OUTPUT`
# unquoted, four times) and 1x SC2012 in `release.yml` job `build` (`ls` piped
# into `tr` where a glob does the job). Both are fixed in the change that added
# this phase. That is the ACME-renewal workflow and the signed-release build --
# the two where a quoting bug is worse than a red build, by the same argument
# #592 used to keep `backfill-relay-secrets.sh` in scope.
#
# WHY actionlint AND NOT EXTRACT-AND-LINT
# ---------------------------------------
# The cheaper shape is to parse the YAML here, write each `run:` body to a temp
# file with a synthesized shebang, and run shellcheck on that. It was measured
# too, as the cross-check on the five findings above, and it works -- for 62 of
# the 70 blocks. The other 8 contain `${{ }}` expressions, which are not shell
# and which shellcheck cannot parse, so extract-and-lint has to skip them.
#
# Skipping them is the wrong 8 to skip. An expression interpolated into an
# unquoted position is the standard GitHub Actions injection shape, so those
# blocks are simultaneously the harder half and the more dangerous half; a gate
# that silently drops them buys much less than its green tick suggests, and the
# skip list is the #579 shape -- an exemption set that only grows. actionlint
# substitutes the expressions for parseable placeholders before it calls
# out to shellcheck, so all 70 blocks are linted and there is no exemption list
# at all.
# It also brings workflow-schema checking along, which found nothing extra on
# this tree -- so adopting it costs zero suppressions.
#
# Coverage was measured, not inferred from that argument: a known-bad canary was
# injected into each block in turn and this phase's linter was required to
# report it. 70 of 70 on `main` as of 0493dfec, 71 of 71 on the tree this
# landed as -- the eight expression-carrying blocks included, no exclusions.
# The same probe with the canary removed reports 0 of 71, so the result is the
# gate seeing the canary and not the probe matching everything.
#
# One measured nuance worth writing down rather than discovering later. After
# substitution an interpolated expression IS a literal, and shellcheck does not
# raise SC2086 on a variable holding a literal with no spaces or globs -- it
# behaves identically on plain `foo=bar; mkdir -p $foo`, with no actionlint
# involved. So SC2086 is not what covers `run: echo ${{ github.event.*.title }}`.
# actionlint's own `expression` rule is, and it is the better instrument: it
# names the untrusted context and points at the env-var fix. Both halves have a
# red case and a green case in the self-test.
#
# THE SILENT-SKIP HAZARD, AND THE CONTROL THAT CATCHES IT
# -------------------------------------------------------
# actionlint's shellcheck integration is best-effort: with no `shellcheck` on
# PATH it does not warn, it does not exit non-zero -- it prints nothing and
# exits 0. Measured, not assumed: the known-bad control workflow this script
# builds reports SC2086 with shellcheck on PATH and reports NOTHING, exit 0,
# with a PATH that omits it. That is a gate that passes everything, which is the
# failure this repo keeps re-learning (#579, #629).
#
# So phase 2 runs that control on EVERY invocation, before it lints anything
# real, and fails if the control does not go red. A green run of this gate is
# therefore evidence that the shellcheck integration was live on that run, not
# merely that actionlint executed.
#
# SEVERITY
# --------
# The default severity (`style`) -- the strictest of the four levels, reporting
# error + warning + info + style. Measured on the tree this landed against,
# across all 18 tracked scripts: 0 findings at `error`, and 1 at `warning`,
# `info` and `style` alike (the dead `EXIT` in check-zombie-screens.sh, fixed in
# the same change). The strictest level lands clean, so there is no reason to
# accept a weaker one, and no `-S` flag is passed.
#
# The OPTIONAL checks (`-o all`) are NOT enabled: 501 findings, 485 of them
# SC2250 (`${var}` bracing) and SC2292 (`[[` over `[`) -- pure style, a
# whole-tree rewrite for no defect-detection gain. The remaining 15 are
# SC2310/SC2312 masked-return notes; those are on-topic for gates whose `grep`
# exit status is load-bearing, but each needs its own judgement call (some of
# the masking is deliberate), so they are tracked as follow-up rather than
# either blocking this gate or being silenced wholesale.
#
# VERSION
# -------
# The version is asserted, not assumed. New shellcheck releases add checks (a
# loud red) but can also relax or drop them (a SILENT weakening), and
# `ubuntu-latest` is a moving target that will roll to a new image on GitHub's
# schedule, not ours. CI installs the pinned build; this assertion is what makes
# removing that install step fail loudly instead of silently handing the gate
# back to whatever the runner image happens to ship.
#
# Not a theoretical gap: measured on the runner at the time this landed, the
# `ubuntu-latest` image ships shellcheck 0.9.0 -- two feature releases behind
# the 0.11.0 every finding count above was measured with. "The runner already
# has it" is true and not sufficient. (And note the line break: a comment line
# that STARTS with the word after `# ` being `shellcheck` is parsed as a
# directive and fails the file with SC1072/SC1073. This header tripped that
# twice while being written; the gate caught it both times.)
#
# Usage: check-shell-lint.sh [repo-root]

set -euo pipefail

# Bump deliberately, with the finding-count delta recorded in the PR. See the
# pinned install in .github/workflows/ci.yml, which must move in lockstep.
EXPECTED_SHELLCHECK_VERSION=0.11.0
# Same reasoning, same lockstep, for the workflow phase. See the pinned install
# in .github/workflows/ci.yml.
EXPECTED_ACTIONLINT_VERSION=1.7.12

root=${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}

if [[ ! -d "$root" ]]; then
  echo "FAIL: no such directory: $root" >&2
  exit 1
fi
cd "$root"

if ! git rev-parse --git-dir >/dev/null 2>&1; then
  echo "FAIL: $root is not a git work tree." >&2
  echo "      Discovery is 'git ls-files', so outside a work tree this gate" >&2
  echo "      would find nothing and 'pass'. It fails instead." >&2
  exit 1
fi

if ! command -v shellcheck >/dev/null 2>&1; then
  echo "FAIL: shellcheck is not installed." >&2
  echo "      Install v$EXPECTED_SHELLCHECK_VERSION -- see the pinned install in" >&2
  echo "      .github/workflows/ci.yml, or your distro's package." >&2
  exit 1
fi

actual_version=$(shellcheck --version | sed -n 's/^version: //p')
if [[ "$actual_version" != "$EXPECTED_SHELLCHECK_VERSION" ]]; then
  echo "FAIL: shellcheck $actual_version, expected $EXPECTED_SHELLCHECK_VERSION." >&2
  echo "      Version drift is not cosmetic here: a release that DROPS a check" >&2
  echo "      weakens this gate silently. If the new version is intended, bump" >&2
  echo "      EXPECTED_SHELLCHECK_VERSION here and the pinned install in" >&2
  echo "      .github/workflows/ci.yml together, and record the finding-count" >&2
  echo "      delta in the PR." >&2
  exit 1
fi

if ! command -v actionlint >/dev/null 2>&1; then
  echo "FAIL: actionlint is not installed." >&2
  echo "      Phase 2 below lints the inline run: shell in the workflow files" >&2
  echo "      (#659). Install v$EXPECTED_ACTIONLINT_VERSION -- see the pinned install in" >&2
  echo "      .github/workflows/ci.yml." >&2
  exit 1
fi

actionlint_version=$(actionlint --version | head -1)
if [[ "$actionlint_version" != "$EXPECTED_ACTIONLINT_VERSION" ]]; then
  echo "FAIL: actionlint $actionlint_version, expected $EXPECTED_ACTIONLINT_VERSION." >&2
  echo "      Same reasoning as the shellcheck pin above. Bump" >&2
  echo "      EXPECTED_ACTIONLINT_VERSION here and the pinned install in" >&2
  echo "      .github/workflows/ci.yml together, and record the finding-count" >&2
  echo "      delta in the PR." >&2
  exit 1
fi

# Resolved once and passed explicitly to actionlint, so the binary phase 2 uses
# is provably the one phase 1 just version-asserted rather than whatever a
# later PATH lookup happens to find.
shellcheck_bin=$(command -v shellcheck)

# `git ls-files -z` + `mapfile -d ''` rather than a glob: NUL-delimited so a
# path with a space cannot split, and recursive so scripts/tests/ and any future
# directory are covered without this list being edited.
files=()
mapfile -d '' -t files < <(git ls-files -z -- '*.sh')

# A gate that finds nothing to check must go RED. `shellcheck` over an empty
# argument list reads stdin and exits 0, and a `scripts/*.sh` glob that matches
# nothing expands to a literal that "just" errors -- both of which are how a
# gate rots into one that passes everything (#579). Asserted, so it cannot.
if [[ ${#files[@]} -eq 0 ]]; then
  echo "FAIL: no tracked *.sh files found under $root." >&2
  echo "      This gate has nothing to check, which is a broken gate, not a" >&2
  echo "      clean tree. Either the scripts moved or discovery is wrong." >&2
  exit 1
fi

echo "=== phase 1: tracked *.sh ==="
echo "shellcheck $actual_version over ${#files[@]} tracked shell script(s):"
printf '  %s\n' "${files[@]}"
echo

# No `-S`: the default severity is the strictest, and `-f gcc` puts the file,
# line and rule code on one grep-able line so a CI log names what to fix.
shellcheck -f gcc -- "${files[@]}"

echo "OK: shellcheck clean across ${#files[@]} shell script(s)"

# =============================================================================
# Phase 2 -- the shell that lives INLINE in the workflow files (#659).
# =============================================================================

echo
echo "=== phase 2: inline run: shell in .github/workflows/ ==="

workflows=()
mapfile -d '' -t workflows < <(
  git ls-files -z -- '.github/workflows/*.yml' '.github/workflows/*.yaml'
)

# Same assertion as the empty-*.sh one above, for the same reason: actionlint
# handed no files at all lints nothing and exits 0.
if [[ ${#workflows[@]} -eq 0 ]]; then
  echo "FAIL: no tracked workflow files under $root/.github/workflows/." >&2
  echo "      This phase has nothing to lint, which is a broken gate rather" >&2
  echo "      than a clean tree." >&2
  exit 1
fi

# A REPORTED count, not the discovery predicate -- actionlint does the real YAML
# parsing. It exists because `exit 0` prints identically for 70 linted blocks
# and for zero, so a green run has to say how much it looked at. Cross-checked
# against a proper YAML parse on the tree this landed against: both say 70.
run_blocks=0
for wf in "${workflows[@]}"; do
  # `grep -c` exits 1 when the count is zero, and under `set -e` that would kill
  # this script mid-count -- the #628 shape exactly. Swallowed with `|| true`,
  # then the captured value is range-checked, so a grep that failed for a REAL
  # reason cannot quietly pass itself off as "this file has no run: blocks".
  n=$(grep -cE '^[[:space:]]*(-[[:space:]]+)?run:([[:space:]]|$)' "$wf" || true)
  if [[ ! "$n" =~ ^[0-9]+$ ]]; then
    echo "FAIL: could not count run: blocks in $wf (grep said '$n')." >&2
    exit 1
  fi
  run_blocks=$((run_blocks + n))
done

if [[ "$run_blocks" -eq 0 ]]; then
  echo "FAIL: ${#workflows[@]} workflow file(s) but not one run: block." >&2
  echo "      actionlint would report clean over that, which would be a gate" >&2
  echo "      covering nothing. Either the workflows changed shape or the" >&2
  echo "      count above is wrong; both need a human." >&2
  exit 1
fi

# --- the live positive control ----------------------------------------------
# actionlint's shellcheck integration is best-effort: with no shellcheck on
# PATH it prints nothing and exits 0. So before linting anything real, lint a
# workflow that is KNOWN to be bad and require it to go red. If this control is
# silent, the gate below cannot be trusted to be doing anything.
control_root=$(mktemp -d)
trap 'rm -rf "$control_root"' EXIT
mkdir -p "$control_root/.github/workflows"
cat > "$control_root/.github/workflows/control.yml" <<'CONTROL'
name: shellcheck integration control
on: [workflow_dispatch]
jobs:
  control:
    runs-on: ubuntu-latest
    steps:
      - name: deliberately unquoted expansion
        run: |
          target=${HOME}
          echo $target
CONTROL

control_out=$(
  cd "$control_root" &&
    actionlint -oneline -shellcheck "$shellcheck_bin" \
      .github/workflows/control.yml 2>&1 || true
)
if ! printf '%s\n' "$control_out" | grep -q 'SC2086'; then
  echo "FAIL: actionlint ran but its shellcheck integration is not live." >&2
  echo "      A control workflow containing 'echo \$target' -- an unquoted" >&2
  echo "      expansion, SC2086 -- produced no SC2086 finding. actionlint" >&2
  echo "      does not warn when it cannot find shellcheck; it reports clean" >&2
  echo "      and exits 0, so without this control the phase below would" >&2
  echo "      'pass' every workflow in the repo." >&2
  echo "      actionlint said:" >&2
  printf '        %s\n' "${control_out:-<nothing>}" >&2
  exit 1
fi

# --- job attribution for the report -----------------------------------------
# actionlint reports file:line:col and the rule code but never the job name, and
# a CI log that says "relay-cert-renew.yml:18" is meaningfully worse than one
# that says "job renew". This maps a line back to its enclosing `jobs:` key.
#
# It is a REPORTING aid and nothing else -- actionlint alone decides pass/fail,
# so the worst a wrong answer here can do is mislabel a finding that is already
# going red. That is why a textual scan is acceptable here and would not be as
# the discovery predicate.
job_for_line() {
  awk -v target="$2" '
    !injobs && /^jobs:[[:space:]]*(#.*)?$/ { injobs = 1; next }
    injobs && /^[^[:space:]#]/ { injobs = 0 }
    injobs && /^  [A-Za-z0-9_.-]+:[[:space:]]*(#.*)?$/ {
      name = $0
      sub(/^[[:space:]]+/, "", name)
      sub(/:.*$/, "", name)
      c++; starts[c] = NR; names[c] = name
    }
    END {
      found = ""
      for (i = 1; i <= c; i++) if (starts[i] <= target) found = names[i]
      print found
    }
  ' "$1"
}

echo "actionlint $actionlint_version + shellcheck $actual_version over" \
  "${#workflows[@]} workflow file(s), $run_blocks inline run: block(s):"
printf '  %s\n' "${workflows[@]}"
echo

# Captured, not piped: gating on the status of a pipeline gates on its LAST
# command, and the annotation loop below is exactly such a pipeline.
set +e
workflow_out=$(actionlint -oneline -shellcheck "$shellcheck_bin" -- "${workflows[@]}" 2>&1)
workflow_status=$?
set -e

if [[ -n "$workflow_out" ]]; then
  while IFS= read -r finding; do
    [[ -n "$finding" ]] || continue
    where=${finding%%: *}
    what=${finding#*: }
    wf_file=${where%%:*}
    wf_line=${where#*:}
    wf_line=${wf_line%%:*}
    job=""
    if [[ -f "$wf_file" && "$wf_line" =~ ^[0-9]+$ ]]; then
      job=$(job_for_line "$wf_file" "$wf_line")
    fi
    if [[ -n "$job" ]]; then
      echo "$where: job '$job': $what"
    else
      echo "$finding"
    fi
  done <<< "$workflow_out"
fi

if [[ "$workflow_status" -ne 0 || -n "$workflow_out" ]]; then
  echo
  echo "FAIL: actionlint found issue(s) in the inline run: shell above." >&2
  exit 1
fi

echo "OK: actionlint clean across ${#workflows[@]} workflow file(s)," \
  "$run_blocks inline run: block(s)"
