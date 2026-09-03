#!/usr/bin/env bash
# Regression tests for scripts/check-no-sensitive-logging.sh (#577, #579).
#
# The gate reported clean while a real violation was being swallowed by its
# comment-continuation post-filter, so a green run of the gate itself proves
# nothing: this class of bug is only visible with a demonstrated-red control.
# The same argument applies to every name in the gate's alternation, so each is
# enumerated below with its own planted violation rather than sampled.
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
  mkdir -p scripts/lib app/src/main app/src/debug integrations/src/main \
    core/mcp/src/jvmMain relay/src/tls
  cp "$repo_root/$gate" "$repo_root/scripts/production-source-dirs.sh" scripts/
  # The gate resolves scripts/lib/gate-filters.sh relative to its own location
  # (#597), so the sandbox mirrors that layout.
  cp "$repo_root/scripts/lib/gate-filters.sh" scripts/lib/
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

# A one-line Kotlin log site interpolating $<name>, as literal source text.
probe_line() {
  printf 'fun go(v: String) = android.util.Log.d("T", "v=$%s")\n' "$1"
}

# The same log site written with the braced spelling, `${<name>}`. Kept as its
# own function rather than a flag on `probe_line`: the two are compared against
# each other all through this file, and a caller reading `braced_probe_line`
# does not have to remember which way the flag pointed (#692).
braced_probe_line() {
  printf 'fun go(v: String) = android.util.Log.d("T", "v=${%s}")\n' "$1"
}

# The names the gate carried before #579. Enumerated, not sampled: widening the
# alternation must not drop or typo one of the originals.
for name in token bearer verifier fcmToken firebaseToken pkceVerifier \
  accessToken refreshToken clientSecret privateKey; do
  # Assigned, then passed. Substituted straight into the argument list,
  # `probe_line`'s status is discarded AND `set -e` is disabled inside it
  # (SC2312/SC2311), so a `printf` that failed would hand `expect` an EMPTY
  # source line -- which the gate does not match, which `expect 1` then reports
  # as "the pattern stopped catching $name". A harness that manufactures its own
  # red is worse than one that stops.
  line=$(probe_line "$name")
  expect 1 "pre-existing \$$name is caught" "$line"
done

# The names #579 added: the camelCase gaps plus a snake_case spelling of every
# multi-word name, since the relay and the policy doc use snake_case.
for name in apiKey sessionToken integrationSecret secretPrefix \
  fcm_token firebase_token pkce_verifier access_token refresh_token \
  client_secret private_key api_key session_token integration_secret \
  secret_prefix; do
  # Assigned, then passed -- see the loop above.
  line=$(probe_line "$name")
  expect 1 "newly covered \$$name is caught" "$line"
done

# The names #596 added. "authorization codes" has been in the policy's Never-log
# list since #379 (docs/internal/logging.md:19) while the gate carried no
# spelling of it at all -- the same class of gap #579 closed for
# `$integrationSecret`/`$apiKey`/`$sessionToken`. Bare `code` is deliberately
# NOT covered: status/error/response codes are everywhere in this tree, and a
# gate that fires on those gets loosened back into uselessness.
for name in authCode auth_code authorizationCode authorization_code; do
  # Assigned, then passed -- see the loop above.
  line=$(probe_line "$name")
  expect 1 "newly covered \$$name is caught" "$line"
done

# The braced spelling, `${<name>}` (#692). Not a stylistic variant: in Kotlin
# `${...}` is mandatory for anything that is not a bare identifier, so it is the
# spelling a developer reaches for the moment a covered name stops being a local
# and starts being a property or a call. The gate matched `$token` and let
# `${token}` through for its whole life -- uniformly, across all 29 names --
# because the interpolation prefix was a bare `\$` with no room for the brace.
#
# Enumerated rather than sampled, for the same reason the bare loops above are.
# The fix is a single `\{?` in one shared prefix, so in principle one case would
# prove it; but the alternation has already drifted twice (#564/#574), and a
# per-name asymmetry reintroduced by a later edit is exactly what an enumerated
# harness catches and a sampled one does not.
for name in token bearer verifier fcmToken firebaseToken pkceVerifier \
  accessToken refreshToken clientSecret privateKey apiKey sessionToken \
  integrationSecret secretPrefix authCode authorizationCode \
  fcm_token firebase_token pkce_verifier access_token refresh_token \
  client_secret private_key api_key session_token integration_secret \
  secret_prefix auth_code authorization_code; do
  # Assigned, then passed -- see the first loop above for why.
  line=$(braced_probe_line "$name")
  expect 1 "braced \${$name} is caught" "$line"
