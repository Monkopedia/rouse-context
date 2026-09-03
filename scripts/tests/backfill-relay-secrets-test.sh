#!/usr/bin/env bash
# Regression tests for scripts/backfill-relay-secrets.sh (#686).
#
# The script writes production GitHub Actions secrets -- the Cloudflare API
# token, the GTS EAB credentials, the Firebase service-account JSON. Its exit
# status is the operator's only signal that provisioning worked, and before
# #686 a run that pushed four of six exited 0: indistinguishable from a
# complete one, with the damage surfacing days later as a relay that cannot
# renew certs. So the interesting assertion here is not "the script runs" but
# "a push that did not land makes the run go RED, and says which one".
#
# Nothing here may touch the network or a real repository. `gh` and `gcloud`
# are stubbed on PATH; the stubs serve fixtures, record what was attempted,
# and fail on demand for a named secret so both directions are exercised in
# one run. The fixture values below are obvious placeholders, not credentials.

set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
script="$repo_root/scripts/backfill-relay-secrets.sh"

sandbox=$(mktemp -d)
trap 'rm -rf "$sandbox"' EXIT

failures=0
pass() { echo "ok: $1"; }
fail() {
  echo "FAIL: $1"
  printf '%s\n' "${2:-}" | sed 's/^/    /'
  failures=$((failures + 1))
}

mkdir -p "$sandbox/bin" "$sandbox/fixtures"

# Resolved before the stub dir goes on PATH so the chmod stub cannot recurse
# into itself.
REAL_CHMOD=$(command -v chmod)
export REAL_CHMOD

# --- stubs --------------------------------------------------------------------

# Stands in for `gcloud compute ssh <inst> --zone=<z> --command='sudo cat <p>'`.
# Serves fixture text; never reaches GCE.
cat > "$sandbox/bin/gcloud" <<'STUB'
#!/usr/bin/env bash
for arg in "$@"; do
  case "$arg" in
    "--command=sudo cat /etc/rouse-relay/env")
      cat "$STUB_FIXTURES/env"; exit 0 ;;
    "--command=sudo cat /etc/rouse-relay/firebase-sa.json")
      cat "$STUB_FIXTURES/firebase-sa.json"; exit 0 ;;
  esac
done
echo "gcloud stub: unexpected invocation: $*" >&2
exit 99
STUB

# Stands in for `gh secret set NAME --repo OWNER/REPO` with the value on stdin.
# Records every attempted name in $GH_ATTEMPTS (so a test can prove which
# secrets the script got as far as trying) and fails for any name listed in
# $GH_FAIL_SECRETS. The value itself is drained to /dev/null: never stored,
# never echoed, not even in a stub.
cat > "$sandbox/bin/gh" <<'STUB'
#!/usr/bin/env bash
if [ "${1:-}" != "secret" ] || [ "${2:-}" != "set" ]; then
  echo "gh stub: unexpected invocation: $*" >&2
  exit 99
fi
name="${3:-}"
cat > /dev/null
printf '%s\n' "$name" >> "$GH_ATTEMPTS"
case " ${GH_FAIL_SECRETS:-} " in
  *" $name "*)
    echo "gh: failed to set secret $name: HTTP 403 (stub)" >&2
    exit 1 ;;
esac
exit 0
STUB

# Real chmod, except that a file whose basename is $CHMOD_ZERO_BASENAME gets
# mode 000 instead. The script's captured values live in a tmpdir it creates
# itself, so this is the only seam a test has into them -- and it is what makes
# the "captured file cannot be read" branch (#682) reachable at all.
cat > "$sandbox/bin/chmod" <<'STUB'
#!/usr/bin/env bash
target="${!#}"
if [ -n "${CHMOD_ZERO_BASENAME:-}" ] && \
   [ "$(basename "$target")" = "$CHMOD_ZERO_BASENAME" ]; then
  exec "$REAL_CHMOD" 000 "$target"
fi
exec "$REAL_CHMOD" "$@"
STUB

chmod +x "$sandbox/bin/gcloud" "$sandbox/bin/gh" "$sandbox/bin/chmod"

# --- fixtures -----------------------------------------------------------------

cat > "$sandbox/fixtures/env.full" <<'EOF'
CLOUDFLARE_API_TOKEN=placeholder-cf-token
CLOUDFLARE_ZONE_ID=placeholder-zone-id
GTS_EAB_KID=placeholder-kid
GTS_EAB_HMAC=placeholder-hmac
RUST_LOG=info
EOF

