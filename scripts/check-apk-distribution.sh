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
# `--expect google` fails if NO Google/Firebase class references are present.
#                   That direction is not decoration: it is what catches the FOSS
#                   APK being staged twice under two names, which is the exact
#                   failure the shared output path makes possible. It is also a
#                   live positive control — the same patterns, run by the same
#                   code, must find Firebase in the Google build on every CI run,
#                   so this gate cannot quietly rot into one that matches nothing.
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

# The APK is scanned in TWO SCOPES, because the right pattern differs between
# them. Each pattern is paired with a sample string that MUST match it; the
# samples are not documentation, they are fed to the matcher as a synthetic
# fixture before the real scan, so a pattern that has stopped matching anything
# fails the run instead of silently passing every APK (#579's failure mode).
#
# DEX scope — `classes*.dex`. A class reference in DEX is a type descriptor and
# uses SLASHES: `Lcom/google/firebase/messaging/FirebaseMessaging;`. Dot-form
# strings inside DEX are something else entirely, and the FOSS build
# legitimately contains four of them (measured on the debug APK):
#
#     com.google.android.gms                              } security-provider
#     com.google.android.gms.org.conscrypt                 } name strings
#     com.google.android.gms.provider.action.PICK_IMAGES   } androidx.activity
#     com.google.android.gms.provider.extra.PICK_IMAGES_MAX} photo-picker Intent
#
# Those are Intent actions and provider names that AndroidX carries whether or
# not Play Services is installed; no GMS code is linked. Matching them would
# make this gate permanently red on a genuinely clean build. Requiring the slash
# form separates them cleanly: FOSS debug scores 0/0 where the Google build
# scores 2869/6571.
DEX_PATTERNS=(
  'com/google/firebase'
  'com/google/android/gms'
)
DEX_SAMPLES=(
  'Lcom/google/firebase/messaging/FirebaseMessaging;'
  'Lcom/google/android/gms/common/api/GoogleApiClient;'
)

# Everything-else scope — manifest, `resources.arsc`, `res/`, assets, META-INF.
# Here EITHER separator counts, because component class names are written in dot
# form and the FOSS build has genuinely zero of them. This is the half a
# DEX-only check would miss: the google-services plugin also generates
# `google_app_id` / `gcm_defaultSenderId` / `firebase_database_url` string
# resources from `google-services.json`, and Firebase ships `res/raw/
# firebase_*_keep.xml`. An APK could in principle carry the credentials without
# the classes; this notices.
META_PATTERNS=(
  'com[./]google[./]firebase'
  'com[./]google[./]android[./]gms'
  'google_app_id'
  'gcm_defaultSenderId'
  'firebase_database_url'
)
META_SAMPLES=(
  'com.google.firebase.provider.FirebaseInitProvider'
  'com.google.android.gms.measurement.AppMeasurementReceiver'
  'google_app_id'
  'gcm_defaultSenderId'
  'firebase_database_url'
)

# Counts OCCURRENCES, not matching lines. DEX is binary: `grep -c` collapses
# thousands of references into a handful of "lines" and understates by ~1000x
# (measured on this very issue -- 3 "lines" was really 2067 references).
#
# `scope` is `dex` (classes*.dex only), `meta` (everything else), or `all`.
grep_scoped() {
  local mode="$1" scope="$2" pattern="$3" root="$4"
  case "$scope" in
    dex)  LC_ALL=C grep "$mode" -aE -e "$pattern" "$root"/classes*.dex ;;
    meta) LC_ALL=C grep "$mode" -raE -e "$pattern" "$root" --exclude='classes*.dex' ;;
    all)  LC_ALL=C grep "$mode" -raE -e "$pattern" "$root" ;;
    *)    fail "internal: unknown scope '$scope'" ;;
  esac
}

count_occurrences() {
  local scope="$1" pattern="$2" root="$3" out status
  set +e
  out=$(grep_scoped -o "$scope" "$pattern" "$root" | wc -l)
  status="${PIPESTATUS[0]}"
  set -e
  if [ "$status" -gt 1 ]; then
    fail "grep exited $status scanning the $scope scope of '$root' for '$pattern'. The scan did not complete; this is a failure, not a pass."
  fi
  printf '%s' "$out"
}

