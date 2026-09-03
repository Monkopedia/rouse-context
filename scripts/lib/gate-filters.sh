#!/usr/bin/env bash
# Shared post-filters for this repo's grep-based CI gates (#597).
#
# Sourced, not executed:
#
#     source "$(git rev-parse --show-toplevel)/scripts/lib/gate-filters.sh"
#     hits=$(git grep -nE "$pattern" -- "${paths[@]}" \
#              | skip_comment_continuations \
#              | exempt_path AuditDetailScreen.kt \
#              || true)
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
# stages. Neither swallows `grep`'s exit status: `grep -v` exits 1 when it emits
# nothing, which for a gate is the GOOD case, so the caller keeps the `|| true`
# on the pipeline as a whole exactly as before.

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
