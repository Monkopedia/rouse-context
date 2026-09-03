#!/usr/bin/env bash
# Regression tests for scripts/check-cancellation-catch-order.sh (#674).
#
# The requirement this file exists to meet is stated in #674 and is not
# negotiable: "If it cannot be made red on a known-bad historical tree, it is
# not a gate." A gate that has only ever been observed passing is
# indistinguishable from one that cannot fail, and this repo has shipped that
# before -- #629's coverage gate proved comparisons happened, not that they
# could detect anything.
#
# So every case below is a demonstrated control, in BOTH directions:
#
#   * a positive that must fire, and must NAME the offending file and line;
#   * a negative that must stay silent.
#
# One direction alone is worthless here. An empty pattern matches everything and
# a filtered search reaches nothing, and both look like a clean result.
#
# Part 4 goes further and mutates the gate itself: a copy with its broad-type
# match broken must make this suite fail (so the positives are not passing for
# some unrelated reason), and a copy with its guard check broken must make it
# fail too (so the negatives are not passing because the gate flags nothing).
#
# The gate scans whatever `scripts/production-source-dirs.sh` derives from the
# enclosing repo, so the tests run it against a throwaway repo built here rather
# than planting probe files in the real tree.

# The Kotlin probes below are literal source text: `$e` and `${...}` must reach
# the gate unexpanded, so single quotes are deliberate throughout.
# shellcheck disable=SC2016

set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
gate=scripts/check-cancellation-catch-order.sh
scanner=scripts/lib/kotlin-catch-chains.awk
allowlist=scripts/cancellation-catch-allowlist.tsv
backlog=scripts/cancellation-catch-backlog.tsv

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
  mkdir -p scripts/lib app/src/main app/src/debug integrations/src/main \
    core/mcp/src/jvmMain relay/src/tls
  cp "$repo_root/$gate" "$repo_root/scripts/production-source-dirs.sh" scripts/
  cp "$repo_root/$scanner" scripts/lib/
  echo 'package p' > app/src/main/Main.kt
  echo 'package p' > app/src/debug/Debug.kt
  echo 'package p' > integrations/src/main/Main.kt
  echo 'package p' > core/mcp/src/jvmMain/Main.kt
  echo '// rust' > relay/src/tls/mod.rs
  git add -A
}

# expect <exit-status> <name> <probe-file-content>
#
# The probe always lands at app/src/main/Probe.kt, so a red run has a file and a
# line to name and this harness can insist that it does.
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

# expect_line <line-number> <name> <probe-file-content>
#
# Stronger than `expect 1`: the reported line must be the line the broad `catch`
# is actually on. A gate that reports the right file and the wrong line is not
# actionable, and for a multi-line clause the two come apart.
expect_line() {
  local want_line=$1 name=$2 content=$3
  local probe=app/src/main/Probe.kt out status
  printf '%s\n' "$content" > "$sandbox/$probe"
  set +e
  out=$(cd "$sandbox" && bash "$gate" 2>&1)
  status=$?
  set -e
  rm -f "$sandbox/$probe"

  if [ "$status" -eq 0 ]; then
    echo "FAIL: $name -- expected a violation, gate exited 0"
    printf '%s\n' "$out" | sed 's/^/    /'
    failures=$((failures + 1))
    return
  fi
  if ! printf '%s\n' "$out" | grep -q "$probe:$want_line:"; then
    echo "FAIL: $name -- expected a finding at $probe:$want_line"
    printf '%s\n' "$out" | sed 's/^/    /'
    failures=$((failures + 1))
    return
  fi
  echo "ok: $name (named $probe:$want_line)"
}

setup_sandbox

echo "== Part 1: the rule =="

# THE POSITIVE CONTROL. The plain case, and the reason the whole gate exists.
expect 1 'unguarded catch (Exception) is caught' \
  'suspend fun go() {
    try {
        work()
    } catch (e: Exception) {
        report(e)
    }
}'

# THE NEGATIVE CONTROL. The same site with the guard above it must stay silent.
# Without this, a gate that flags every catch clause -- or every file -- passes
# every positive above and is still useless.
expect 0 'guarded catch (Exception) is not flagged' \
  'suspend fun go() {
    try {
        work()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        report(e)
    }
}'

