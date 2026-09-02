#!/usr/bin/env bash
# Prints one production Kotlin source-set directory per line, on stdout.
#
# Single source of truth for "which trees are production code". CI gates that
# scan production sources (the #136 runBlocking gate, the #379 sensitive-logging
# gate) MUST take their path list from here rather than hard-coding one.
#
# Why this exists (#547): the two gates in .github/workflows/ci.yml each carried
# their own hand-maintained list. They drifted. The runBlocking gate still named
# `health/src/main`, `outreach/src/main` and `usage/src/main` -- modules that no
# longer exist -- and never listed `integrations/src/main`, the 26-file tree the
# health module was moved into. `2>/dev/null` swallowed the resulting "No such
# file or directory" errors and `|| true` kept the step green, so for the whole
# life of that drift the gate reported "clean" for a tree it had never read.
#
# Two properties follow from that post-mortem:
#
#   1. Derive, don't enumerate. The list is computed from the tracked tree, so a
#      new module or a new product-flavor dir is gated the day it lands and a
#      renamed module cannot silently fall out of scope.
#   2. Fail loudly on anything stale. A path list that tolerates nonexistent
#      entries cannot distinguish "clean" from "not looked at". Every assertion
#      below turns a stale assumption into a red build instead of a quiet skip.
#
# Derivation rule: every tracked `<module>/src/<sourceSet>` directory whose
# source set is not a test source set, minus the exclusions below. Default is to
# include -- that is the safe polarity for a gate. If a tree does not belong,
# exclude it here with a reason.

set -euo pipefail

# Assigned, then used. Inside `cd "$(git rev-parse --show-toplevel)"` the
# status of `git rev-parse` was discarded and only `cd` decided what happened
# next -- and `cd ""` is a bash error whose message ("null directory") says
# nothing about the real cause. As an assignment the failure stops the script
# with git's own explanation, which is what a human reading a red CI log needs.
repo_root=$(git rev-parse --show-toplevel)
cd "$repo_root"

# Extended regexes matched against a full `<module>/src/<sourceSet>` path. Each
# entry MUST match at least one real source set; a pattern matching nothing is a
# stale exclusion and fails the preflight below.
EXCLUDE=(
  # Rust crate. `relay/src/*` are Rust modules, not Kotlin source sets.
  '^relay/src/'
  # Debug-build-only ADB test harness (TestCommandProvider/TestCommandReceiver).
  # Never compiled into a release build, and deliberately uses runBlocking.
  '^app/src/debug$'
)

# Trees that MUST survive derivation. This is a floor, not the list: it can only
# fail loudly, never narrow the scan. It exists so that a module rename -- the
# exact event that caused #547 -- breaks the build instead of quietly shrinking
# the gate's coverage.
REQUIRED=(
  app/src/main
  integrations/src/main
  core/mcp/src/jvmMain
)

die() {
  echo "ERROR: $*" >&2
  echo "  (see the header of scripts/production-source-dirs.sh)" >&2
  exit 1
}

# Every tracked source set, e.g. `app/src/main`, `core/mcp/src/jvmMain`.
all_source_sets=$(
  git ls-files \
    | sed -n 's#^\(.*\)/src/\([^/]*\)/.*#\1/src/\2#p' \
    | sort -u
)
[ -n "$all_source_sets" ] || die "found no <module>/src/<sourceSet> directories at all"

# Drop test source sets: `test`, `testFoss`, `jvmTest`, `androidTest`, ... The
# match is on the source-set component only, so `core/testfixtures/src/main` --
# a `main` source set under a test-support module -- is kept and scanned.
production=$(printf '%s\n' "$all_source_sets" | grep -viE '/src/[^/]*test[^/]*$' || true)
[ -n "$production" ] || die "every source set looked like a test source set"

for pattern in "${EXCLUDE[@]}"; do
  printf '%s\n' "$all_source_sets" | grep -qE "$pattern" \
    || die "stale exclusion: '$pattern' matches no source set. Remove it or fix it."
  production=$(printf '%s\n' "$production" | grep -vE "$pattern" || true)
done
[ -n "$production" ] || die "exclusions removed every production source set"

for required in "${REQUIRED[@]}"; do
  printf '%s\n' "$production" | grep -qxF "$required" \
    || die "required production tree '$required' is missing from the derived list. \
If it moved, update REQUIRED; if it was excluded, that exclusion is too broad."
done

# The bug this whole file exists to prevent: a path that is scanned but is not
# there. Derived paths come from the index, so a sparse or partial checkout is
# the way this can still happen. Refuse to report on a tree we cannot read.
while IFS= read -r dir; do
  [ -d "$dir" ] || die "derived production dir '$dir' does not exist on disk"
done <<<"$production"

printf '%s\n' "$production"
