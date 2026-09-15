#!/usr/bin/env bash
# Fails if a CI gate ships without a self-test, or if a self-test is not wired
# into a workflow (#705).
#
# WHY THIS EXISTS
# ---------------
# The rule was already written down. `scripts/lib/gate-filters.sh` puts it
# first, in a block headed READ THIS FIRST IF YOU ARE ADDING A GATE:
#
#   Every `scripts/check-*.sh` MUST ship a matching
#   `scripts/tests/<gate>-test.sh` and MUST have that self-test wired into CI.
#   That requirement -- not this file -- is the part that catches the bug.
#
# It was a comment, and on `main` it was false: `check-apk-distribution.sh` --
# the FOSS-purity gate `release.yml` runs as the whole of #548's answer -- and
# `check-zombie-screens.sh` had no harness at all. Both were fixed in the change
# that added this file; this is what stops the tenth gate arriving without one.
#
# The argument for mechanising it is the repo's own history. A gate that has
# only ever been observed passing is indistinguishable from one that cannot
# fail, and this tree has shipped that four times:
#
#   #577/#579  a post-filter silently swallowing real violations, reporting clean
#   #657       a stubbed `awk` making check-zombie-screens.sh print OK over a zombie
#   #678       check-roborazzi-step-order.sh exiting 1 with zero output -- a crash
#              reading as a finding -- on the two inputs it existed to catch
#   #686       backfill-relay-secrets.sh exiting 0 after a failed secret push
#
# Every one was caught by a self-test, or by a human noticing what a self-test
# would have caught. None was caught by the gate itself running in CI.
#
# WHAT COUNTS AS A GATE -- a name-based predicate, stated rather than inherited
# ---------------------------------------------------------------------------
# The set is `git ls-files 'scripts/check-*.sh'`: a NAME-based predicate for
# what is really a content-based class, which is the objection #658 raises
# against the shellcheck gate's `*.sh`. It is accepted here deliberately, and
# the reasons are worth stating so the next reader does not have to guess:
#
#   * the convention is real and uniform -- all nine gates on `main` when this
#     landed are named `check-<subject>.sh`, and each is invoked from a workflow
#     under that name;
#   * proximity enforces it -- a new gate is written next to nine files that
#     follow the convention, and its CI step is added next to theirs;
#   * the failure direction is safe. A script that IS a gate but is not named
#     `check-*` escapes this check; it does not cause a false red. The cost of
#     the miss is the status quo ante, not a broken build.
#
# `scripts/backfill-relay-secrets.sh` is deliberately OUTSIDE the set: it is not
# a gate, it writes the production relay secrets. It happens to have a harness
# (#686, after it exited 0 over a failed push) and that harness is wired into
# CI, so the second half of this check covers it -- which is the right split.
# Being outside the gate set means nothing forces it to HAVE a self-test; having
# one means the self-test must be RUN.
#
# WHAT IS CHECKED
# ---------------
#   1. every `scripts/check-*.sh` has `scripts/tests/<name>-test.sh`;
#   2. every `scripts/tests/*-test.sh` is named in a `.github/workflows/*.yml`.
#
# (2) is the half that matters more than it looks. A harness nobody runs is a
# file, not a gate, and it rots silently: the gate it covers keeps passing CI
# while the thing that would notice a lie sits unexecuted. It also covers this
# script's own harness, and every harness for a non-`check-` subject.
#
# HOW IT FAILS
# ------------
# Loudly, by name, and never green by absence: zero gates, zero harnesses or
# zero workflow files is a FAILURE, because each of those is a way for this
# check to look at nothing and call it clean -- the #547/#579 shape it exists to
# prevent. Discovery is `git ls-files`, so it refuses to run outside a work
# tree for the same reason `check-shell-lint.sh` does.

set -euo pipefail

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

# list_tracked <what> <pathspec...> -- tracked files matching a pathspec, into
# `tracked`.
#
# `git ls-files` is read with its status CHECKED, and the emptiness test happens
# BEFORE the split. Both matter. A process substitution reports no status at
# all, so a failed `git` would look like an empty repo (the #657 shape); and
# `mapfile -t a <<<""` yields a ONE-element array holding the empty string,
# which would sail past a `${#a[@]} -gt 0` test and then be used as a filename.
tracked=()
list_tracked() {
  local what=$1 raw status
  shift
  set +e
  raw=$(git ls-files -- "$@")
  status=$?
  set -e
  if [[ "$status" -ne 0 ]]; then
    echo "FAIL: 'git ls-files -- $*' exited $status looking for $what." >&2
    echo "      Nothing was discovered, so this is a failure and not a pass." >&2
    exit 1
  fi
  if [[ -z "$raw" ]]; then
    echo "FAIL: no $what found (pathspec: $*)." >&2
    echo "      An empty discovery would make every check below vacuously" >&2
    echo "      true, so it is reported as a failure." >&2
    exit 1
  fi
  mapfile -t tracked <<<"$raw"
}

