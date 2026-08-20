#!/usr/bin/env bash
# Regression tests for scripts/check-no-sensitive-logging.sh (#577).
#
# The gate reported clean while a real violation was being swallowed by its
# comment-continuation post-filter, so a green run of the gate itself proves
# nothing: this class of bug is only visible with a demonstrated-red control.
#
# The gate scans whatever `scripts/production-source-dirs.sh` derives from the
# enclosing repo, so the tests run it against a throwaway repo built here rather
# than planting probe files in the real tree.

# The Kotlin probes below are literal source text: `$token` and friends must
# reach the gate unexpanded, so single quotes are deliberate throughout.
# shellcheck disable=SC2016

set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
gate=scripts/check-no-sensitive-logging.sh

sandbox=$(mktemp -d)
trap 'rm -rf "$sandbox"' EXIT

failures=0

# A repo shaped like the real one as far as production-source-dirs.sh cares:
# every REQUIRED tree present, and one file matching each EXCLUDE pattern so the
# stale-exclusion preflight passes.
setup_sandbox() {
  cd "$sandbox"
  git init -q .
  git config user.email t@example.com
  git config user.name test
  mkdir -p scripts app/src/main app/src/debug integrations/src/main \
    core/mcp/src/jvmMain relay/src/tls
  cp "$repo_root/$gate" "$repo_root/scripts/production-source-dirs.sh" scripts/
  echo 'package p' > app/src/main/Main.kt
  echo 'package p' > app/src/debug/Debug.kt
  echo 'package p' > integrations/src/main/Main.kt
  echo 'package p' > core/mcp/src/jvmMain/Main.kt
  echo '// rust' > relay/src/tls/mod.rs
  git add -A
}

# expect <exit-status> <name> <probe-file-content>
expect() {
  local want=$1 name=$2 content=$3
  local probe=app/src/main/Probe.kt out status
  printf '%s\n' "$content" > "$sandbox/$probe"
  set +e
  out=$(cd "$sandbox" && bash "$gate" 2>&1)
  status=$?
  set -e
  rm -f "$sandbox/$probe"

  if [ "$status" -ne "$want" ]; then
    echo "FAIL: $name -- expected exit $want, got $status"
    printf '%s\n' "$out" | sed 's/^/    /'
    failures=$((failures + 1))
    return
  fi
  # A red run must name the offending file and line, not just exit nonzero.
  if [ "$want" -ne 0 ] && ! printf '%s\n' "$out" | grep -q "$probe:"; then
    echo "FAIL: $name -- exited $status but did not name $probe"
    printf '%s\n' "$out" | sed 's/^/    /'
    failures=$((failures + 1))
    return
  fi
  echo "ok: $name (exit $status)"
}

setup_sandbox

# The #577 case: a real violation whose log string contains `: *`. The
# unanchored post-filter matched that sequence in grep's `file:line:content`
# output and dropped the hit, so the gate exited 0 on a genuine `$token` site.
expect 1 'violation containing ": *" is caught' \
  'fun go(token: String) = android.util.Log.d("T", "mask: *$token")'

# Ordinary detection still works.
expect 1 'violation without ": *" is caught' \
  'fun go(token: String) = android.util.Log.d("T", "mask $token")'

# What the post-filter is actually for: a pattern list written in a Kotlin block
# comment must not fail the build.
expect 0 'block-comment continuation is skipped' \
  '/**
 * Log.d(TAG, "auth $token") is what the gate flags.
 * Log.e(TAG, "args: $args") likewise.
 */
fun go(id: String) = android.util.Log.d("T", "id $id")'

# Nothing planted at all.
expect 0 'clean source set passes' 'fun go(id: String) = android.util.Log.d("T", "id $id")'

if [ "$failures" -gt 0 ]; then
  echo "$failures test(s) failed"
  exit 1
fi
echo "All check-no-sensitive-logging.sh tests passed"
