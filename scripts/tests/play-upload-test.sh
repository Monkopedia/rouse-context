#!/usr/bin/env bash
# Regression tests for scripts/play-upload.sh.
#
# The script drives a real Google Play edit, and the things that matter about
# it are the ones a happy-path run never shows: that a dry run NEVER commits
# and ALWAYS deletes its edit; that any failure deletes the edit instead of
# leaving it dangling; that a 403 surfaces as a 403 with its body; and that
# production, a FOSS artifact, or a bundle for another package are refused
# before anything reaches the API.
#
# Nothing here touches Google. The script's PLAY_API_ROOT is pointed at a mock
# Android Publisher server (python http.server, bound to 127.0.0.1) that serves
# the v3 edit endpoints, records every request it receives, and fails a named
# endpoint on demand. Fixture bundles are zips holding a protobuf
# base/manifest/AndroidManifest.xml in aapt2's format, encoded below; the
# reader they exercise (scripts/lib/aab_manifest.py) was checked against the
# real v1.0.13 Google AAB when it was written.
#
# Every red case asserts the specific reason, not just a non-zero exit.

set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
script="$repo_root/scripts/play-upload.sh"

sandbox=$(mktemp -d)
server_pid=""
cleanup() {
  if [[ -n "$server_pid" ]]; then
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
  fi
  rm -rf "$sandbox"
}
trap cleanup EXIT

failures=0
pass() { echo "ok: $1"; }
fail() {
  echo "FAIL: $1"
  printf '%s\n' "${2:-}" | sed 's/^/    /'
  failures=$((failures + 1))
}

# --- fixtures -------------------------------------------------------------------

# make_aab OUT PACKAGE VERSION_CODE VERSION_NAME
make_aab() {
  python3 - "$@" <<'PY'
import sys, zipfile
out, package, code, name = sys.argv[1:5]
NS = "http://schemas.android.com/apk/res/android"

def varint(n):
    b = bytearray()
    while True:
        x = n & 0x7F
        n >>= 7
        if n:
            b.append(x | 0x80)
        else:
            b.append(x)
            return bytes(b)

def ld(num, payload):
    if isinstance(payload, str):
        payload = payload.encode()
    return varint(num << 3 | 2) + varint(len(payload)) + payload

def attr(name, value, ns=""):
    return (ld(1, ns) if ns else b"") + ld(2, name) + ld(3, value)

element = (ld(3, "manifest")
           + ld(4, attr("package", package))
           + ld(4, attr("versionCode", code, NS))
           + ld(4, attr("versionName", name, NS))
           + ld(5, ld(1, ld(3, "application"))))
with zipfile.ZipFile(out, "w") as z:
    z.writestr("base/manifest/AndroidManifest.xml", ld(1, element))
    z.writestr("base/dex/classes.dex", b"dex\n035\0fixture")
PY
}

mkdir -p "$sandbox/dist" "$sandbox/other" "$sandbox/changelogs" "$sandbox/nochangelogs"
good="$sandbox/dist/rouse-context-1.0.13-google.aab"
make_aab "$good" com.rousecontext 14 1.0.13
foss="$sandbox/dist/rouse-context-1.0.13-foss.aab"
cp "$good" "$foss"
wrongpkg="$sandbox/other/rouse-context-1.0.13-google.aab"
make_aab "$wrongpkg" com.example.evil 14 1.0.13
printf 'Fixture release notes for versionCode 14.\n' >"$sandbox/changelogs/14.txt"

# --- mock Android Publisher -------------------------------------------------------

cat >"$sandbox/mock.py" <<'PY'
import http.server, json, os, re, sys

log_path, port_path = sys.argv[1], sys.argv[2]
PKG = "/androidpublisher/v3/applications/com.rousecontext"
UP = "/upload" + PKG
EDIT = "edit-123"

