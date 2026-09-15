#!/usr/bin/env bash
# Regression tests for scripts/check-apk-distribution.sh (#705, #548).
#
# WHY THIS FILE EXISTS
# --------------------
# `check-apk-distribution.sh` is the FOSS-purity gate: release.yml runs it as
# "Staged FOSS APK contains no Google/Firebase code", and it is the whole of
# #548's answer. Until this file, it had never been observed going RED. That is
# the distinction half this repo's gate work is about -- #678's step-order gate
# ran on every PR for months while exiting 1 with zero output on the two inputs
# it existed to catch, and #686's backfill exited 0 after a failed push. A gate
# that has only ever been seen passing is indistinguishable from one that
# cannot fail, and this particular one guards a property (no Firebase in the
# F-Droid artifact) whose violation would be invisible until somebody
# decompiled a shipped APK.
#
# So every case below is a demonstrated control in BOTH directions: a fixture
# that CONTAINS what the gate exists to catch, asserted red and asserted to NAME
# what it found; and a clean fixture asserted green.
#
# NO REAL APK IS BUILT OR DOWNLOADED. The gate reads an APK as a zip of files
# and greps the bytes, so a zip of text files exercises every path it has --
# including the size floor, the dex-presence check, and the `com/rousecontext`
# control. Building a real APK here would make this suite need a JDK, an SDK
# and four minutes, and would test Gradle rather than the gate.

set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
gate=$repo_root/scripts/check-apk-distribution.sh

sandbox=$(mktemp -d)
trap 'rm -rf "$sandbox"' EXIT

failures=0
out_file=$sandbox/gate-output.txt
stage_dir=$sandbox/stage
run_status=0
run_ok=0
has_verdict=
has_status=0

if ! command -v zip >/dev/null 2>&1; then
  echo "FAIL: 'zip' is not installed; the fixture APKs cannot be built." >&2
  echo "      Skipping would make this suite green by absence, so it fails." >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# Reading the gate's output.
#
# Deliberately `grep` over a FILE, never `printf ... | grep -q` (#716/#725).
# That pipeline has three outcomes the caller cannot tell apart: grep found
# nothing; grep never answered; or grep exited early ON A MATCH and the writer
# took SIGPIPE, which `pipefail` then reports as the pipeline's status and `!`
# inverts into "no match". The last is payload-size dependent -- measured clean
# at 4.4KB and failing 15116 times in 20000 at 32KB -- and this gate's failure
# output grows with the number of markers it found, which is precisely the case
# these assertions run on. With no pipe there is no writer to kill, so the shape
# cannot arise; grep's own status is read, and a status above 1 (it could not
# answer) is reported as its own thing rather than folded into "absent".
# ---------------------------------------------------------------------------
out_has() {
  set +e
  grep -qF -e "$1" "$out_file"
  has_status=$?
  set -e
  case "$has_status" in
    0) has_verdict=yes ;;
    1) has_verdict=no ;;
    *) has_verdict=broken ;;
  esac
}

fail_case() {
  echo "FAIL: $*"
  sed 's/^/    /' "$out_file"
  failures=$((failures + 1))
}

# ---------------------------------------------------------------------------
# Fixture APKs.
#
# `stage_clean` lays out the minimum the gate demands of ANY artifact before it
# will render a verdict: a top-level classes.dex, app code inside it, and a
# megabyte of bulk. Individual cases then add the markers they are about.
#
# The padding is stored (`zip -0`) rather than deflated because the gate's size
# floor reads the FILE, and a megabyte of one repeated byte compresses to
# nothing. It is a run of 'A': no pattern in the gate matches it, so it cannot
# accidentally become the thing a case is measuring.
# ---------------------------------------------------------------------------
stage_clean() {
  rm -rf "$stage_dir"
  mkdir -p "$stage_dir/res/raw" "$stage_dir/assets" "$stage_dir/META-INF"
  # DEX type descriptors: slash-form, `L...;`-wrapped, as the real thing is.
  printf '%s\n' \
    'Lcom/rousecontext/app/MainActivity;' \
    'Lcom/rousecontext/app/ui/AppNavigation;' \
    'Lcom/rousecontext/core/mcp/McpServer;' > "$stage_dir/classes.dex"
  printf '%s\n' '<manifest package="com.rousecontext"><application/></manifest>' \
    > "$stage_dir/AndroidManifest.xml"
  printf '%s\n' 'app_name' 'rouse_context_label' > "$stage_dir/resources.arsc"
  printf '%s\n' 'MANIFEST-VERSION: 1.0' > "$stage_dir/META-INF/MANIFEST.MF"
  head -c 1100000 /dev/zero | tr '\0' 'A' > "$stage_dir/assets/pad.bin"
}