done

# The braced form of the #577 case, so the post-filter interaction is measured
# on the widened prefix too and not assumed to carry over.
expect 1 'braced violation containing ": *" is caught' \
  'fun go(token: String) = android.util.Log.d("T", "mask: *${token}")'

# The braced form of the block-comment exemption. A pattern list written in a
# Kotlin comment must still not fail the build now that the braced spelling
# matches -- and a doc comment is precisely where someone writes `${token}` as
# an example of what not to do.
expect 0 'braced example in a block-comment continuation is skipped' \
  '/**
 * Log.d(TAG, "auth ${token}") is what the gate flags.
 */
fun go(id: String) = android.util.Log.d("T", "id $id")'

# The two things the optional brace deliberately does NOT buy, pinned here so
# the "does not catch" list in docs/internal/logging.md is describing a measured
# behaviour rather than an assumption. #690's symmetric-difference check polices
# the gloss's NAMES; nothing polices these bullets, so they are asserted instead.
#
# 1. Property chains. The covered name has to sit immediately after `$` or `${`,
#    so a chain ending in a secret still passes. Reaching into the braces means
#    either a brace-wrapped second copy of the alternation (the #564/#574 drift)
#    or a `[^}]*` that gives up the `\b` the safe cases below depend on.
expect 0 'residue: braced property chain ${user.token} is NOT caught' \
  'fun go(user: U) = android.util.Log.d("T", "v=${user.token}")'
expect 0 'residue: braced property chain ${creds.apiKey} is NOT caught' \
  'fun go(creds: C) = android.util.Log.d("T", "v=${creds.apiKey}")'

# 2. The flip side of the same shared prefix: an accessor ON a covered name is
#    flagged, even `.length`, which the policy calls safe. A line-level ERE
#    cannot tell `.length` from `.take(4)`, and a prefix of a secret is
#    never-log, so this errs toward flagging. Asserted rather than tolerated:
#    if someone later narrows the prefix to silence it, that trade should be a
#    decision with a failing test in front of it, not a quiet edit.
expect 1 'over-approximation: braced ${token.length} trips the gate' \
  'fun go(token: String) = android.util.Log.d("T", "len=${token.length}")'

# Values docs/internal/logging.md explicitly says are SAFE to log. These are the
# false positives that option 2 (a `\$[A-Za-z_]*(secret|token|key)` substring
# rule) would introduce, so they are asserted green: if a later "simplification"
# broadens the pattern, these fail loudly instead of the gate quietly becoming
# something people work around.
expect 0 'safe: $tokenEntity does not match' \
  'fun go(tokenEntity: TokenEntity) = android.util.Log.d("T", "client=$tokenEntity")'
expect 0 'safe: TokenEntity.label does not match' \
  'fun go(e: TokenEntity) = android.util.Log.d("T", "client=${e.label} / $TokenEntity.label")'
expect 0 'safe: Firebase key id does not match' \
  'fun go(kid: String, keyId: String) = android.util.Log.d("T", "jwks kid=$kid id=$keyId")'

# The braced forms of the same safe values (#692). This pair is the whole test
# of whether the widening is precise or blunt: `${token}` must be caught by the
# loop above while `${tokenEntity}` stays green here. Both run through the same
# `\$\{?` prefix, so what separates them is the trailing `\b` -- which is why the
# widening had to leave that boundary alone rather than reach for a substring
# rule. `TokenEntity.label` is already asserted braced above: the existing case
# logs `${e.label}`.
expect 0 'safe: braced ${tokenEntity} does not match' \
  'fun go(tokenEntity: TokenEntity) = android.util.Log.d("T", "client=${tokenEntity}")'
expect 0 'safe: braced ${kid} does not match' \
  'fun go(kid: String, keyId: String) = android.util.Log.d("T", "jwks kid=${kid} id=${keyId}")'

# A name that merely CONTAINS a covered name, braced. `${bearerish}` and
# `${secretPrefixes}` are the shapes a substring rule would swallow; the `\b`
# keeps them green on the braced side exactly as it does on the bare side.
expect 0 'safe: braced ${secretPrefixes} does not match' \
  'fun go(secretPrefixes: List<String>) = android.util.Log.d("T", "n=${secretPrefixes}")'

# docs/internal/logging.md describes this gate in three parts: the `PATTERN`
# quoted verbatim in a fenced block, a prose gloss naming each covered variable,
# and an explicit "does not catch" list. #564/#574 were that doc drifting away
# from the gate; widening the alternation without moving the prose reintroduces
# exactly that. The two mechanically checkable parts are checked here, so the
# doc cannot silently fall behind the next name someone adds.
doc=$repo_root/docs/internal/logging.md
# The literal prefix of the fenced PATTERN block, shared by the two greps below.
doc_pattern_prefix='Log\.[dievw].*\$\{?('

