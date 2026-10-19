#!/usr/bin/env bash
# Uploads the Google-distribution app bundle of a release to a Google Play
# TESTING track through the Android Publisher API v3, in one edit.
#
# Called by .github/workflows/play-upload.yml, which authenticates with
# keyless Workload Identity Federation and hands this script a short-lived
# access token in PLAY_ACCESS_TOKEN. There is no JSON key anywhere.
#
# USAGE
#   PLAY_ACCESS_TOKEN=... scripts/play-upload.sh \
#     --aab dist/rouse-context-1.0.13-google.aab \
#     --version 1.0.13 \
#     --track alpha \
#     --changelog-dir <dir holding fastlane changelogs/<versionCode>.txt> \
#     [--status completed|draft] [--dry-run]
#
# FLOW (Android Publisher v3)
#   edits.insert -> edits.bundles.upload -> edits.tracks.update -> edits.validate
#   then  --dry-run : edits.delete. Never commits. (A dry run also prints
#                     edits.tracks.list right after insert, before any change.)
#         otherwise : edits.commit.
#   Any failure after the edit exists deletes the edit before exiting non-zero,
#   so a failed run never leaves a dangling edit behind.
#
# REFUSALS (all before any network call)
#   * track `production` (or `<form-factor>:production`) -- testing tracks only,
#     whatever the service account's grant happens to be;
#   * a bundle whose file name is not exactly rouse-context-<version>-google.aab
#     (in particular any FOSS artifact -- the FOSS build is never uploaded here);
#   * a bundle whose manifest package is not com.rousecontext, or whose
#     versionName is not <version>;
#   * a missing or empty changelog for the bundle's versionCode.
#
# API errors are printed in full -- HTTP status plus response body -- and fail
# the run. The token is read from the environment, written to a mode-600
# header file and passed to curl as `-H @file`, so it is never on a command
# line and never echoed.
#
# PLAY_API_ROOT overrides https://androidpublisher.googleapis.com; it exists for
# scripts/tests/play-upload-test.sh, which runs this against a mock server.

set -euo pipefail

readonly EXPECTED_PACKAGE=com.rousecontext
readonly NOTES_LANGUAGE=en-US

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
api_root=${PLAY_API_ROOT:-https://androidpublisher.googleapis.com}

die() {
  echo "ERROR: $*" >&2
  exit 1
}

aab="" version="" track="" changelog_dir="" status=completed dry_run=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --aab) aab=${2:?--aab needs a value}; shift 2 ;;
    --version) version=${2:?--version needs a value}; shift 2 ;;
    --track) track=${2:?--track needs a value}; shift 2 ;;
    --changelog-dir) changelog_dir=${2:?--changelog-dir needs a value}; shift 2 ;;
    --status) status=${2:?--status needs a value}; shift 2 ;;
    --dry-run) dry_run=true; shift ;;
    *) die "unknown argument: $1" ;;
  esac
done

[[ -n "$aab" ]] || die "--aab is required"
[[ -n "$version" ]] || die "--version is required"
[[ -n "$track" ]] || die "--track is required"
[[ -n "$changelog_dir" ]] || die "--changelog-dir is required"

# --- preflight: nothing below touches the network ------------------------------

track_lc=${track,,}
if [[ "$track_lc" == production || "$track_lc" == *:production ]]; then
  die "refusing track '$track': this workflow uploads to testing tracks only, never production."
fi
if [[ ! "$track" =~ ^[A-Za-z0-9._:-]+$ ]]; then
  die "track '$track' contains characters outside [A-Za-z0-9._:-]; refusing rather than URL-encoding a guess."
fi
case "$status" in
  completed | draft) ;;
  *) die "release status '$status' is not one of: completed, draft." ;;
esac

[[ -n "${PLAY_ACCESS_TOKEN:-}" ]] || die "PLAY_ACCESS_TOKEN is not set."

[[ -f "$aab" ]] || die "bundle not found: $aab"
expected_name="rouse-context-${version}-google.aab"
actual_name=$(basename "$aab")
if [[ "$actual_name" == *foss* ]]; then
  die "refusing '$actual_name': it is a FOSS artifact. Only the Google bundle is ever uploaded to Play."
fi
if [[ "$actual_name" != "$expected_name" ]]; then
  die "refusing '$actual_name': expected exactly '$expected_name' (the Google-distribution bundle)."
fi

if ! manifest=$(python3 "$here/lib/aab_manifest.py" "$aab"); then
  die "could not read the manifest of $aab (reason above)."
fi
package=$(sed -n 's/^package=//p' <<<"$manifest")
version_code=$(sed -n 's/^versionCode=//p' <<<"$manifest")
version_name=$(sed -n 's/^versionName=//p' <<<"$manifest")
if [[ "$package" != "$EXPECTED_PACKAGE" ]]; then
  die "refusing $actual_name: manifest package is '$package', expected '$EXPECTED_PACKAGE'."
fi
if [[ "$version_name" != "$version" ]]; then
  die "refusing $actual_name: manifest versionName is '$version_name', expected '$version'."
fi
[[ "$version_code" =~ ^[0-9]+$ ]] || die "manifest versionCode '$version_code' is not numeric."

notes_file="$changelog_dir/$version_code.txt"
[[ -f "$notes_file" ]] || die "no changelog for versionCode $version_code: $notes_file is missing."
[[ -s "$notes_file" ]] || die "changelog $notes_file is empty."

echo "Bundle : $actual_name"
echo "Package: $package  versionCode: $version_code  versionName: $version_name"
echo "Track  : $track  status: $status  dry run: $dry_run"
echo "Notes  : $notes_file"
echo

# --- API ------------------------------------------------------------------------

work=$(mktemp -d)
edit_id=""