# seal <name> -> the path of the fixture APK, in $apk
seal() {
  apk=$sandbox/$1
  rm -f "$apk"
  ( cd "$stage_dir" && zip -q -0 -r -X "$apk" . )
}

# ---------------------------------------------------------------------------
# run <want-exit> <label> <gate args...>
#
# Reports a status mismatch itself and sets `run_ok`, so the content assertions
# below can bail without putting a function in a condition (SC2310).
# ---------------------------------------------------------------------------
run() {
  local want=$1 label=$2
  shift 2
  set +e
  bash "$gate" "$@" > "$out_file" 2>&1
  run_status=$?
  set -e
  if [ "$run_status" -ne "$want" ]; then
    run_ok=0
    fail_case "$label -- expected exit $want, got $run_status"
    return
  fi
  run_ok=1
  echo "ok: $label (exit $run_status)"
}

# must_say <label> <fixed string> ...
#
# Exiting non-zero is not enough: #678's gate exited 1 with zero bytes of
# output, which reads as a finding and is a crash. A red run has to NAME what
# it found, or nobody can act on it.
must_say() {
  local label=$1 s
  shift
  if [ "$run_ok" -ne 1 ]; then
    return
  fi
  for s in "$@"; do
    out_has "$s"
    if [ "$has_verdict" = broken ]; then
      fail_case "$label -- the output read itself failed (grep exited $has_status)"
      return
    fi
    if [ "$has_verdict" = no ]; then
      fail_case "$label -- output never contained: $s"
      return
    fi
  done
  echo "ok: $label names what it found"
}

# must_not_say <label> <fixed string> ...
must_not_say() {
  local label=$1 s
  shift
  if [ "$run_ok" -ne 1 ]; then
    return
  fi
  for s in "$@"; do
    out_has "$s"
    if [ "$has_verdict" = broken ]; then
      fail_case "$label -- the output read itself failed (grep exited $has_status)"
      return
    fi
    if [ "$has_verdict" = yes ]; then
      fail_case "$label -- output should not have contained: $s"
      return
    fi
  done
  echo "ok: $label stays silent about $*"
}

# ===========================================================================
# Part 1 -- RED. Each fixture contains the thing the gate exists to catch.
# ===========================================================================

# The headline case: Firebase class references in the DEX of an APK published
# as FOSS. This is the shape an ambient ORG_GRADLE_PROJECT_google, a stray
# gradle.properties line, or a mis-ordered stage step in release.yml produces.
stage_clean
printf '%s\n' \
  'Lcom/google/firebase/messaging/FirebaseMessaging;' \
  'Lcom/google/firebase/iid/FirebaseInstanceId;' >> "$stage_dir/classes.dex"
seal firebase-dex.apk
run 1 'firebase DEX refs fail --expect foss' --apk "$apk" --expect foss
must_say 'firebase DEX refs fail --expect foss' \
  'Google/Firebase code found in a build published as FOSS.' \
  'dex pattern: com/google/firebase' \
  'in: classes.dex' \
  'Google/Firebase packages linked in:' \
  'com/google/firebase/messaging' \
  'APK DISTRIBUTION CHECK FAILED (issue #548)'
# A gate that says FAILED and OK in the same run has told the reader nothing.
must_not_say 'firebase DEX refs fail --expect foss' 'FOSS distribution confirmed'

# Play Services without Firebase. Separate pattern, separate case: an
# alternation that silently loses one arm is the #579 shape.
stage_clean
printf '%s\n' \
  'Lcom/google/android/gms/common/api/GoogleApiClient;' \
  'Lcom/google/android/gms/tasks/Task;' >> "$stage_dir/classes.dex"