list_tracked 'CI gates' 'scripts/check-*.sh'
gates=("${tracked[@]}")
list_tracked 'gate self-tests' 'scripts/tests/*-test.sh'
harnesses=("${tracked[@]}")
list_tracked 'workflow files' '.github/workflows/*.yml' '.github/workflows/*.yaml'
workflows=("${tracked[@]}")

echo "Checking gate self-test coverage"
echo "  gates       : ${#gates[@]} (scripts/check-*.sh)"
echo "  self-tests  : ${#harnesses[@]} (scripts/tests/*-test.sh)"
echo "  workflows   : ${#workflows[@]}"
echo

# Membership is tested against the TRACKED list, not the filesystem: an
# untracked harness satisfies `[[ -f ]]` locally and does not exist in CI's
# checkout, which is the one place this check's answer matters. Same trade
# `check-shell-lint.sh` makes, and the same reason `git add` before running
# either of them locally is worth the habit.
harness_list=$'\n'
for harness in "${harnesses[@]}"; do
  harness_list+="$harness"$'\n'
done

missing_harness=()
for gate in "${gates[@]}"; do
  name=$(basename "$gate" .sh)
  harness="scripts/tests/$name-test.sh"
  if [[ "$harness_list" == *$'\n'"$harness"$'\n'* ]]; then
    printf '  %-10s %-40s %s\n' 'HAS' "$name" "$harness"
  else
    printf '  %-10s %-40s %s\n' 'NONE' "$name" "(expected $harness)"
    missing_harness+=("$gate -> $harness")
  fi
done
echo

# Which workflow, if any, runs this harness. The search is for the harness's
# own path, which is how every wired step in this repo spells it
# (`run: bash scripts/tests/<name>-test.sh`).
unwired=()
for harness in "${harnesses[@]}"; do
  set +e
  hits=$(grep -lF -e "$harness" -- "${workflows[@]}")
  status=$?
  set -e
  if [[ "$status" -gt 1 ]]; then
    echo "FAIL: grep exited $status looking for '$harness' in the workflow files." >&2
    echo "      The search did not complete; this is a failure, not a pass." >&2
    exit 1
  fi
  if [[ "$status" -eq 0 ]]; then
    printf '  %-10s %-40s %s\n' 'WIRED' "$(basename "$harness")" "${hits//$'\n'/ }"
  else
    printf '  %-10s %-40s %s\n' 'UNWIRED' "$(basename "$harness")" '(no workflow runs it)'
    unwired+=("$harness")
  fi
done
echo

if [[ "${#missing_harness[@]}" -gt 0 || "${#unwired[@]}" -gt 0 ]]; then
  echo "GATE SELF-TEST CHECK FAILED (issue #705)" >&2
  echo >&2
  if [[ "${#missing_harness[@]}" -gt 0 ]]; then
    echo "  ${#missing_harness[@]} gate(s) ship with no self-test:" >&2
    printf '    %s\n' "${missing_harness[@]}" >&2
    echo >&2
  fi
  if [[ "${#unwired[@]}" -gt 0 ]]; then
    echo "  ${#unwired[@]} self-test(s) are not invoked by any workflow:" >&2
    printf '    %s\n' "${unwired[@]}" >&2
    echo >&2
  fi
  echo "  A gate that has only ever been observed passing is indistinguishable" >&2
  echo "  from one that cannot fail. Write the harness -- red case FIRST, so the" >&2
  echo "  gate is demonstrated capable of failing -- and add a step that runs it." >&2
  echo "  scripts/tests/check-zombie-screens-test.sh is a short example; see also" >&2
  echo "  the rule in scripts/lib/gate-filters.sh." >&2
  exit 1
fi

echo "OK: all ${#gates[@]} gate(s) have a self-test, and all ${#harnesses[@]} self-test(s) are wired into a workflow."