ROUTES = [
    ("insert",   "POST",   "^" + PKG + "/edits$"),
    ("upload",   "POST",   "^" + UP + "/edits/" + EDIT + r"/bundles\?uploadType=media$"),
    ("track",    "PUT",    "^" + PKG + "/edits/" + EDIT + "/tracks/[^/]+$"),
    ("validate", "POST",   "^" + PKG + "/edits/" + EDIT + ":validate$"),
    ("tracks",   "GET",    "^" + PKG + "/edits/" + EDIT + "/tracks$"),
    ("commit",   "POST",   "^" + PKG + "/edits/" + EDIT + ":commit$"),
    ("delete",   "DELETE", "^" + PKG + "/edits/" + EDIT + "$"),
]

def control():
    # Read per request so one server serves every case.
    try:
        with open(os.environ["MOCK_CONTROL"]) as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}

class H(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def handle_any(self):
        n = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(n) if n else b""
        name = "unknown"
        for rname, method, pat in ROUTES:
            if self.command == method and re.match(pat, self.path):
                name = rname
                break
        auth = self.headers.get("Authorization") == "Bearer " + os.environ["MOCK_TOKEN"]
        with open(log_path, "a") as f:
            f.write("%s auth=%s\n" % (name, "ok" if auth else "BAD"))
        if name == "track":
            with open(log_path + ".track-body", "wb") as f:
                f.write(body)
        if name == "upload":
            with open(log_path + ".upload-size", "w") as f:
                f.write(str(len(body)))

        ctl = control()
        if name in ctl.get("fail", {}):
            status, payload = ctl["fail"][name]
        elif name == "unknown" or not auth:
            status, payload = 404, {"error": {"code": 404, "message": "mock: no route"}}
        else:
            status = 200
            payload = {
                "insert":   {"id": EDIT, "expiryTimeSeconds": "9999999999"},
                "upload":   {"versionCode": ctl.get("upload_code", 14), "sha256": "00"},
                "track":    None,
                "validate": {"id": EDIT},
                "tracks":   {"kind": "androidpublisher#tracksListResponse",
                             "tracks": [{"track": "production", "releases": []},
                                        {"track": "alpha", "releases": [
                                            {"status": "completed", "versionCodes": ["13"]}]}]},
                "commit":   {"id": EDIT},
                "delete":   {},
            }[name]
            if name == "track":
                payload = json.loads(body or b"{}")
        data = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    do_GET = do_POST = do_PUT = do_DELETE = handle_any

srv = http.server.HTTPServer(("127.0.0.1", 0), H)
with open(port_path + ".tmp", "w") as f:
    f.write(str(srv.server_address[1]))
os.rename(port_path + ".tmp", port_path)
srv.serve_forever()
PY

export MOCK_CONTROL="$sandbox/control.json"
export MOCK_TOKEN="fixture-token-not-a-credential"
mock_log="$sandbox/requests.log"
python3 "$sandbox/mock.py" "$mock_log" "$sandbox/port" &
server_pid=$!
for _ in $(seq 1 100); do
  [[ -f "$sandbox/port" ]] && break
  if ! kill -0 "$server_pid" 2>/dev/null; then
    echo "FAIL: mock server exited before listening" >&2
    exit 1
  fi
  sleep 0.05
done
[[ -f "$sandbox/port" ]] || { echo "FAIL: mock server never wrote its port" >&2; exit 1; }
api_root="http://127.0.0.1:$(cat "$sandbox/port")"

# --- runner ---------------------------------------------------------------------

out="" status=0 calls=""
# run CONTROL_JSON [script args...] -- defaults: good bundle, alpha, changelogs.
run() {
  local ctl=$1
  shift
  printf '%s\n' "$ctl" >"$MOCK_CONTROL"
  : >"$mock_log"
  rm -f "$mock_log.track-body" "$mock_log.upload-size"
  set +e
  out=$(PLAY_API_ROOT="$api_root" PLAY_ACCESS_TOKEN="${TOKEN-$MOCK_TOKEN}" \
    bash "$script" "$@" 2>&1)
  status=$?
  set -e
  calls=$(sed 's/ auth=ok$//' "$mock_log" | tr '\n' ' ' | sed 's/ $//')
}
defaults=(--aab "$good" --version 1.0.13 --track alpha --changelog-dir "$sandbox/changelogs")

expect_calls() { # NAME EXPECTED
  if [[ "$calls" == "$2" ]]; then pass "$1: calls = [$2]"; else
    fail "$1: expected calls [$2], got [$calls]" "$out"; fi
}
expect_status() { # NAME nonzero|zero
  if [[ "$2" == zero && "$status" -eq 0 ]] || [[ "$2" == nonzero && "$status" -ne 0 ]]; then
    pass "$1: exit $status"
  else
    fail "$1: expected $2 exit, got $status" "$out"
  fi
}
expect_out() { # NAME FIXED_STRING
  if [[ "$out" == *"$2"* ]]; then pass "$1: says '$2'"; else
    fail "$1: output does not contain '$2'" "$out"; fi
}
expect_not_out() { # NAME FIXED_STRING
  if [[ "$out" != *"$2"* ]]; then pass "$1: does not say '$2'"; else
    fail "$1: output unexpectedly contains '$2'" "$out"; fi
}

# --- green: dry run ---------------------------------------------------------------

run '{}' "${defaults[@]}" --dry-run true
expect_status "dry run" zero
expect_calls "dry run" "insert tracks upload track validate delete"
expect_out "dry run" "DRY RUN OK"
expect_out "dry run lists the real track names" "alpha"
expect_not_out "dry run never prints the token" "$MOCK_TOKEN"
if grep -q 'auth=BAD' "$mock_log"; then
  fail "dry run: a request went out without the bearer token" "$(cat "$mock_log" || true)"
else
  pass "dry run: every request carried the bearer token"
fi
body=$(cat "$mock_log.track-body" 2>/dev/null || true)
if python3 - "$body" <<'PY'
import json, sys
b = json.loads(sys.argv[1])
r = b["releases"][0]
assert b["track"] == "alpha", b
assert r["versionCodes"] == ["14"], r
assert r["status"] == "completed", r
assert r["releaseNotes"] == [{"language": "en-US",
    "text": "Fixture release notes for versionCode 14."}], r
PY
then pass "dry run: track body has versionCode 14, completed, en-US notes"
else fail "dry run: track body wrong" "$body"; fi
size=$(cat "$mock_log.upload-size" 2>/dev/null || echo 0)
good_size=$(stat -c %s "$good")
if [[ "$size" -eq "$good_size" ]]; then pass "dry run: uploaded the whole bundle ($size bytes)"
else fail "dry run: uploaded $size bytes, bundle is $good_size"; fi

# --- green: real run ----------------------------------------------------------------

run '{}' "${defaults[@]}" --dry-run false
expect_status "commit run" zero
expect_calls "commit run" "insert upload track validate commit"
expect_out "commit run" "COMMITTED: versionCode 14 to track 'alpha' (completed)"

run '{}' "${defaults[@]}" --status draft --dry-run true
expect_status "draft status" zero
body=$(cat "$mock_log.track-body" 2>/dev/null || true)
if [[ "$body" == *'"status": "draft"'* ]]; then
  pass "draft status: track body carries status draft"
else fail "draft status: track body lacks status draft" "$body"; fi

# --- fail closed: only the exact value `false` commits -----------------------------

dry_calls="insert tracks upload track validate delete"
run '{}' "${defaults[@]}"
expect_status "--dry-run omitted" zero
expect_calls "--dry-run omitted is a dry run" "$dry_calls"
expect_out "--dry-run omitted" "DRY RUN OK"
for v in "" True TRUE FALSE False flase 0 no " false" "false "; do
  run '{}' "${defaults[@]}" --dry-run "$v"
  expect_status "--dry-run '$v'" zero
  expect_calls "--dry-run '$v' is a dry run" "$dry_calls"
  expect_out "--dry-run '$v'" "DRY RUN OK"
  expect_not_out "--dry-run '$v'" "COMMITTED"
done
run '{}' "${defaults[@]}" --dry-run
expect_status "--dry-run with no value" nonzero
expect_out "--dry-run with no value" "--dry-run needs a value"
expect_calls "--dry-run with no value" ""

# --- red: API failures delete the edit ------------------------------------------------

forbidden='{"fail": {"track": [403, {"error": {"code": 403, "message": "The caller does not have permission", "status": "PERMISSION_DENIED"}}]}}'
run "$forbidden" "${defaults[@]}" --dry-run false
expect_status "403 on tracks.update" nonzero
expect_out "403 on tracks.update" "-> HTTP 403"
expect_out "403 on tracks.update prints the body" "PERMISSION_DENIED"
expect_out "403 on tracks.update" "edits.tracks.update for track 'alpha' failed"
expect_calls "403 on tracks.update" "insert upload track delete"

# The dry run exists partly to learn the real API name of "Closed testing -
# Alpha". If that name is wrong, tracks.update fails -- and the list must
# already have been printed by then.
run '{"fail": {"track": [404, {"error": {"code": 404, "message": "Track not found: alpha"}}]}}' "${defaults[@]}" --dry-run true
expect_status "dry run, unknown track" nonzero
expect_out "dry run, unknown track" "-> HTTP 404"
expect_out "dry run, unknown track prints the track list first" "completed:13"
expect_calls "dry run, unknown track" "insert tracks upload track delete"

run '{"fail": {"validate": [400, {"error": {"code": 400, "message": "Only releases with status draft may be created on draft app."}}]}}' \
  "${defaults[@]}" --dry-run true
expect_status "validate 400 on dry run" nonzero
expect_out "validate 400 on dry run" "-> HTTP 400"
expect_out "validate 400 on dry run prints the body" "Only releases with status draft"
expect_calls "validate 400 on dry run" "insert tracks upload track validate delete"

run '{"fail": {"commit": [500, {"error": {"code": 500, "message": "backend error"}}]}}' "${defaults[@]}" --dry-run false
expect_status "commit 500" nonzero
expect_out "commit 500" "-> HTTP 500"
expect_out "commit 500" "edits.commit failed"
expect_calls "commit 500" "insert upload track validate commit delete"

run '{"fail": {"upload": [403, {"error": {"code": 403, "message": "APK specifies a version code that has already been used."}}]}}' "${defaults[@]}" --dry-run true
expect_status "upload 403" nonzero
expect_out "upload 403" "-> HTTP 403"
expect_out "upload 403" "edits.bundles.upload failed"
expect_calls "upload 403" "insert tracks upload delete"

run '{"upload_code": 99}' "${defaults[@]}" --dry-run true
expect_status "upload versionCode mismatch" nonzero
expect_out "upload versionCode mismatch" "Play reports versionCode 99 for the upload; the manifest says 14"
expect_calls "upload versionCode mismatch" "insert tracks upload delete"

run '{"fail": {"delete": [500, {"error": {"code": 500, "message": "delete broke"}}]}}' "${defaults[@]}" --dry-run true
expect_status "dry run whose delete fails" nonzero
expect_out "dry run whose delete fails" "dry run could not delete edit edit-123"
expect_calls "dry run whose delete fails" "insert tracks upload track validate delete"

run '{"fail": {"insert": [401, {"error": {"code": 401, "message": "Request had invalid authentication credentials."}}]}}' "${defaults[@]}" --dry-run true
expect_status "insert 401" nonzero
expect_out "insert 401" "-> HTTP 401"
expect_out "insert 401" "edits.insert failed"
expect_calls "insert 401" "insert"

# --- red: refused before any request -------------------------------------------------

for t in production Production wear:production; do
  run '{}' --aab "$good" --version 1.0.13 --track "$t" --changelog-dir "$sandbox/changelogs" --dry-run true
  expect_status "track $t" nonzero
  expect_out "track $t" "refusing track '$t': this workflow uploads to testing tracks only"
  expect_calls "track $t" ""
done

run '{}' --aab "$foss" --version 1.0.13 --track alpha --changelog-dir "$sandbox/changelogs" --dry-run true
expect_status "FOSS-named bundle" nonzero
expect_out "FOSS-named bundle" "it is a FOSS artifact"
expect_calls "FOSS-named bundle" ""

run '{}' --aab "$wrongpkg" --version 1.0.13 --track alpha --changelog-dir "$sandbox/changelogs" --dry-run true
expect_status "wrong package" nonzero
expect_out "wrong package" "manifest package is 'com.example.evil', expected 'com.rousecontext'"
expect_calls "wrong package" ""

run '{}' --aab "$good" --version 1.0.12 --track alpha --changelog-dir "$sandbox/changelogs" --dry-run true
expect_status "version/file name mismatch" nonzero
expect_out "version/file name mismatch" "expected exactly 'rouse-context-1.0.12-google.aab'"
expect_calls "version/file name mismatch" ""

mkdir -p "$sandbox/misnamed"
make_aab "$sandbox/misnamed/rouse-context-1.0.13-google.aab" com.rousecontext 14 1.0.99
run '{}' --aab "$sandbox/misnamed/rouse-context-1.0.13-google.aab" --version 1.0.13 --track alpha \
  --changelog-dir "$sandbox/changelogs" --dry-run true
expect_status "manifest versionName mismatch" nonzero
expect_out "manifest versionName mismatch" "manifest versionName is '1.0.99', expected '1.0.13'"
expect_calls "manifest versionName mismatch" ""

printf 'not a zip' >"$sandbox/other/rouse-context-9.9.9-google.aab"
run '{}' --aab "$sandbox/other/rouse-context-9.9.9-google.aab" --version 9.9.9 --track alpha \
  --changelog-dir "$sandbox/changelogs" --dry-run true
expect_status "not a bundle" nonzero
expect_out "not a bundle" "is not a zip file"
expect_calls "not a bundle" ""

# Truncated fixed-width protobuf fields (wire types 1 and 5) must be reported
# as malformed, not silently read short.
mkdir -p "$sandbox/trunc"
for wt in 64:09 32:0d; do
  bits=${wt%%:*}
  printf -v key '\\x%s' "${wt#*:}"
  python3 - "$sandbox/trunc/rouse-context-1.0.13-google.aab" "$key" <<'PY'
import sys, zipfile
key = bytes([int(sys.argv[2][2:], 16)])
with zipfile.ZipFile(sys.argv[1], "w") as z:
    z.writestr("base/manifest/AndroidManifest.xml", key + b"\x01\x02")
PY
  run '{}' --aab "$sandbox/trunc/rouse-context-1.0.13-google.aab" --version 1.0.13 --track alpha \
    --changelog-dir "$sandbox/changelogs" --dry-run true
  expect_status "truncated ${bits}-bit field" nonzero
  expect_out "truncated ${bits}-bit field" "truncated ${bits}-bit field"
  expect_calls "truncated ${bits}-bit field" ""
done

run '{}' --aab "$sandbox/dist/rouse-context-1.0.14-google.aab" --version 1.0.14 --track alpha \
  --changelog-dir "$sandbox/changelogs" --dry-run true
expect_status "missing bundle" nonzero
expect_out "missing bundle" "bundle not found"
expect_calls "missing bundle" ""

run '{}' --aab "$good" --version 1.0.13 --track alpha --changelog-dir "$sandbox/nochangelogs" --dry-run true
expect_status "missing changelog" nonzero
expect_out "missing changelog" "no changelog for versionCode 14"
expect_calls "missing changelog" ""

: >"$sandbox/nochangelogs/14.txt"
run '{}' --aab "$good" --version 1.0.13 --track alpha --changelog-dir "$sandbox/nochangelogs" --dry-run true
expect_status "empty changelog" nonzero
expect_out "empty changelog" "is empty"
expect_calls "empty changelog" ""

run '{}' "${defaults[@]}" --status inProgress --dry-run true
expect_status "status inProgress" nonzero
expect_out "status inProgress" "release status 'inProgress' is not one of: completed, draft"
expect_calls "status inProgress" ""

TOKEN="" run '{}' "${defaults[@]}" --dry-run true
expect_status "no token" nonzero
expect_out "no token" "PLAY_ACCESS_TOKEN is not set"
expect_calls "no token" ""

echo
if [[ "$failures" -gt 0 ]]; then
  echo "play-upload self-test: $failures FAILURE(S)"
  exit 1
fi
echo "play-upload self-test: all cases passed"