seal gms-dex.apk
run 1 'gms DEX refs fail --expect foss' --apk "$apk" --expect foss
must_say 'gms DEX refs fail --expect foss' \
  'dex pattern: com/google/android/gms' \
  'com/google/android/gms/common'

# The half a DEX-only check would miss, and the reason the gate has two scopes:
# the google-services plugin generates `google_app_id` / `gcm_defaultSenderId` /
# `firebase_database_url` STRING RESOURCES from google-services.json, and
# Firebase ships res/raw/firebase_*_keep.xml. An APK can carry the project
# credentials with no Firebase classes linked at all, and that is still not a
# FOSS artifact. One case per resource marker -- each is its own pattern.
for marker in google_app_id gcm_defaultSenderId firebase_database_url; do
  stage_clean
  printf '%s\n' "$marker" >> "$stage_dir/resources.arsc"
  seal "res-$marker.apk"
  run 1 "resources-only $marker fails --expect foss" --apk "$apk" --expect foss
  must_say "resources-only $marker fails --expect foss" \
    "meta  $marker" \
    'in: resources.arsc'
done

# Component class names in the manifest are written in DOT form, which is why
# the non-dex scope accepts either separator. A manifest naming
# FirebaseInitProvider is a Firebase-initialising APK whatever its DEX holds.
stage_clean
printf '%s\n' \
  '<provider android:name="com.google.firebase.provider.FirebaseInitProvider"/>' \
  >> "$stage_dir/AndroidManifest.xml"
seal manifest-dotform.apk
run 1 'dot-form manifest provider fails --expect foss' --apk "$apk" --expect foss
must_say 'dot-form manifest provider fails --expect foss' \
  'meta  com[./]google[./]firebase' \
  'in: AndroidManifest.xml'

# res/raw/firebase_common_keep.xml -- shipped by the Firebase AARs, so it is
# present whenever they are linked, and it is a filename rather than content.
stage_clean
printf '%s\n' '<resources><item>com.google.firebase.components</item></resources>' \
  > "$stage_dir/res/raw/firebase_common_keep.xml"
seal res-raw-keep.apk
run 1 'res/raw firebase keep file fails --expect foss' --apk "$apk" --expect foss
must_say 'res/raw firebase keep file fails --expect foss' \
  'in: res/raw/firebase_common_keep.xml'

# The OTHER direction, and it is not decoration: both variants write the same
# unprefixed output path, so staging the FOSS APK twice under two names is a
# live failure mode (see release.yml). `--expect google` over a clean APK is
# what notices, and it doubles as the positive control that keeps the FOSS
# patterns from rotting into ones that match nothing.
stage_clean
seal clean-for-google.apk
run 1 'a clean APK fails --expect google' --apk "$apk" --expect google
must_say 'a clean APK fails --expect google' \
  'was expected to be the GOOGLE build' \
  'firebase=0 gms=0'

# Firebase but no Play Services: `--expect google` requires BOTH class-reference
# markers, so a resources-only or half-linked artifact cannot pass as the Google
# build.
stage_clean
printf '%s\n' 'Lcom/google/firebase/messaging/FirebaseMessaging;' >> "$stage_dir/classes.dex"
seal firebase-only.apk
run 1 'firebase without gms fails --expect google' --apk "$apk" --expect google
must_say 'firebase without gms fails --expect google' 'firebase=1 gms=0'

# A `google_app_id` string with no linked classes must not pass as the Google
# build either -- the `--expect google` arm counts DEX class references
# specifically, for exactly this reason.
stage_clean
printf '%s\n' 'google_app_id' >> "$stage_dir/resources.arsc"
seal res-only-for-google.apk
run 1 'resources-only markers do not satisfy --expect google' --apk "$apk" --expect google
must_say 'resources-only markers do not satisfy --expect google' 'firebase=0 gms=0'

# ===========================================================================
# Part 2 -- RED on the preflight. "Clean" must mean "we looked", and every one
# of these is a way to look at nothing and call it clean (#577/#579).
# ===========================================================================

run 1 'a missing APK fails' --apk "$sandbox/does-not-exist.apk" --expect foss
must_say 'a missing APK fails' 'APK not found' 'nothing was inspected'