files_matching() {
  local scope="$1" pattern="$2" root="$3" status
  set +e
  grep_scoped -l "$scope" "$pattern" "$root"
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
# Assigned, then printed. Substituted into the `echo`, the statuses of both
# `sha256sum` and `cut` are discarded, so an APK that could not be hashed would
# print "sha256      : " and this gate would go on to render a verdict about it
# anyway -- and this line is the identity of the artifact every finding below is
# attributed to. `set -o pipefail` makes the assignment cover both commands, and
# `sha256sum` writes its own reason to stderr, so an unhashable APK stops the
# gate WITH an explanation instead of silently losing its identity.
apk_sha=$(sha256sum "$APK" | cut -d' ' -f1)
echo "  sha256      : $apk_sha"
echo "  entries     : ${entry_count} files, ${dex_count} dex"
echo "  expecting   : ${EXPECT} distribution"
echo

# ---------------------------------------------------------------------------
# Self-test: prove every pattern still matches something before trusting a zero.
# ---------------------------------------------------------------------------
FIXTURE="$WORK/fixture"
mkdir -p "$FIXTURE"
printf '%s\n' "${DEX_SAMPLES[@]}" > "$FIXTURE/classes.dex"
printf '%s\n' "${META_SAMPLES[@]}" > "$FIXTURE/AndroidManifest.xml"

[ "${#DEX_PATTERNS[@]}" -eq "${#DEX_SAMPLES[@]}" ] ||
  fail "DEX_PATTERNS (${#DEX_PATTERNS[@]}) and DEX_SAMPLES (${#DEX_SAMPLES[@]}) are different lengths -- every pattern needs a sample that exercises it."
[ "${#META_PATTERNS[@]}" -eq "${#META_SAMPLES[@]}" ] ||
  fail "META_PATTERNS (${#META_PATTERNS[@]}) and META_SAMPLES (${#META_SAMPLES[@]}) are different lengths -- every pattern needs a sample that exercises it."

for pattern in "${DEX_PATTERNS[@]}"; do
  n=$(count_occurrences dex "$pattern" "$FIXTURE")
  [ "$n" -gt 0 ] ||
    fail "self-test: dex pattern '$pattern' matched nothing in the synthetic fixture. The matcher is broken -- a zero from it means nothing."
done
for pattern in "${META_PATTERNS[@]}"; do
  n=$(count_occurrences meta "$pattern" "$FIXTURE")
  [ "$n" -gt 0 ] ||
    fail "self-test: non-dex pattern '$pattern' matched nothing in the synthetic fixture. The matcher is broken -- a zero from it means nothing."
done
echo "  self-test   : all ${#DEX_PATTERNS[@]} dex + ${#META_PATTERNS[@]} non-dex patterns match the synthetic fixture"

# ---------------------------------------------------------------------------
# Control: prove we are scanning the app's real code, not an empty tree.
# ---------------------------------------------------------------------------
app_refs=$(count_occurrences dex 'com/rousecontext' "$EXTRACT")
[ "$app_refs" -gt 0 ] ||
  fail "control: found 0 references to com/rousecontext in the DEX of $APK. This is not (or no longer) the Rouse Context app, so nothing here can be trusted."
echo "  control     : ${app_refs} com/rousecontext references in the DEX"
echo

# ---------------------------------------------------------------------------
# The actual measurement.
# ---------------------------------------------------------------------------
total=0
hit_scopes=()
hit_patterns=()
scan() {
  local scope="$1"; shift
  local pattern n
  for pattern in "$@"; do
    n=$(count_occurrences "$scope" "$pattern" "$EXTRACT")
    printf '  %-5s %-34s %s occurrence(s)\n' "$scope" "$pattern" "$n"
    total=$((total + n))
    if [ "$n" -gt 0 ]; then
      hit_scopes+=("$scope")
      hit_patterns+=("$pattern")
    fi
  done
}
scan dex "${DEX_PATTERNS[@]}"
scan meta "${META_PATTERNS[@]}"
echo

if [ "$EXPECT" = "foss" ]; then
  if [ "$total" -gt 0 ]; then
    echo "Google/Firebase code found in a build published as FOSS." >&2
    echo >&2
    for i in "${!hit_patterns[@]}"; do
      echo "  ${hit_scopes[$i]} pattern: ${hit_patterns[$i]}" >&2
      files_matching "${hit_scopes[$i]}" "${hit_patterns[$i]}" "$EXTRACT" |
        sed "s|^$EXTRACT/|    in: |" >&2
    done
    echo >&2
    echo "  Google/Firebase packages linked in:" >&2
    # `set +e` around it rather than `|| true` after it -- the same shape
    # `count_occurrences` and `files_matching` above already use, and the reason
    # to prefer it here is that `|| true` puts `grep_scoped` in a condition,
    # where `set -e` is silently disabled for everything INSIDE the function too
    # (SC2310), including the `fail` in its unknown-scope arm.
    #
    # The status stays ignored either way, and deliberately: `grep` exits 1 on
    # no match, this is the diagnostic listing for a failure the `$total` test
    # above has ALREADY decided, and the `fail` on the next line is the verdict.
    # Letting the status escape would kill the gate between its header and its
    # verdict -- exit 1 with a half-written explanation, the #628 shape.
    set +e
    grep_scoped -o all 'com[./]google[./](firebase|android[./]gms)[./][a-zA-Z]+' "$EXTRACT" |
      sed 's/^.*://' | sort -u | sed 's/^/    /' >&2
    set -e
    fail "$total Google/Firebase marker occurrence(s) in $APK. A bare build must produce ZERO. Something set the \`google\` property (a -P flag, a gradle.properties line, or an ORG_GRADLE_PROJECT_google env var), or the wrong artifact was staged."
  fi
  echo "OK: no Google/Firebase markers in $(basename "$APK") -- FOSS distribution confirmed."
else
  # A resources-only match would pass a stray `google_app_id` off as a real
  # Google build, so require the two DEX class-reference markers specifically.
  fb=$(count_occurrences dex 'com/google/firebase' "$EXTRACT")
  gms=$(count_occurrences dex 'com/google/android/gms' "$EXTRACT")
  if [ "$fb" -eq 0 ] || [ "$gms" -eq 0 ]; then
    fail "$APK was expected to be the GOOGLE build but its DEX contains firebase=$fb gms=$gms class references. Either the -Pgoogle build did not take effect, or the FOSS APK was staged under the Google name (both variants write the same output path -- see release.yml)."
  fi
  echo "OK: Google/Firebase present in $(basename "$APK") (firebase=$fb, gms=$gms DEX refs) -- Google distribution confirmed."
  echo "    (this run also re-proves the FOSS gate's patterns still match real Firebase code)"
fi
