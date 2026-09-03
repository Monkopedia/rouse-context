#!/usr/bin/env bash
# Shared post-filters for this repo's grep-based CI gates (#597).
#
# Sourced, not executed. This example is the shape the two converted gates
# actually use, and it passes `scripts/check-shell-lint.sh` phase 1 as written --
# both of which matter, because the next gate will be a copy of one of those two
# and of this block:
#
#     gate_lib_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
#     # shellcheck source=scripts/lib/gate-filters.sh
#     source "$gate_lib_dir/lib/gate-filters.sh"
#
#     set +e
#     hits=$(git grep -nE "$pattern" -- "${paths[@]}" \
#              | skip_comment_continuations \
#              | exempt_path AuditDetailScreen.kt)
#     set -e
#
# Two details are deliberate rather than incidental.
#
# The library is resolved from the GATE's own location, not from the working
# directory: `$(git rev-parse --show-toplevel)` reads the enclosing repo, which
# is the wrong repo when the gate is invoked from the throwaway tree its
# self-test builds, and shellcheck flags the discarded status besides (SC2312).
#
# The pipeline sits inside a `set +e` bracket rather than ending in `|| true`.
# Both discard the status, and the status must be discarded -- see below -- but
# a FUNCTION in an `||` condition turns `set -e` off inside it, which
# `check-shell-lint.sh` reports as SC2310 and fails the build over.
#
# =============================================================================
# READ THIS FIRST IF YOU ARE ADDING A GATE
# =============================================================================
# Every `scripts/check-*.sh` MUST ship a matching `scripts/tests/<gate>-test.sh`
# and MUST have that self-test wired into CI. That requirement -- not this file --
# is the part that catches the bug.
#
# Be clear about what this library is and is not. It removes ONE way to get a
# post-filter wrong: re-deriving the anchor by hand. It cannot help a gate that
# does not call it, and nothing forces the next author to call it. What actually
# caught two of the three defects below was the self-test pattern: a throwaway
# git repo, the REAL gate run end-to-end, and red cases asserted red -- so the
# harness catches a hand-rolled filter whether or not it used this file. On
# #598's harness alone, four distinct wrong fixes were rejected, including the
# regex the issue itself had recommended; a shared helper would have caught none
# of them on its own merits.
#
# So: a new gate without a self-test is not finished, however it filters.
#
# =============================================================================
# WHY THE ANCHORS LOOK LIKE THIS
# =============================================================================
# `grep -n` / `git grep -n` emit `path:line:content`. A post-filter is always
# about exactly ONE of those three fields, and the recurring defect is applying
# a field-specific filter to the whole line: the pattern then also matches in a
# field it was never meant to see, and REAL violations drop silently out of the
# results while the gate reports clean.
#
# Three separate gates shipped that same defect -- and they were not three
# hand-rolls of one regex, they were two DIFFERENT anchor shapes, which is why
# each is a named function here rather than one shared pattern. Naming the field
# is the point: the bug is a category error, not a typo.
#
#   #577 / #588  check-no-sensitive-logging.sh      content field
#   #590 / #593  check-no-production-runblocking.sh content field
#   #594 / #598  check-no-manual-json.sh            path field
#
# Not every post-filter needs anchoring, and adding it where it is not needed is
# its own hazard. `check-no-manual-json.sh` also filters on the marker
# `// allow-manual-json`, which is safe unanchored because a path cannot contain
# `//`; its self-test asserts that, so a later "consistently anchor everything"
# sweep cannot quietly break it. Only 3 of this repo's `check-*.sh` gates have a
# filter of this shape at all -- `check-zombie-screens.sh` and
# `check-apk-distribution.sh` have none and deliberately do not source this file.
#
# Both functions read stdin and write stdout, so they compose as pipeline
# stages. Neither swallows `grep`'s exit status, so the CALLER must discard it:
# `grep -v` exits 1 when it emits nothing, which for a gate is the GOOD case,
# and under `set -euo pipefail` that would otherwise kill the gate on a clean
# tree. Both converted gates do that with the `set +e` bracket shown above --
# which is a change from the `|| true` they carried before this library existed,
# and the reason is SC2310, not taste.

# Drop lines whose CONTENT field is a block-comment continuation (` * ...`), so
# prose about the banned construct -- a KDoc paragraph, a pattern list written
# out in a comment -- does not fail the build.
#
# `^[^:]*:[0-9]+:` consumes the path and line fields, and `[^:]*` cannot cross
# the first colon, so `[[:space:]]*\*` is matched only at the START of the
# content. The unanchored form (`grep -v ':[[:space:]]*\*'`) also matched `: *`
# ANYWHERE -- inside a string literal, inside a trailing comment -- and threw
# away genuine violations that happened to contain it (#577, #590).
skip_comment_continuations() {
  grep -vE '^[^:]*:[0-9]+:[[:space:]]*\*'
}

# Drop lines whose PATH field is exactly <basename>, in any directory. For a
# whole-file allowlist entry ("this one file is exempt from this gate").
#
# `^([^:]*/)?` is an optional directory prefix that cannot cross the first colon,
# and the trailing `:[0-9]+:` pins that colon to the path/line separator, so the
# match is confined to the path field. Unanchored it also matched the content
# field, and then ANY file could evade the gate just by naming the exempt file in
# a comment or a string (#594).
#
# Requiring the basename to be EXACTLY <basename> matters as much as the field
# anchoring. `^[^:]*Foo\.kt:[0-9]+:` looks anchored and is not: `[^:]*` happily
# absorbs `some/dir/My`, so `MyFoo.kt` and `dir/NotFoo.kt` become exempt too.
# That precise regex was recommended in #594 and rejected by #598's harness.
#
# The argument is a bare basename, checked rather than trusted: the check is what
# makes escaping only `.` provably sufficient. A basename carrying an unescaped
# ERE metacharacter would silently widen the exemption, which is the class of bug
# this whole file exists to prevent. Misuse is reported on stdout as well as
# stderr and the input is passed through UNFILTERED, so the calling gate goes RED
# carrying the diagnostic instead of quietly exempting the wrong set of files.
exempt_path() {
  local base=${1-}
  if [[ ! "$base" =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]*$ ]]; then
    echo "gate-filters: exempt_path: '$base' is not a bare basename" >&2
    echo "gate-filters: exempt_path: '$base' is not a bare basename (gate not filtered)"
    cat
    return 0
  fi
  local escaped=${base//./\\.}
  grep -vE "^([^:]*/)?${escaped}:[0-9]+:"
}