printf 'tiny\n' > "$sandbox/tiny.apk"
run 1 'an implausibly small APK fails' --apk "$sandbox/tiny.apk" --expect foss
must_say 'an implausibly small APK fails' 'too small to be a real build'

head -c 1100000 /dev/zero | tr '\0' 'Z' > "$sandbox/not-a-zip.apk"
run 1 'an unreadable (non-zip) APK fails' --apk "$sandbox/not-a-zip.apk" --expect foss
must_say 'an unreadable (non-zip) APK fails' 'the APK could not be read'

# A zip with no classes*.dex: the code is not where the gate looks, so its
# verdict would be about nothing.
stage_clean
rm -f "$stage_dir/classes.dex"
seal no-dex.apk
run 1 'an APK with no classes.dex fails' --apk "$apk" --expect foss
must_say 'an APK with no classes.dex fails' 'no classes*.dex'

# The control that separates "scanned the app and found nothing" from "scanned
# something that is not the app". A DEX with no com/rousecontext in it is the
# wrong artifact, and a clean verdict over it means nothing.
stage_clean
printf '%s\n' 'Landroidx/core/app/NotificationCompat;' > "$stage_dir/classes.dex"
seal not-the-app.apk
run 1 'an APK that is not this app fails the control' --apk "$apk" --expect foss
must_say 'an APK that is not this app fails the control' \
  'control: found 0 references to com/rousecontext'

# ===========================================================================
# Part 3 -- GREEN. The cases that must NOT fire, or the gate gets loosened back
# into uselessness by the first person it blocks.
# ===========================================================================

stage_clean
seal clean-foss.apk
run 0 'a clean fixture passes --expect foss' --apk "$apk" --expect foss
must_say 'a clean fixture passes --expect foss' \
  'FOSS distribution confirmed' \
  'self-test   : all 2 dex + 5 non-dex patterns match the synthetic fixture' \
  'control     : 3 com/rousecontext references in the DEX'

# The four dot-form GMS strings a genuinely clean FOSS build carries in its DEX:
# AndroidX's photo-picker Intent actions and the Conscrypt provider name. No GMS
# code is linked. Requiring the SLASH form in the dex scope is what separates
# them, and it is the single judgement in this gate most likely to be "tidied"
# later by someone who reads `com.google.android.gms` and assumes a bug -- so it
# is pinned here rather than left to the comment that explains it.
stage_clean
printf '%s\n' \
  'com.google.android.gms' \
  'com.google.android.gms.org.conscrypt' \
  'com.google.android.gms.provider.action.PICK_IMAGES' \
  'com.google.android.gms.provider.extra.PICK_IMAGES_MAX' >> "$stage_dir/classes.dex"
seal dotform-dex.apk
run 0 'dot-form GMS strings in the DEX are not a violation' --apk "$apk" --expect foss
must_say 'dot-form GMS strings in the DEX are not a violation' 'FOSS distribution confirmed'

# The flip side of the same judgement, so the asymmetry is measured rather than
# assumed: the SAME string in the manifest IS a violation, because a component
# name there means a linked component.
stage_clean
printf '%s\n' '<service android:name="com.google.android.gms.measurement.AppMeasurementService"/>' \
  >> "$stage_dir/AndroidManifest.xml"
seal dotform-manifest.apk
run 1 'the same dot-form string in the manifest IS a violation' --apk "$apk" --expect foss
must_say 'the same dot-form string in the manifest IS a violation' 'in: AndroidManifest.xml'

# A real Google build: both class-reference markers present.
stage_clean
printf '%s\n' \
  'Lcom/google/firebase/messaging/FirebaseMessaging;' \
  'Lcom/google/android/gms/common/api/GoogleApiClient;' >> "$stage_dir/classes.dex"
printf '%s\n' 'google_app_id' 'gcm_defaultSenderId' >> "$stage_dir/resources.arsc"
seal google.apk
run 0 'a Google fixture passes --expect google' --apk "$apk" --expect google
must_say 'a Google fixture passes --expect google' \
  'Google distribution confirmed' \
  'firebase=1' \
  'gms=1'