# Same, minus RUST_LOG: the script drops the file and push_secret takes its
# [skip] branch. A skip is not a failure and must not colour the exit status.
grep -v '^RUST_LOG=' "$sandbox/fixtures/env.full" > "$sandbox/fixtures/env.norustlog"

printf '%s\n' '{"type": "service_account", "project_id": "placeholder"}' \
  > "$sandbox/fixtures/firebase-sa.json"

ALL_SIX="CLOUDFLARE_API_TOKEN CLOUDFLARE_ZONE_ID RELAY_GTS_EAB_KID \
RELAY_GTS_EAB_HMAC RELAY_RUST_LOG RELAY_FIREBASE_SERVICE_ACCOUNT_JSON"

# --- runner -------------------------------------------------------------------

last_out=""
last_status=0
last_attempts=""

# run_backfill <fail-list> <prompt-answer> <chmod-zero-basename> <env-fixture>
run_backfill() {
  local fail_list="$1" answer="$2" chmod_zero="$3" env_fixture="$4"
  : > "$sandbox/attempts"
  cp "$sandbox/fixtures/$env_fixture" "$sandbox/fixtures/env"
  set +e
  last_out=$(printf '%s\n' "$answer" | env \
    PATH="$sandbox/bin:$PATH" \
    STUB_FIXTURES="$sandbox/fixtures" \
    GH_ATTEMPTS="$sandbox/attempts" \
    GH_FAIL_SECRETS="$fail_list" \
    CHMOD_ZERO_BASENAME="$chmod_zero" \
    bash "$script" Example/fixture-repo 2>&1)
  last_status=$?
  set -e
  last_attempts=$(cat "$sandbox/attempts")
}

# --- assertions ---------------------------------------------------------------
#
# Deliberately NOT written as `want_x ... || return`: `scripts/check-shell-lint.sh`
# runs shellcheck with `-o check-set-e-suppressed`, and a function invoked in a
# condition turns `set -e` off inside it (SC2310). So each assertion is a plain
# call that records its verdict in $case_failed, and every assertion after the
# first failure in a case is a no-op -- one FAIL per case, not a cascade.
case_failed=0

begin_case() { case_failed=0; }

end_case() {
  local name="$1" detail="${2:-}"
  if [ "$case_failed" -eq 0 ]; then
    pass "$name${detail:+ ($detail)}"
  fi
}

want_status() {
  local name="$1" want="$2"
  if [ "$case_failed" -ne 0 ]; then return 0; fi
  if [ "$last_status" -ne "$want" ]; then
    fail "$name -- expected exit $want, got $last_status" "$last_out"
    case_failed=1
  fi
  return 0
}

want_nonzero() {
  local name="$1"
  if [ "$case_failed" -ne 0 ]; then return 0; fi
  if [ "$last_status" -eq 0 ]; then
    fail "$name -- expected a non-zero exit, got 0" "$last_out"
    case_failed=1
  fi
  return 0
}

want_output() {
  local name="$1" needle="$2"
  if [ "$case_failed" -ne 0 ]; then return 0; fi
  if ! printf '%s\n' "$last_out" | grep -qF -- "$needle"; then
    fail "$name -- output never mentioned '$needle'" "$last_out"
    case_failed=1
  fi
  return 0
}

want_attempted() {
  local name="$1"
  shift
  if [ "$case_failed" -ne 0 ]; then return 0; fi
  local secret
  for secret in "$@"; do
    if ! printf '%s\n' "$last_attempts" | grep -qx -- "$secret"; then
      fail "$name -- $secret was never handed to gh" \
        "attempted:
$last_attempts

$last_out"
      case_failed=1
      return 0
    fi
  done
  return 0
}

want_nothing_attempted() {
  local name="$1"
  if [ "$case_failed" -ne 0 ]; then return 0; fi
  if [ -n "$last_attempts" ]; then
    fail "$name -- secrets were pushed when none should have been" \
      "attempted:
$last_attempts"
    case_failed=1
  fi
  return 0
}

# --- cases --------------------------------------------------------------------

# The green direction. Guards the fix against over-correction: a change that
# made every run fail would satisfy the red cases below and be useless.
case_all_success() {
  local n='a run where every push succeeds exits 0'
  begin_case
  run_backfill "" yes "" env.full
  want_status "$n" 0
  # The name list is meant to split into separate arguments here.
  # shellcheck disable=SC2086
  want_attempted "$n" $ALL_SIX
  want_output "$n" 'Done. Verify with:'
  end_case "$n" "exit 0, six secrets pushed"
}