# All four broad types, enumerated rather than sampled. `IllegalStateException`
# is the one that matters most and the one a "looks narrow, is not" reading of
# the source misses: CancellationException extends it.
for type in Exception Throwable RuntimeException IllegalStateException; do
  # Assigned, then passed. Substituted straight into the argument list,
  # `printf`'s status is discarded AND `set -e` is disabled inside it
  # (SC2312/SC2311), so a printf that failed would hand `expect` an EMPTY probe
  # -- which the gate does not flag, which `expect 1` then reports as "the gate
  # stopped catching $type". A harness that manufactures its own red is worse
  # than one that stops.
  probe=$(printf 'suspend fun go() {\n    try {\n        work()\n    } catch (e: %s) {\n        report(e)\n    }\n}\n' "$type")
  expect 1 "unguarded catch ($type) is caught" "$probe"
  probe=$(printf 'suspend fun go() {\n    try {\n        work()\n    } catch (e: CancellationException) {\n        throw e\n    } catch (e: %s) {\n        report(e)\n    }\n}\n' "$type")
  expect 0 "guarded catch ($type) is not flagged" "$probe"
done

# Narrow types are not the gate's business. Asserted so that a later "just match
# every catch" simplification fails here instead of turning the gate into noise
# people route around.
for type in IOException IllegalArgumentException TimeoutException; do
  # Assigned, then passed -- see the loop above for why.
  probe=$(printf 'suspend fun go() {\n    try {\n        work()\n    } catch (e: %s) {\n        report(e)\n    }\n}\n' "$type")
  expect 0 "narrow catch ($type) is not flagged" "$probe"
done

echo "== Part 2: the two spellings, and the shapes a line regex gets wrong =="

# Both spellings of the guard are live in this tree (#667), and a gate matching
# one misses the other. Enumerated.
for spelling in CancellationException kotlinx.coroutines.CancellationException \
  java.util.concurrent.CancellationException; do
  # Assigned, then passed -- see Part 1.
  probe=$(printf 'suspend fun go() {\n    try {\n        work()\n    } catch (e: %s) {\n        throw e\n    } catch (e: Exception) {\n        report(e)\n    }\n}\n' "$spelling")
  expect 0 "guard spelled $spelling counts" "$probe"
done

# The qualified spelling of the BROAD type must still be caught: `java.lang.`
# is not a way out of the gate.
expect 1 'qualified broad type java.lang.Exception is caught' \
  'suspend fun go() {
    try {
        work()
    } catch (e: java.lang.Exception) {
        report(e)
    }
}'

# ORDER, which is the actual property. Kotlin diagnoses neither an unreachable
# nor a mis-ordered catch clause, so a guard BELOW the broad clause compiles,
# reads as present, and never runs.
expect 1 'guard placed AFTER the broad clause does not count' \
  'suspend fun go() {
    try {
        work()
    } catch (e: Exception) {
        report(e)
    } catch (e: CancellationException) {
        throw e
    }
}'

# CHAIN IDENTITY. A guard on a DIFFERENT try, textually adjacent, must not be
# credited to this one. This is the case a line-window regex gets wrong, and it
# is the one that would let a real swallow through while the gate reported clean.
expect_line 9 'a guard on a neighbouring try is not credited' \
  'suspend fun go() {
    try {
        first()
    } catch (e: CancellationException) {
        throw e
    }
    try {
        second()
    } catch (e: Exception) {
        report(e)
    }
}'

# A guard EARLIER in the same chain still counts even when another clause sits
# between it and the broad one. The property is "some earlier clause of this
# chain", not "the immediately preceding clause".
expect 0 'guard earlier in the same chain, with a clause between, counts' \
  'suspend fun go() {
    try {
        work()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        report(e)
    } catch (e: Exception) {
        report(e)
    }
}'

