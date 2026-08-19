#!/usr/bin/env bash
# Verifies that a built APK really is the distribution it is published as (#548).
#
# WHY THIS EXISTS
# ---------------
# Distribution is a build flag, not a product flavor (#467): a bare build is the
# FOSS distribution, `-Pgoogle` is the credentialed Firebase build. Until this
# gate existed, NOTHING checked the result. The invariant "the asset published as
# `rouse-context-<v>-foss.apk` contains zero Firebase / Play Services code" was
# believed, not measured, and it is flippable by things nobody reviews: an
# ambient `ORG_GRADLE_PROJECT_google`, a stray line in a gradle.properties, or a
# reordering of release.yml's build/stage steps (both variants write the SAME
# unprefixed output path, so step order is what tells them apart).
#
# So this reads the artifact itself. Whatever flipped the gate — flag, property,
# environment, or a broken copy step — shows up as Google code inside the APK.
#
# USAGE
# -----
#   scripts/check-apk-distribution.sh --apk <path> --expect foss
#   scripts/check-apk-distribution.sh --apk <path> --expect google
#
# `--expect foss`   fails if ANY Google/Firebase marker is present.
# `--expect google` fails if NO Google/Firebase marker is present. That direction
#                   is not decoration: it is what catches the FOSS APK being
#                   staged twice under two names, which is the exact failure the
#                   shared output path makes possible. It is also a live positive
#                   control — the same patterns, run by the same code, must find
#                   Firebase in the Google build on every CI run, so this gate
#                   cannot quietly rot into one that matches nothing.
#
# HOW IT FAILS
# ------------
# Loudly and specifically, and it refuses to report a pass it did not earn:
#   * APK missing / unreadable / implausibly small        -> fail
#   * unzip produced no entries, or no classes*.dex       -> fail
#   * grep exits >1 (a real error, not "no match")        -> fail
#   * a pattern matches nothing in the synthetic fixture  -> fail (self-test)
#   * app code (com/rousecontext) absent from the DEX     -> fail (control)
# The last two are the point. A purity check that passes because it scanned an
# empty directory, or because a pattern silently stopped matching, is worse than
# no check at all -- this repo has had that failure twice (#577, #579). Both
# controls run BEFORE the verdict, so "clean" always means "we looked, with a
# matcher we proved works, at a corpus we proved is the app".

set -euo pipefail

APK=""
EXPECT=""

while [ $# -gt 0 ]; do
  case "$1" in
    --apk)    APK="${2:-}"; shift 2 ;;
    --expect) EXPECT="${2:-}"; shift 2 ;;
    -h|--help)
      grep -E '^#( |$)' "$0" | cut -c3-
      exit 0 ;;
    *)
      echo "ERROR: unknown argument '$1'" >&2
      echo "usage: $0 --apk <path> --expect foss|google" >&2
      exit 2 ;;
  esac
done

[ -n "$APK" ]    || { echo "ERROR: --apk is required" >&2; exit 2; }
case "$EXPECT" in
  foss|google) ;;
  *) echo "ERROR: --expect must be 'foss' or 'google' (got '${EXPECT}')" >&2; exit 2 ;;
esac

fail() {
  echo >&2
  echo "APK DISTRIBUTION CHECK FAILED (issue #548)" >&2
  echo "  $*" >&2
  echo >&2
  exit 1
}

# Google/Firebase markers, each paired with a sample string that MUST match it.
# The sample is not documentation: it is fed to the matcher as a synthetic
# fixture before the real scan, so a pattern that has stopped matching anything
# fails the run instead of silently passing every APK (#579's failure mode).
#
# Patterns are anchored on `com.google.` / `com/google/` on purpose. The FOSS
# build legitimately contains the substring "firebase" in two unrelated places —
# our own `firebaseToken` wire field name, and UnifiedPush's own
# `...FirebaseReceiver` — so a bare `firebase` pattern would be a permanent false
# positive. `com.google.android.material` is likewise legitimate and is why the
# Play Services pattern spells out `.../gms`.
#
# The `google_app_id` / `gcm_defaultSenderId` / `firebase_database_url` entries
# catch the other half of a leak: those string resources are generated from
# `google-services.json` by the google-services plugin and land in
# `resources.arsc`, not in the DEX. An APK could in principle carry the
# credentials without the classes; this notices.
PATTERNS=(
  'com[./]google[./]firebase'
  'com[./]google[./]android[./]gms'
  'google_app_id'
  'gcm_defaultSenderId'
  'firebase_database_url'
)
SAMPLES=(
  'Lcom/google/firebase/messaging/FirebaseMessaging;'
  'com.google.android.gms.common.api.GoogleApiClient'
  'google_app_id'
  'gcm_defaultSenderId'
  'firebase_database_url'
)

# Counts OCCURRENCES, not matching lines. DEX is binary: `grep -c` collapses
# thousands of references into a handful of "lines" and understates by ~1000x
# (measured on this very issue -- 3 "lines" was really 2067 references).
count_occurrences() {
  local pattern="$1" root="$2" out status
  set +e
  out=$(LC_ALL=C grep -roaE -e "$pattern" "$root" | wc -l)
  status="${PIPESTATUS[0]}"
  set -e
  if [ "$status" -gt 1 ]; then
    fail "grep exited $status scanning '$root' for '$pattern'. The scan did not complete; this is a failure, not a pass."
  fi
  printf '%s' "$out"
}

files_matching() {
  local pattern="$1" root="$2" status
  set +e
  LC_ALL=C grep -rlaE -e "$pattern" "$root"
  status=$?
  set -e
  [ "$status" -le 1 ] || fail "grep exited $status listing files for '$pattern'."
}