# The #686 case. Before the fix this run exited 0, indistinguishable from a
# complete provision.
case_one_push_fails() {
  local n='a failed push makes the run go RED and names the secret'
  begin_case
  run_backfill "RELAY_GTS_EAB_KID" yes "" env.full
  want_nonzero "$n"
  want_output "$n" 'RELAY_GTS_EAB_KID'
  end_case "$n" "exit $last_status"
}

# The shape decision, asserted rather than assumed: failures accumulate, so a
# secret failing does not strand the ones after it unattempted. An operator who
# learns all six outcomes can finish the job by hand; one who learns only the
# first cannot.
case_later_secrets_still_attempted() {
  local n='a failure early in the list does not strand the later secrets'
  begin_case
  run_backfill "CLOUDFLARE_API_TOKEN" yes "" env.full
  want_nonzero "$n"
  # The name list is meant to split into separate arguments here.
  # shellcheck disable=SC2086
  want_attempted "$n" $ALL_SIX
  end_case "$n" "all six attempted, exit $last_status"
}

# The last secret failing is the one case a "status of the final command" fix
# would pass by accident; the first-failing case above is the one such a fix
# would miss. Both are asserted so neither shape can sneak through.
case_last_push_fails() {
  local n='a failure on the LAST secret still makes the run go RED'
  begin_case
  run_backfill "RELAY_FIREBASE_SERVICE_ACCOUNT_JSON" yes "" env.full
  want_nonzero "$n"
  want_output "$n" 'RELAY_FIREBASE_SERVICE_ACCOUNT_JSON'
  end_case "$n" "exit $last_status"
}

case_two_pushes_fail() {
  local n='two failed pushes are both named in the summary'
  begin_case
  run_backfill "CLOUDFLARE_ZONE_ID RELAY_RUST_LOG" yes "" env.full
  want_nonzero "$n"
  want_output "$n" '  - CLOUDFLARE_ZONE_ID'
  want_output "$n" '  - RELAY_RUST_LOG'
  end_case "$n" "exit $last_status"
}

# A secret with no captured value is skipped by design (RUST_LOG is optional).
# A skip is not a failure, and folding it into the accumulator would make the
# script fail on a perfectly healthy relay.
case_skip_is_not_a_failure() {
  local n='an intentionally skipped secret leaves the run green'
  begin_case
  run_backfill "" yes "" env.norustlog
  want_status "$n" 0
  want_output "$n" '[skip] RELAY_RUST_LOG'
  end_case "$n" "exit 0"
}

# The #682 branch: a captured file the script cannot size is not pushed. That
# already printed "[fail] ... NOT pushed"; #686 is about it reaching the exit
# status too. Mode 000 does not restrain root, so this is skipped there rather
# than reported as a spurious pass.
case_unreadable_capture() {
  local n='a captured file that cannot be read makes the run go RED'
  local uid
  uid=$(id -u)
  if [ "$uid" -eq 0 ]; then
    pass "$n (skipped: running as root, where mode 000 does not apply)"
    return 0
  fi
  begin_case
  run_backfill "" yes cf_zone_id env.full
  want_nonzero "$n"
  want_output "$n" 'NOT pushed'
  want_output "$n" '  - CLOUDFLARE_ZONE_ID'
  end_case "$n" "exit $last_status"
}

# The control-flow change lands next to an interactive prompt and a cleanup
# trap, so the prompt is asserted too: declining must still abort, before
# anything is pushed.
case_declined_confirmation() {
  local n='declining the confirmation prompt aborts without pushing'
  begin_case
  run_backfill "" no "" env.full
  want_nonzero "$n"
  want_output "$n" 'Aborted.'
  want_nothing_attempted "$n"
  end_case "$n" "exit $last_status, nothing pushed"
}

case_all_success
case_one_push_fails
case_later_secrets_still_attempted
case_last_push_fails
case_two_pushes_fail
case_skip_is_not_a_failure
case_unreadable_capture
case_declined_confirmation

if [ "$failures" -gt 0 ]; then
  echo
  echo "$failures case(s) failed: scripts/backfill-relay-secrets.sh is not"
  echo "behaving as its own tests describe. Fix the script, not these tests."
  exit 1
fi
echo "All backfill-relay-secrets.sh tests passed"
