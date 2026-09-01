#!/usr/bin/env bash
# Runs shellcheck over every tracked shell script in the repo (issue #592).
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
# Every tracked `*.sh` in the repo, discovered -- not listed. A hand-maintained
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

echo "shellcheck $actual_version over ${#files[@]} tracked shell script(s):"
printf '  %s\n' "${files[@]}"
echo

# No `-S`: the default severity is the strictest, and `-f gcc` puts the file,
# line and rule code on one grep-able line so a CI log names what to fix.
shellcheck -f gcc -- "${files[@]}"

echo "OK: shellcheck clean across ${#files[@]} shell script(s)"