# The multi-line, annotated clause this tree actually contains
# (`McpTool.kt:170`). No line regex parses it; the reported line must be the
# line the `catch` keyword is on.
expect_line 4 'a multi-line annotated clause is caught, at the catch line' \
  'suspend fun go(): R {
    return try {
        work()
    } catch (
        @Suppress("TooGenericExceptionCaught")
        t: Throwable
    ) {
        R.Error
    }
}'

expect 0 'a multi-line annotated clause with a guard above it is not flagged' \
  'suspend fun go(): R {
    return try {
        work()
    } catch (ce: kotlinx.coroutines.CancellationException) {
        throw ce
    } catch (
        @Suppress("TooGenericExceptionCaught")
        t: Throwable
    ) {
        R.Error
    }
}'

echo "== Part 3: what must NOT trip it, and what must not hide from it =="

# Prose about the construct. Every gate in this repo has had to learn this one.
expect 0 'a KDoc paragraph about catch (Exception) is not a violation' \
  '/**
 * Never write `catch (e: Exception)` without a cancellation clause above it.
 * } catch (e: Exception) {
 */
suspend fun go() {
    try {
        work()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        report(e)
    }
}'

# ...and the flip side: a comment must not HIDE a violation either. A scanner
# that blanked too much would pass the case above and this one would go quiet.
expect 1 'a violation below a comment mentioning the guard is still caught' \
  'suspend fun go() {
    // A catch (e: CancellationException) guard belongs above the clause below.
    try {
        work()
    } catch (e: Exception) {
        report(e)
    }
}'

# A string literal is not code. `"} catch (e: CancellationException) {"` in a
# log message must not silence the real clause underneath it.
expect 1 'a guard quoted inside a string literal does not count' \
  'suspend fun go() {
    try {
        log("} catch (e: CancellationException) { throw e }")
    } catch (e: Exception) {
        report(e)
    }
}'

# The multi-line string template (`TestRelayFixture.kt:392`). Its `${ ... }`
# spans lines and contains braces; read as code they shift the depth counter and
# every chain decision after them is made at the wrong depth. This probe puts a
# real violation AFTER one, so a scanner that mis-tracks depth reports clean.
expect_line 10 'a violation after a multi-line string template is still caught' \
  'suspend fun go() {
    log(
        "pid=${runCatching {
            p.pid()
        }.getOrDefault(-1)}) " +
            "done"
    )
    try {
        work()
    } catch (e: Exception) {
        report(e)
    }
}'

# `Flow.catch { }` is a different construct with different semantics (#667
# cleared those separately). `catch` not followed by `(` is not a catch clause.
expect 0 'Flow.catch { } is not a catch clause' \
  'suspend fun go() {
    flow.catch { e -> report(e) }.collect()
    try {
        work()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        report(e)
    }
}'

# A `fun interface` declaration and a brace-less interface member both used to
# leave the function-header parser armed, which mis-attributed every later catch
# to the wrong ledger key. The violation is real either way; what this pins is
# that the gate still finds it.
expect 1 'a violation after a fun interface declaration is caught' \
  'fun interface Signer {
    suspend fun sign(data: ByteArray): String?
}

suspend fun go() {
    try {
        work()
    } catch (e: Exception) {
        report(e)
    }
}'

# The known-bad historical shape, reduced from `CtLogMonitor.check` as it stood
# at 9b50f469 -- the commit before #660. This is the "red on a known-bad
# historical tree" requirement in miniature; the full run against that tree is
# recorded in the PR.
expect 1 'the pre-#660 CtLogMonitor shape goes red' \
  'suspend fun check(domain: String): CtResult {
    return try {
        val entries = httpClient.get(logUrl(domain))
        CtResult.Ok(entries)
    } catch (e: Exception) {
        CtResult.Warning("CT check failed: ${e.message}")
    }
}'

echo "== Part 4: the anti-vacuity control =="

