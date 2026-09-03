#!/usr/bin/env bash
# Tests for scripts/lib/gate-filters.sh (#597).
#
# Three gates shipped the same defect -- a post-filter meant for ONE field of
# `grep`'s `path:line:content` output, applied unanchored to the whole line
# (#577, #590, #594). Those anchors now live in one shared library, which makes
# a mistake in them three times as expensive: every gate that sources it
# inherits it. So this file asserts both directions for both functions -- the
# line that MUST be filtered, and the line that must NOT be -- because a filter
# that drops everything and a filter that drops nothing are both green if you
# only test one side.
#
# This does not replace the per-gate harnesses. Those run the REAL gate over a
# throwaway repo and are what actually caught the three defects; a gate that
# hand-rolls its filter is caught there and is invisible here.

set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
# shellcheck source=scripts/lib/gate-filters.sh
source "$repo_root/scripts/lib/gate-filters.sh"

failures=0

# kept <name> <line> [args...] -- the line must SURVIVE the filter named by the
# remaining arguments. This is the direction that matters most: a line that is
# wrongly dropped here is a violation the gate reports as clean.
kept() {
  local name=$1 line=$2
  shift 2
  local out
  set +e
  out=$(printf '%s\n' "$line" | "$@")
  set -e
  if [ "$out" != "$line" ]; then
    echo "FAIL: $name -- line was dropped (or altered) but must be kept"
    printf '    in:  %s\n    out: %s\n' "$line" "${out:-<nothing>}"
    failures=$((failures + 1))
    return
  fi
  echo "ok: $name (kept)"
}

# dropped <name> <line> [args...] -- the line must be REMOVED by the filter.
dropped() {
  local name=$1 line=$2
  shift 2
  local out
  set +e
  out=$(printf '%s\n' "$line" | "$@")
  set -e
  if [ -n "$out" ]; then
    echo "FAIL: $name -- line survived the filter but must be dropped"
    printf '    out: %s\n' "$out"
    failures=$((failures + 1))
    return
  fi
  echo "ok: $name (dropped)"
}

# --- skip_comment_continuations: a CONTENT-field filter ----------------------

dropped 'KDoc continuation is dropped' \
  'app/src/main/A.kt:12: * Callers must not wrap this in runBlocking.' \
  skip_comment_continuations

dropped 'continuation with no leading space is dropped' \
  'app/src/main/A.kt:12:* prose' \
  skip_comment_continuations

dropped 'continuation with deep indentation is dropped' \
  'app/src/main/A.kt:3:      *   prose' \
  skip_comment_continuations

# The #577/#590 direction. Unanchored, `: *` anywhere in the line matched, so
# these real violations were silently discarded and the gates reported clean.
kept 'violation with ": *" inside a string literal survives' \
  'app/src/main/A.kt:12:fun go() = runBlocking { println("total: *done") }' \
  skip_comment_continuations

kept 'violation with ": *" in a trailing comment survives' \
  'app/src/main/A.kt:12:fun go() = runBlocking { work() } // note: *temporary*' \
  skip_comment_continuations

kept 'ordinary violation survives' \
  'app/src/main/A.kt:12:fun go() = runBlocking { work() }' \
  skip_comment_continuations

kept 'a "*" later in the content does not count as a continuation' \
  'app/src/main/A.kt:12:val n = a * b' \
  skip_comment_continuations

# A path is never a continuation, whatever it is called.
kept 'a path with a leading star-ish name survives' \
  'app/src/main/Star.kt:12:val n = 1' \
  skip_comment_continuations

# --- exempt_path: a PATH-field filter ----------------------------------------

dropped 'exempt file in a directory is dropped' \
  'app/src/main/java/com/x/AuditDetailScreen.kt:5:val body = """{}"""' \
  exempt_path AuditDetailScreen.kt

dropped 'exempt file at the repo root is dropped' \
  'AuditDetailScreen.kt:5:val body = """{}"""' \
  exempt_path AuditDetailScreen.kt

# The #594 direction: the exemption must not reach the content field, or any
# file can leave the gate just by naming the exempt file.
kept 'another file that mentions the exempt name in a comment survives' \
  'app/src/main/Probe.kt:5:val body = """{}""" // shape mirrors AuditDetailScreen.kt' \
  exempt_path AuditDetailScreen.kt