# The alternation's names, one per line. Parameter expansion rather than sed:
# the pattern is itself a regex full of backslashes, and a sed program to strip
# it is less readable than the two substring cuts it would replace.
gate_pattern_line=$(grep -m1 '^PATTERN=' "$repo_root/$gate")
gate_pattern=${gate_pattern_line#PATTERN=\'}
gate_pattern=${gate_pattern%\'}
alternation=${gate_pattern#*'\$\{?('}
alternation=${alternation%%')\b'*}
gate_names=$(printf '%s\n' "$alternation" | tr '|' '\n' | sort)

# Anti-vacuity control on the parse itself. If either substring cut above
# misses, `gate_names` is the whole pattern or empty -- and an empty set
# compares equal to an empty prose set, which would report this doc check GREEN
# while measuring nothing. Two names known to be in the alternation, one
# original and one from the widening this control was written for, must survive
# the parse before the comparison below means anything.
parse_ok=1
case $'\n'"$gate_names"$'\n' in
  *$'\ntoken\n'*) ;;
  *) parse_ok=0 ;;
esac
case $'\n'"$gate_names"$'\n' in
  *$'\nauthCode\n'*) ;;
  *) parse_ok=0 ;;
esac
if [ "$parse_ok" -ne 1 ]; then
  echo "FAIL: could not parse the alternation out of the gate's PATTERN"
  printf '    got: %s\n' "$gate_names"
  failures=$((failures + 1))
else
  echo "ok: the alternation parses into names"
fi

# Part 1: the fenced block in the doc is the gate's PATTERN byte for byte.
# grep -c exits 1 on no match, which under `set -e` would kill this script
# mid-suite and read as a crash rather than as the finding it is (#628).
set +e
doc_pattern_count=$(grep -cF "$doc_pattern_prefix" "$doc")
doc_grep_status=$?
set -e
if [ "$doc_grep_status" -gt 1 ]; then
  echo "FAIL: grep over $doc failed with status $doc_grep_status"
  failures=$((failures + 1))
elif [ "$doc_pattern_count" -ne 1 ]; then
  echo "FAIL: $doc quotes the PATTERN $doc_pattern_count times, expected exactly 1"
  failures=$((failures + 1))
else
  doc_pattern=$(grep -m1 -F "$doc_pattern_prefix" "$doc")
  if [ "$doc_pattern" != "$gate_pattern" ]; then
    echo "FAIL: the PATTERN quoted in docs/internal/logging.md is not the gate's"
    printf '    gate: %s\n' "$gate_pattern"
    printf '    doc:  %s\n' "$doc_pattern"
    failures=$((failures + 1))
  else
    echo "ok: docs/internal/logging.md quotes PATTERN verbatim"
  fi
fi

# Part 2: the prose gloss names exactly the alternation's names -- symmetric
# difference empty in BOTH directions, so a name added to the gate and left out
# of the prose fails here, and so does a name the prose claims is covered when
# it is not. The range is bounded to the gloss bullet on purpose: the "does not
# catch" list below it names `$secretValue`, `$credential` and `$tokenEntity`
# precisely because they are NOT in the alternation.
gloss=$(sed -n '/^- a Kotlin string-template interpolation/,/^- the literal /p' "$doc")
# `grep -o` finding nothing leaves this empty rather than killing the script:
# `sort` ends the pipeline and exits 0 either way. An empty prose set then fails
# the comparison below, which is the correct outcome -- it cannot read as green.
doc_names=$(printf '%s\n' "$gloss" | grep -oE '`\$[A-Za-z_]+`' | tr -d '`$' | sort)
if [ "$gate_names" != "$doc_names" ]; then
  echo "FAIL: the prose gloss in docs/internal/logging.md does not match the alternation"
  echo "    in the gate but not the prose:"
  comm -23 <(printf '%s\n' "$gate_names") <(printf '%s\n' "$doc_names") | sed 's/^/      /'
  echo "    in the prose but not the gate:"
  comm -13 <(printf '%s\n' "$gate_names") <(printf '%s\n' "$doc_names") | sed 's/^/      /'
  failures=$((failures + 1))
else
  echo "ok: the prose gloss in docs/internal/logging.md names exactly the alternation"
fi

if [ "$failures" -gt 0 ]; then
  echo "$failures test(s) failed"
  exit 1
fi
echo "All check-no-sensitive-logging.sh tests passed"