# A gate that reads nothing and a gate that finds nothing print the same empty
# result. The gate refuses to report clean for a tree in which it parsed no
# catch clauses at all, and that refusal is itself a control worth pinning:
# without it, every `expect 0` above could be passing for the wrong reason.
#
# Not run through `expect`, which insists a red run names the probe file: there
# is no offending site to name here, and the finding is about the SCAN rather
# than about any one file. So the diagnostic itself is what is asserted.
vacuity_probe=app/src/main/Probe.kt
printf 'suspend fun go() = work()\n' > "$sandbox/$vacuity_probe"
set +e
vacuity_out=$(cd "$sandbox" && bash "$gate" 2>&1)
vacuity_status=$?
set -e
rm -f "$sandbox/$vacuity_probe"
if [ "$vacuity_status" -eq 0 ]; then
  echo "FAIL: a tree with no catch clauses at all must be RED, not clean"
  printf '%s\n' "$vacuity_out" | sed 's/^/    /'
  failures=$((failures + 1))
elif ! printf '%s\n' "$vacuity_out" | grep -q 'parsed ZERO catch clauses'; then
  echo "FAIL: a tree with no catch clauses went red without saying why"
  printf '%s\n' "$vacuity_out" | sed 's/^/    /'
  failures=$((failures + 1))
else
  echo "ok: a tree with no catch clauses at all is RED, and says so (exit $vacuity_status)"
fi

echo "== Part 5: the ledgers =="

# ledger_case <want> <name> <allowlist-content> <backlog-content> <probe>
ledger_case() {
  local want=$1 name=$2 allow=$3 back=$4 content=$5
  local probe=app/src/main/Probe.kt out status
  printf '%s\n' "$content" > "$sandbox/$probe"
  printf '%s' "$allow" > "$sandbox/$allowlist"
  printf '%s' "$back" > "$sandbox/$backlog"
  set +e
  out=$(cd "$sandbox" && bash "$gate" 2>&1)
  status=$?
  set -e
  rm -f "$sandbox/$probe" "$sandbox/$allowlist" "$sandbox/$backlog"

  if [ "$status" -ne "$want" ]; then
    echo "FAIL: $name -- expected exit $want, got $status"
    printf '%s\n' "$out" | sed 's/^/    /'
    failures=$((failures + 1))
    return
  fi
  echo "ok: $name (exit $status)"
}

violating_probe='suspend fun go() {
    try {
        work()
    } catch (e: Exception) {
        report(e)
    }
}'
good_reason='No suspension point in this try; cannot take delivery of cancellation.'

ledger_case 0 'an allowlist entry silences the site' \
  "$(printf 'app/src/main/Probe.kt\tgo\t1\t%s\n' "$good_reason")" '' \
  "$violating_probe"

ledger_case 0 'a backlog entry silences the site' \
  '' "$(printf 'app/src/main/Probe.kt\tgo\t1\tPre-existing, unaudited. Tracked by #667.\n')" \
  "$violating_probe"

# The ratchet, upwards: an entry for 1 does not cover 2.
ledger_case 1 'a second violation in a listed function is still caught' \
  "$(printf 'app/src/main/Probe.kt\tgo\t1\t%s\n' "$good_reason")" '' \
  'suspend fun go() {
    try {
        work()
    } catch (e: Exception) {
        report(e)
    }
    try {
        more()
    } catch (e: Throwable) {
        report(e)
    }
}'

# The ratchet, downwards. This is the half that keeps either file from becoming
# the place things go to be forgotten: fixing a site fails the gate until the
# entry is removed, so nobody can fix one and leave the record behind.
ledger_case 1 'an entry whose violation is gone is a STALE-entry failure' \
  "$(printf 'app/src/main/Probe.kt\tgo\t1\t%s\n' "$good_reason")" '' \
  'suspend fun go() {
    try {
        work()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        report(e)
    }
}'

ledger_case 1 'an entry for a count higher than the tree is a STALE-entry failure' \
  "$(printf 'app/src/main/Probe.kt\tgo\t2\t%s\n' "$good_reason")" '' \
  "$violating_probe"

# A reason is the entire point of a ledger entry.
ledger_case 1 'an entry with no reason is rejected' \
  "$(printf 'app/src/main/Probe.kt\tgo\t1\tn/a\n')" '' \
  "$violating_probe"

# A backlog entry is an unfixed defect, so it must name the issue that will fix
# it. Without that, the backlog is an allowlist with a different filename.
ledger_case 1 'a backlog entry that cites no issue is rejected' \
  '' "$(printf 'app/src/main/Probe.kt\tgo\t1\t%s\n' "$good_reason")" \
  "$violating_probe"