# ---------------------------------------------------------------------------
# Preflight: the artifact must exist and be a plausible APK.
# ---------------------------------------------------------------------------
[ -f "$APK" ] || fail "APK not found: $APK (nothing was inspected)"
apk_bytes=$(stat -c %s "$APK")
[ "$apk_bytes" -ge 1000000 ] ||
  fail "APK is only ${apk_bytes} bytes: $APK -- too small to be a real build, refusing to call it clean."

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
EXTRACT="$WORK/apk"
mkdir -p "$EXTRACT"

unzip -q -o "$APK" -d "$EXTRACT" ||
  fail "unzip failed on $APK -- the APK could not be read, so nothing was verified."

entry_count=$(find "$EXTRACT" -type f | wc -l)
[ "$entry_count" -gt 0 ] || fail "unzip produced no files from $APK -- nothing was scanned."

dex_count=$(find "$EXTRACT" -maxdepth 1 -name 'classes*.dex' -type f | wc -l)
[ "$dex_count" -gt 0 ] ||
  fail "no classes*.dex in $APK -- the code is not where this gate looks, so its verdict would be meaningless."

echo "Checking $(basename "$APK")"
echo "  path        : $APK"
echo "  size        : ${apk_bytes} bytes"
echo "  sha256      : $(sha256sum "$APK" | cut -d' ' -f1)"
echo "  entries     : ${entry_count} files, ${dex_count} dex"
echo "  expecting   : ${EXPECT} distribution"
echo

# ---------------------------------------------------------------------------
# Self-test: prove every pattern still matches something before trusting a zero.
# ---------------------------------------------------------------------------
FIXTURE="$WORK/fixture"
mkdir -p "$FIXTURE"
printf '%s\n' "${SAMPLES[@]}" > "$FIXTURE/synthetic-google-markers.txt"

[ "${#PATTERNS[@]}" -eq "${#SAMPLES[@]}" ] ||
  fail "PATTERNS (${#PATTERNS[@]}) and SAMPLES (${#SAMPLES[@]}) are different lengths -- every pattern needs a sample that exercises it."

for i in "${!PATTERNS[@]}"; do
  n=$(count_occurrences "${PATTERNS[$i]}" "$FIXTURE")
  [ "$n" -gt 0 ] ||
    fail "self-test: pattern '${PATTERNS[$i]}' matched nothing in the synthetic fixture. The matcher is broken -- a zero from it means nothing."
done
echo "  self-test   : all ${#PATTERNS[@]} patterns match the synthetic fixture"

# ---------------------------------------------------------------------------
# Control: prove we are scanning the app's real code, not an empty tree.
# ---------------------------------------------------------------------------
app_refs=$(count_occurrences 'com[./]rousecontext' "$EXTRACT")
[ "$app_refs" -gt 0 ] ||
  fail "control: found 0 references to com/rousecontext in $APK. This is not (or no longer) the Rouse Context app, so nothing here can be trusted."
echo "  control     : ${app_refs} com/rousecontext references present"
echo

# ---------------------------------------------------------------------------
# The actual measurement.
# ---------------------------------------------------------------------------
total=0
declare -a hits=()
for i in "${!PATTERNS[@]}"; do
  pattern="${PATTERNS[$i]}"
  n=$(count_occurrences "$pattern" "$EXTRACT")
  printf '  %-34s %s occurrence(s)\n' "$pattern" "$n"
  total=$((total + n))
  [ "$n" -gt 0 ] && hits+=("$pattern")
done
echo

if [ "$EXPECT" = "foss" ]; then
  if [ "$total" -gt 0 ]; then
    echo "Google/Firebase code found in a build published as FOSS." >&2
    echo >&2
    for pattern in "${hits[@]}"; do
      echo "  pattern: $pattern" >&2
      files_matching "$pattern" "$EXTRACT" | sed "s|^$EXTRACT/|    in: |" >&2
    done
    echo >&2
    echo "  Firebase packages linked in:" >&2
    LC_ALL=C grep -roaE -e 'com[./]google[./]firebase[./][a-z]+' "$EXTRACT" |
      sed 's/^.*://' | sort -u | sed 's/^/    /' >&2 || true
    fail "$total Google/Firebase marker occurrence(s) in $APK. A bare \`assembleRelease\` must produce ZERO. Something set the \`google\` property (a -P flag, a gradle.properties line, or an ORG_GRADLE_PROJECT_google env var), or the wrong artifact was staged."
  fi
  echo "OK: no Google/Firebase markers in $(basename "$APK") -- FOSS distribution confirmed."
else
  # `google_app_id` alone would pass a resources-only leak off as a real Google
  # build, so require the two class-package markers specifically.
  fb=$(count_occurrences 'com[./]google[./]firebase' "$EXTRACT")
  gms=$(count_occurrences 'com[./]google[./]android[./]gms' "$EXTRACT")
  if [ "$fb" -eq 0 ] || [ "$gms" -eq 0 ]; then
    fail "$APK was expected to be the GOOGLE build but contains firebase=$fb gms=$gms references. Either the -Pgoogle build did not take effect, or the FOSS APK was staged under the Google name (both variants write the same output path -- see release.yml)."
  fi
  echo "OK: Google/Firebase present in $(basename "$APK") (firebase=$fb, gms=$gms) -- Google distribution confirmed."
  echo "    (this run also re-proves the FOSS gate's patterns still match real Firebase code)"
fi