cleanup() {
  local rc=$?
  if [[ -n "$edit_id" ]]; then
    echo "Deleting edit $edit_id after a failed run (exit $rc) so it is not left dangling." >&2
    local del
    set +e
    request DELETE "$base/edits/$edit_id" "$work/delete.json"
    del=$?
    set -e
    if [[ "$del" -ne 0 ]]; then
      echo "WARNING: could not delete edit $edit_id; it will expire on its own." >&2
    fi
  fi
  rm -rf "$work"
  exit "$rc"
}
trap cleanup EXIT

umask 077
printf 'Authorization: Bearer %s\n' "$PLAY_ACCESS_TOKEN" >"$work/auth.hdr"

base="$api_root/androidpublisher/v3/applications/$EXPECTED_PACKAGE"
upload_base="$api_root/upload/androidpublisher/v3/applications/$EXPECTED_PACKAGE"

# request METHOD URL OUTFILE [curl args...]
# Returns 0 only on a 2xx. Anything else prints the method, URL, status and the
# full response body, and returns 1. Never prints the token. Callers run it
# with errexit off and check the status themselves (see `api`).
request() {
  local method=$1 url=$2 out=$3 code
  shift 3
  if ! code=$(curl -sS -o "$out" -w '%{http_code}' -X "$method" \
    -H "@$work/auth.hdr" "$@" "$url"); then
    echo "ERROR: $method $url failed at the transport level (curl, above)." >&2
    return 1
  fi
  if [[ ! "$code" =~ ^2[0-9][0-9]$ ]]; then
    echo "ERROR: $method $url -> HTTP $code" >&2
    if [[ -s "$out" ]]; then
      cat "$out" >&2
      echo >&2
    else
      echo "(empty response body)" >&2
    fi
    return 1
  fi
}

# api LABEL METHOD URL OUTFILE [curl args...] -- `request`, dying with LABEL
# on failure. Dying runs the EXIT trap, which deletes the edit.
api() {
  local label=$1 rc
  shift
  set +e
  request "$@"
  rc=$?
  set -e
  [[ "$rc" -eq 0 ]] || die "$label failed."
}

# json_get FILE KEY -- top-level string/number field of a JSON object.
json_get() {
  python3 - "$1" "$2" <<'PY'
import json, sys
with open(sys.argv[1]) as f:
    v = json.load(f).get(sys.argv[2])
if v is None:
    sys.exit("missing field %r in %s" % (sys.argv[2], sys.argv[1]))
print(v)
PY
}

echo "== edits.insert"
api edits.insert POST "$base/edits" "$work/edit.json" \
  -H 'Content-Type: application/json' --data '{}'
edit_id=$(json_get "$work/edit.json" id)
echo "edit: $edit_id"

# On a dry run, list the tracks FIRST, so the real API names (is "Closed
# testing - Alpha" really `alpha`?) are printed even if the update below fails.
if [[ "$dry_run" == true ]]; then
  echo "== edits.tracks.list (dry run: the API names of this app's tracks)"
  api edits.tracks.list GET "$base/edits/$edit_id/tracks" "$work/tracks.json"
  python3 - "$work/tracks.json" <<'PY'
import json, sys
with open(sys.argv[1]) as f:
    data = json.load(f)
for t in data.get("tracks", []):
    rel = ", ".join("%s:%s" % (r.get("status"), "/".join(r.get("versionCodes", [])))
                    for r in t.get("releases", []))
    print("  %-30s %s" % (t.get("track"), rel or "(no releases)"))
PY
fi

echo "== edits.bundles.upload"
api edits.bundles.upload POST "$upload_base/edits/$edit_id/bundles?uploadType=media" \
  "$work/bundle.json" -H 'Content-Type: application/octet-stream' --data-binary "@$aab"
uploaded_code=$(json_get "$work/bundle.json" versionCode)
if [[ "$uploaded_code" != "$version_code" ]]; then
  die "Play reports versionCode $uploaded_code for the upload; the manifest says $version_code."
fi
echo "uploaded versionCode $uploaded_code"

echo "== edits.tracks.update ($track, $status)"
python3 - "$track" "$version_code" "$status" "$NOTES_LANGUAGE" "$notes_file" \
  >"$work/track-body.json" <<'PY'
import json, sys
track, code, status, lang, notes = sys.argv[1:6]
with open(notes, encoding="utf-8") as f:
    text = f.read().strip()
json.dump({"track": track, "releases": [{
    "versionCodes": [code],
    "status": status,
    "releaseNotes": [{"language": lang, "text": text}],
}]}, sys.stdout)
PY
api "edits.tracks.update for track '$track'" PUT "$base/edits/$edit_id/tracks/$track" \
  "$work/track.json" -H 'Content-Type: application/json' --data-binary "@$work/track-body.json"
cat "$work/track.json"
echo

echo "== edits.validate"
api edits.validate POST "$base/edits/$edit_id:validate" "$work/validate.json" --data ''
cat "$work/validate.json"
echo

if [[ "$dry_run" == true ]]; then
  echo "== edits.delete (dry run: nothing is committed)"
  # Cleared before the call so the EXIT trap does not try a second delete.
  id=$edit_id
  edit_id=""
  set +e
  request DELETE "$base/edits/$id" "$work/delete.json"
  rc=$?
  set -e
  if [[ "$rc" -ne 0 ]]; then
    die "dry run could not delete edit $id; it will expire on its own, but nothing was committed."
  fi
  echo "DRY RUN OK: edit $id validated and deleted; nothing was committed."
  exit 0
fi

echo "== edits.commit"
api edits.commit POST "$base/edits/$edit_id:commit" "$work/commit.json" --data ''
edit_id=""
cat "$work/commit.json"
echo
echo "COMMITTED: versionCode $version_code to track '$track' ($status)."