# The same key on both ledgers is ambiguous: the tree cannot be reconciled
# against it by inspection.
ledger_case 1 'the same key on both ledgers is rejected' \
  "$(printf 'app/src/main/Probe.kt\tgo\t1\t%s\n' "$good_reason")" \
  "$(printf 'app/src/main/Probe.kt\tgo\t1\tPre-existing, unaudited. Tracked by #667.\n')" \
  "$violating_probe"

# A ledger entry keys on <path, function>, so it must not silence the same
# function name in a different file, nor a different function in the same file.
ledger_case 1 'an allowlist entry does not silence a different function' \
  "$(printf 'app/src/main/Probe.kt\tsomethingElse\t1\t%s\n' "$good_reason")" '' \
  "$violating_probe"

echo "== Part 6: this suite's own controls =="

# Parts 1-5 are all assertions about a gate. If the gate stopped working in
# either direction, would this file notice? That is not a question to answer by
# reading it. Two mutants, each breaking one direction, and each must make a
# nested run of this suite FAIL and SAY so -- exiting nonzero is not enough,
# since a script that dies mutely does that too (#711).
#
# `CANCEL_CATCH_TEST_NESTED` is what stops those runs recursing into here.
if [ -z "${CANCEL_CATCH_TEST_NESTED:-}" ]; then
  # Absolute: `setup_sandbox` left the working directory inside `$sandbox`, so a
  # relative `${BASH_SOURCE[0]}` no longer resolves from here.
  self_name=$(basename "${BASH_SOURCE[0]}")
  self=$repo_root/scripts/tests/$self_name
  nested=$sandbox/nested

  build_nested() {
    rm -rf "$nested"
    mkdir -p "$nested/scripts/lib" "$nested/scripts/tests"
    cp "$repo_root/$gate" "$repo_root/scripts/production-source-dirs.sh" "$nested/scripts/"
    cp "$repo_root/$scanner" "$nested/scripts/lib/"
    cp "$self" "$nested/scripts/tests/"
  }

  # Run the mutated copy and require it to fail out loud.
  nested_expect() {
    local label=$1 out status ok=1
    set +e
    out=$(CANCEL_CATCH_TEST_NESTED=1 bash "$nested/scripts/tests/$self_name" 2>&1)
    status=$?
    set -e
    if [ "$status" -eq 0 ]; then
      ok=0
      echo "FAIL: $label -- the nested suite exited 0, expected nonzero"
    fi
    if ! printf '%s\n' "$out" | grep -q '^FAIL: '; then
      ok=0
      echo "FAIL: $label -- the nested suite printed no FAIL line"
      printf '%s\n' "$out" | tail -20 | sed 's/^/    /'
    fi
    if [ "$ok" -eq 1 ]; then
      echo "ok: $label"
    else
      failures=$((failures + 1))
    fi
  }

  # Mutant 1 -- the gate stops recognising broad types. Every positive control
  # in Parts 1-3 must go quiet, and this suite must notice. Without this, an
  # `expect 1` that passes proves only that SOMETHING went red.
  build_nested
  sed -i 's/^          Exception|Throwable|RuntimeException|IllegalStateException|.*)$/          ThisTypeDoesNotExist)/' \
    "$nested/scripts/$(basename "$gate")"
  nested_expect 'a gate that recognises no broad type fails this suite'

  # Mutant 2 -- the gate stops honouring the cancellation guard and flags every
  # broad catch. Every negative control must go red, and this suite must notice.
  # Without this, an `expect 0` that passes proves only that SOMETHING was quiet.
  build_nested
  sed -i 's/if \[ "\$c" = "0" \]; then/if [ "$c" = "0" ] || true; then/' \
    "$nested/scripts/$(basename "$gate")"
  nested_expect 'a gate that ignores the cancellation guard fails this suite'
fi

if [ "$failures" -gt 0 ]; then
  echo "$failures test(s) failed"
  exit 1
fi
echo "All check-cancellation-catch-order.sh tests passed"