kept 'another file that mentions the exempt name in a string survives' \
  'app/src/main/Probe.kt:5:val body = """{"src": "AuditDetailScreen.kt"}"""' \
  exempt_path AuditDetailScreen.kt

# The basename must match EXACTLY. `^[^:]*AuditDetailScreen\.kt:[0-9]+:` looks
# anchored to the path field and is not -- `[^:]*` absorbs `.../My` -- which is
# the regex #594 recommended and #598's harness rejected.
kept 'a longer name ending in the exempt name survives' \
  'app/src/main/MyAuditDetailScreen.kt:5:val body = """{}"""' \
  exempt_path AuditDetailScreen.kt

kept 'a longer name starting with the exempt name survives' \
  'app/src/main/AuditDetailScreenTest.kt:5:val body = """{}"""' \
  exempt_path AuditDetailScreen.kt

# The `.` in a basename is escaped, so it is a literal dot and not "any char".
kept 'a dot in the basename is not a wildcard' \
  'app/src/main/AuditDetailScreenXkt:5:val body = """{}"""' \
  exempt_path AuditDetailScreen.kt

# A line number is required between the path and the content, so a path-shaped
# prefix in the content field cannot be mistaken for the path field.
kept 'the exempt name followed by a non-numeric field survives' \
  'app/src/main/Probe.kt:5:see AuditDetailScreen.kt:notaline: for the fixture' \
  exempt_path AuditDetailScreen.kt

# --- exempt_path misuse ------------------------------------------------------
# A basename carrying an ERE metacharacter would silently widen the exemption.
# The guard makes that a RED gate instead: input passes through unfiltered, and
# the diagnostic goes to stdout so the caller's `[ -n "$hits" ]` fires.

misuse() {
  local name=$1 line=$2 arg=$3 out
  set +e
  out=$(printf '%s\n' "$line" | exempt_path "$arg" 2>/dev/null)
  set -e
  if ! printf '%s\n' "$out" | grep -q 'not a bare basename'; then
    echo "FAIL: $name -- no diagnostic on stdout, so the gate would stay green"
    printf '    out: %s\n' "${out:-<nothing>}"
    failures=$((failures + 1))
    return
  fi
  if ! printf '%s\n' "$out" | grep -qF -- "$line"; then
    echo "FAIL: $name -- input was filtered instead of passed through"
    printf '    out: %s\n' "${out:-<nothing>}"
    failures=$((failures + 1))
    return
  fi
  echo "ok: $name (diagnostic on stdout, input passed through)"
}

misuse 'a glob in the basename is refused' \
  'app/src/main/AuditDetailScreen.kt:5:x' 'Audit*.kt'

misuse 'a path rather than a basename is refused' \
  'app/src/main/AuditDetailScreen.kt:5:x' 'app/src/main/AuditDetailScreen.kt'

misuse 'an empty basename is refused' \
  'app/src/main/AuditDetailScreen.kt:5:x' ''

# --- composition -------------------------------------------------------------
# The gates chain these as pipeline stages, so assert the chain, not just the
# stages: a violation must survive both filters and a doubly-exempt line must
# not.
set +e
composed=$(printf '%s\n' \
  'app/src/main/A.kt:1: * prose mentioning AuditDetailScreen.kt' \
  'app/src/main/AuditDetailScreen.kt:2:val body = """{}"""' \
  'app/src/main/A.kt:3:val body = """{}""" // like AuditDetailScreen.kt: *see above*' \
  | skip_comment_continuations | exempt_path AuditDetailScreen.kt)
set -e

if [ "$composed" = 'app/src/main/A.kt:3:val body = """{}""" // like AuditDetailScreen.kt: *see above*' ]; then
  echo "ok: chained filters keep exactly the real violation"
else
  echo "FAIL: chained filters -- unexpected result"
  printf '    out: %s\n' "${composed:-<nothing>}"
  failures=$((failures + 1))
fi

if [ "$failures" -gt 0 ]; then
  echo "$failures test(s) failed"
  exit 1
fi
echo "All scripts/lib/gate-filters.sh tests passed"
