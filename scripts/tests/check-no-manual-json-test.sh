#!/usr/bin/env bash
# Regression tests for scripts/check-no-manual-json.sh (#594).
#
# The gate's AuditDetailScreen.kt exemption is a PATH allowlist that was applied
# unanchored to the whole of `git grep -n`'s `file:line:content` output, so it
# also matched the content field: a violation in any file could evade the gate by
# naming the exempt file in a comment or string. A green run of the gate proves
# nothing about that, so every case below is asserted against a planted probe,
# with the red cases required to name the offending file and line.
#
# Third instance of the class behind #577/#588 (sensitive-logging gate) and
# #590/#593 (runBlocking gate); same harness shape — build a throwaway git repo
# and run the REAL script end-to-end rather than re-implementing its regex.

# The Kotlin probes below are literal source text: `$id` and `"""` must reach the
# gate unexpanded, so single quotes are deliberate throughout.
# shellcheck disable=SC2016

set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
gate=scripts/check-no-manual-json.sh

sandbox=$(mktemp -d)
trap 'rm -rf "$sandbox"' EXIT

failures=0

# The gate greps `*/src/main/*.kt` over TRACKED files, so the sandbox needs a git
# repo with the probes staged — an untracked probe is invisible to `git grep`.
setup_sandbox() {
  cd "$sandbox"
  git init -q .
  git config user.email t@example.com
  git config user.name test
  mkdir -p scripts app/src/main
  cp "$repo_root/$gate" scripts/
  echo 'package p' > app/src/main/Main.kt
  git add -A
}

# expect <exit-status> <name> <probe-path> <probe-file-content>
expect() {
  local want=$1 name=$2 probe=$3 content=$4 out status
  mkdir -p "$sandbox/$(dirname "$probe")"
  printf '%s\n' "$content" > "$sandbox/$probe"
  (cd "$sandbox" && git add -A)
  set +e
  out=$(cd "$sandbox" && bash "$gate" 2>&1)
  status=$?
  set -e
  rm -f "$sandbox/$probe"
  (cd "$sandbox" && git add -A)

  if [ "$status" -ne "$want" ]; then
    echo "FAIL: $name -- expected exit $want, got $status"
    printf '%s\n' "$out" | sed 's/^/    /'
    failures=$((failures + 1))
    return
  fi
  # A red run must name the offending file and line, not just exit nonzero.
  if [ "$want" -ne 0 ] && ! printf '%s\n' "$out" | grep -qE "$probe:[0-9]+:"; then
    echo "FAIL: $name -- exited $status but did not name $probe with a line number"
    printf '%s\n' "$out" | sed 's/^/    /'
    failures=$((failures + 1))
    return
  fi
  echo "ok: $name (exit $status)"
}

setup_sandbox

# The #594 case: a real violation in a non-exempt file that merely mentions the
# allowlisted filename. Unanchored, the path filter matched that mention in the
# content field and dropped the hit, so the gate exited 0 on a genuine violation.
expect 1 'violation mentioning AuditDetailScreen.kt in a comment is caught' \
  app/src/main/Probe.kt \
  'val body = """{"id": "$id"}""" // shape mirrors AuditDetailScreen.kt'

expect 1 'violation mentioning AuditDetailScreen.kt in a string is caught' \
  app/src/main/Probe.kt \
  'val body = """{"src": "AuditDetailScreen.kt", "id": "$id"}"""'

# Ordinary detection still works (control for the two above).
expect 1 'violation with no mention is caught' \
  app/src/main/Probe.kt \
  'val body = """{"id": "$id"}"""'

# What the path allowlist is actually FOR: @Preview fixtures in the real
# AuditDetailScreen.kt. A "fix" that simply deletes the filter passes the red
# cases above and fails here.
expect 0 'violation inside AuditDetailScreen.kt is still allowlisted' \
  app/src/main/java/com/rousecontext/app/ui/screens/AuditDetailScreen.kt \
  'val body = """{"days":7,"metric":"steps"}"""'

# The anchor requires the basename to be exactly AuditDetailScreen.kt, so files
# whose names merely contain it are NOT exempt. (Such files would normally live
# outside src/main; planted here because the path regex is what is under test.)
expect 1 'AuditDetailScreenTest.kt is not covered by the allowlist' \
  app/src/main/java/com/rousecontext/app/ui/screens/AuditDetailScreenTest.kt \
  'val body = """{"id": "$id"}"""'

expect 1 'MyAuditDetailScreen.kt is not covered by the allowlist' \
  app/src/main/java/com/rousecontext/app/ui/screens/MyAuditDetailScreen.kt \
  'val body = """{"id": "$id"}"""'

# The sibling content-field filter. It is safe unanchored (a path cannot contain
# `//`) and is deliberately left alone by #594 — asserted so a later sweep that
# "consistently anchors" both filters cannot break it silently.
expect 0 'allow-manual-json marker line is skipped' \
  app/src/main/Probe.kt \
  'val body = """{"id": "$id"}""" // allow-manual-json: preview fixture'

# Nothing planted at all.
expect 0 'clean source set passes' \
  app/src/main/Clean.kt \
  'val body = Json.encodeToString(payload)'

if [ "$failures" -gt 0 ]; then
  echo "$failures test(s) failed"
  exit 1
fi
echo "All check-no-manual-json.sh tests passed"