# ===========================================================================
# Part 4 -- usage errors exit 2, not 1. The distinction is what stops a
# mistyped invocation in a workflow from reading as a purity finding.
# ===========================================================================

stage_clean
seal usage.apk
run 2 'a missing --expect is a usage error' --apk "$apk"
must_say 'a missing --expect is a usage error' "--expect must be 'foss' or 'google'"
run 2 'a bogus --expect is a usage error' --apk "$apk" --expect fosss
must_say 'a bogus --expect is a usage error' "got 'fosss'"
run 2 'a missing --apk is a usage error' --expect foss
must_say 'a missing --apk is a usage error' '--apk is required'
run 2 'an unknown argument is a usage error' --apk "$apk" --expect foss --wat
must_say 'an unknown argument is a usage error' "unknown argument '--wat'"

# ===========================================================================
# Part 5 -- controls on the gate's OWN anti-vacuity self-test.
#
# The gate proves its patterns still match before trusting a zero from them,
# which is the guard that stops it passing every APK after a pattern typo
# (#579). A guard nobody has watched fire is worth what an unfired one is, so
# each is made to fire against a deliberately broken copy of the gate.
# ===========================================================================

broken_gate=$sandbox/broken-gate.sh

# mutate <sed-program> -- rebuild the broken copy, and require the edit to have
# taken. A mutation that quietly matched nothing would leave the copy identical
# to the gate, and the control would then "pass" while measuring nothing.
mutate() {
  cp "$gate" "$broken_gate"
  sed -i "$1" "$broken_gate"
  set +e
  cmp -s "$gate" "$broken_gate"
  local same=$?
  set -e
  if [ "$same" -eq 0 ]; then
    echo "FAIL: the mutation '$1' did not change the gate -- the control below would be vacuous"
    failures=$((failures + 1))
    return
  fi
  echo "ok: mutation '$1' took"
}

# A dex pattern that no longer matches its own sample. Every APK would score
# zero against it, so every APK would look clean.
stage_clean
seal selftest.apk
mutate "s|^  'com/google/firebase'$|  'com/google/firebase/NOPE'|"
set +e
bash "$broken_gate" --apk "$apk" --expect foss > "$out_file" 2>&1
run_status=$?
set -e
run_ok=1
if [ "$run_status" -eq 0 ]; then
  run_ok=0
  fail_case 'a dex pattern that matches nothing is caught by the self-test -- gate exited 0'
fi
must_say 'a dex pattern that matches nothing is caught by the self-test' \
  "self-test: dex pattern 'com/google/firebase/NOPE' matched nothing"

# The same for the non-dex scope.
# Ranged to the FIRST match on purpose: `'google_app_id'` appears twice in the
# gate, once as a pattern and once as its own sample, and rewriting both leaves
# the pair still matching -- a mutation that looks applied and measures nothing.
# META_PATTERNS precedes META_SAMPLES, so the first is the pattern.
mutate "0,/^  'google_app_id'\$/s//  'google_app_id_NOPE'/"
set +e
bash "$broken_gate" --apk "$apk" --expect foss > "$out_file" 2>&1
run_status=$?
set -e
run_ok=1
if [ "$run_status" -eq 0 ]; then
  run_ok=0
  fail_case 'a non-dex pattern that matches nothing is caught by the self-test -- gate exited 0'
fi
must_say 'a non-dex pattern that matches nothing is caught by the self-test' \
  'self-test: non-dex pattern'

# A pattern added without a sample. The arrays are compared by length before
# anything is scanned, so an unexercised pattern stops the gate instead of
# riding along untested.
mutate "s|^  'com/google/android/gms'$|  'com/google/android/gms'\n  'com/google/ads'|"
set +e
bash "$broken_gate" --apk "$apk" --expect foss > "$out_file" 2>&1
run_status=$?
set -e
run_ok=1
if [ "$run_status" -eq 0 ]; then
  run_ok=0
  fail_case 'a pattern with no sample is caught -- gate exited 0'
fi
must_say 'a pattern with no sample is caught' \
  'every pattern needs a sample that exercises it'

if [ "$failures" -gt 0 ]; then
  echo "$failures test(s) failed"
  exit 1
fi
echo "All check-apk-distribution.sh tests passed"
