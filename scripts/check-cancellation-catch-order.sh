#!/usr/bin/env bash
# Fails if a BROAD catch clause in production Kotlin is not preceded, in its own
# try/catch chain, by a clause that catches `CancellationException` (#674).
#
# THE RULE
# --------
# "Broad" is any clause whose type can receive a `CancellationException`:
#
#     Exception   Throwable   RuntimeException   IllegalStateException
#
# `IllegalStateException` is the dangerous member. `CancellationException`
# extends it on the JVM -- the chain is
# `CancellationException -> IllegalStateException -> RuntimeException ->
# Exception -> Throwable` -- so `catch (e: IllegalStateException)` READS as a
# narrow, deliberate catch and silently swallows cancellation. Whoever wrote it
# believed they had constrained what they were handling and had not.
#
# Swallowing cancellation means work continues after the scope is gone: a
# retry loop that keeps retrying, a "failed" state written into a torn-down
# ViewModel, a crash record persisted for a user who simply navigated away.
# `.claude/rules/coroutines.md` requires propagation.
#
# WHY A GATE AND NOT A CONVENTION
# -------------------------------
# Six human-directed sweeps in two days (#660, #662/#665, #666/#669, #670/#673,
# #638/#672, #667) each found what the previous one had not looked at. The
# property is SOURCE ORDER within one chain, and Kotlin diagnoses neither an
# unreachable nor a mis-ordered catch clause, so nothing but a gate enforces it.
#
# HOW IT DECIDES
# --------------
# `scripts/lib/kotlin-catch-chains.awk` -- read its header -- strips comments and
# string literals, then walks tokens tracking brace depth, so it answers "is
# there an EARLIER clause of the SAME chain that catches CancellationException"
# rather than "is there such a line nearby". Both matter: this tree contains a
# four-line clause (`McpTool.kt:170`) that no line regex parses, and adjacent
# chains whose clauses a line regex would credit to each other.
#
# Both spellings of the guard count -- bare `CancellationException` and the
# qualified `kotlinx.coroutines.` / `java.util.concurrent.` forms are all live
# in this tree, and a gate matching one misses the other (#667).
#
# The scanned trees come from scripts/production-source-dirs.sh, shared with the
# #136 and #379 gates. Test source sets are out of scope by construction -- the
# deliberate `catch (IllegalStateException)` at `MuxStreamTest.kt:141` is a test.
#
# THE TWO LEDGERS
# ---------------
# A violation is silenced only by an entry in one of two files, and they are two
# files rather than one column because they mean different things:
#
#   scripts/cancellation-catch-allowlist.tsv
#       Sites that are genuinely exempt, each with the reason. A `try`
#       containing no suspension point cannot take delivery of cancellation at
#       all, so a broad catch around straight-line non-suspend work is safe --
#       but that is a claim about the code that a reader has to make and write
#       down, not something this gate can see.
#
#   scripts/cancellation-catch-backlog.tsv
#       Sites that are NOT exempt: pre-existing defects this gate found when it
#       landed and that nobody has fixed yet. Every entry names the issue
#       tracking it. This gate PRINTS the outstanding count on every run, so the
#       tree's actual state is visible in CI rather than hidden behind a tick.
#
# Both are exact-match ratchets: an entry whose count no longer matches the tree
# -- in EITHER direction -- fails the gate. Fixing a site therefore forces an
# edit here, which is what stops either file becoming the place things go to be
# forgotten, and adding a site to either file is a deliberate act with a reason
# attached rather than a number quietly going up.
#
# Usage: bash scripts/check-cancellation-catch-order.sh

set -euo pipefail

# ADDING A NEW GATE? It MUST ship a matching scripts/tests/<gate>-test.sh, wired
# into CI. See the header of scripts/lib/gate-filters.sh: a green gate is not
# evidence a gate works.
#
# Resolved from this script's own location rather than from the working
# directory, so the gate can be invoked by path from anywhere -- including from
# the throwaway repo its self-test builds.
gate_lib_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
scanner=$gate_lib_dir/lib/kotlin-catch-chains.awk

if [ ! -f "$scanner" ]; then
  echo "FAIL: scanner not found at $scanner" >&2
  echo "      Without it this gate would read nothing and report clean." >&2
  exit 1
fi

# Assigned, then cd'd, rather than `cd "$(git rev-parse ...)"`, so a failure is
# attributed to git -- which has written its own diagnostic -- instead of
# surfacing as a `cd: null directory` on the wrong line. Same reasoning as
# scripts/production-source-dirs.sh, which documents the measurement.
repo_root=$(git rev-parse --show-toplevel)
cd "$repo_root"

ALLOWLIST=scripts/cancellation-catch-allowlist.tsv
BACKLOG=scripts/cancellation-catch-backlog.tsv

# Via a variable, not `mapfile < <(...)`: process substitution does not
# propagate its exit status, so a failing preflight would leave DIRS empty and
# this gate would exit 0 having scanned nothing (#547).
dirs=$(bash scripts/production-source-dirs.sh)
mapfile -t DIRS <<<"$dirs"
[ "${#DIRS[@]}" -gt 0 ] || { echo "ERROR: no production source dirs to scan" >&2; exit 1; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# `-print` into a file and sort in place, rather than `find | sort`: the
# pipeline's status would be sort's, and a `find` that could not read a tree
# must be a red build, not a quiet pass over fewer files.
find "${DIRS[@]}" -type f -name '*.kt' -print > "$work/files"
sort -o "$work/files" "$work/files"

file_count=$(wc -l < "$work/files")
if [ "$file_count" -eq 0 ]; then
  echo "FAIL: no .kt files under the production source dirs" >&2
  echo "      A gate that reads nothing and one that finds nothing print the" >&2
  echo "      same empty result. This is the former, so it is red." >&2
  exit 1
fi

: > "$work/violations"
total_catches=0
while IFS= read -r f; do
  # No `2>/dev/null` and no `|| true`: a file the scanner cannot read must be a
  # red build. awk exits nonzero only on a real error here, and `set -e` takes
  # it from there.
  awk -f "$scanner" "$f" > "$work/one"
  while IFS=$'\t' read -r kind a b c d; do
    case $kind in
      TOTAL) total_catches=$((total_catches + a)) ;;
      CATCH)
        # $a line, $b type, $c guarded, $d enclosing function.
        # The four types that can receive a CancellationException, in both the
        # bare and the qualified spelling. Written on one line: a backslash
        # continuation inside a case pattern list makes the following line's
        # INDENTATION part of the next pattern, which silently stops it
        # matching anything.
        case $b in
          Exception|Throwable|RuntimeException|IllegalStateException|*.Exception|*.Throwable|*.RuntimeException|*.IllegalStateException)
            if [ "$c" = "0" ]; then
              printf '%s\t%s\t%s\t%s\n' "$f" "$d" "$a" "$b" >> "$work/violations"
            fi
            ;;
        esac
        ;;
    esac
  done < "$work/one"
done < "$work/files"

# Anti-vacuity control. An empty violation list is the good case AND the shape a
# broken scanner produces; the two are distinguishable only by whether anything
# was parsed at all. This tree has ~200 catch clauses in production.
if [ "$total_catches" -eq 0 ]; then
  echo "FAIL: scanned $file_count Kotlin file(s) and parsed ZERO catch clauses." >&2
  echo "      That is a broken scanner, not a clean tree. Red rather than green." >&2
  exit 1
fi

# read_ledger <file> <kind> -> appends "path<TAB>function<TAB>count" lines to
# $work/expected, and validates the reasons.
#
# Format, tab-separated: path, enclosing function, count, reason.
# `#` comments and blank lines are ignored.
ledger_bad=0
read_ledger() {
  local path=$1 kind=$2 lineno=0 p fn cnt reason rest
  [ -f "$path" ] || return 0
  while IFS= read -r raw || [ -n "$raw" ]; do
    lineno=$((lineno + 1))
    case $raw in
      ''|'#'*) continue ;;
    esac
    IFS=$'\t' read -r p fn cnt reason rest <<<"$raw"
    if [ -z "$p" ] || [ -z "$fn" ] || [ -z "$cnt" ]; then
      echo "FAIL: $path:$lineno: expected 4 tab-separated fields (path, function, count, reason)" >&2
      ledger_bad=1
      continue
    fi
    if [ -n "$rest" ]; then
      echo "FAIL: $path:$lineno: more than 4 tab-separated fields" >&2
      ledger_bad=1
      continue
    fi
    if ! [[ "$cnt" =~ ^[1-9][0-9]*$ ]]; then
      echo "FAIL: $path:$lineno: count '$cnt' is not a positive integer" >&2
      ledger_bad=1
      continue
    fi
    # A reason is the entire point of a ledger entry: without one the file is a
    # list of numbers nobody can re-audit. 20 characters is not a quality bar,
    # it is a floor that "n/a" and "ok" do not clear.
    if [ "${#reason}" -lt 20 ]; then
      echo "FAIL: $path:$lineno: reason is missing or too short (<20 chars): '$reason'" >&2
      ledger_bad=1
      continue
    fi
    # A backlog entry is an unfixed defect, so it must name the issue that will
    # fix it. Without that requirement the backlog is an allowlist with a
    # different filename.
    if [ "$kind" = backlog ] && ! [[ "$reason" =~ \#[0-9]+ ]]; then
      echo "FAIL: $path:$lineno: a backlog reason must cite a tracking issue (#NNN)" >&2
      ledger_bad=1
      continue
    fi
    printf '%s\t%s\t%s\n' "$p" "$fn" "$cnt" >> "$work/expected"
    printf '%s\t%s\t%s\t%s\n' "$kind" "$p" "$fn" "$cnt" >> "$work/ledger"
  done < "$path"
}

: > "$work/expected"
: > "$work/ledger"
read_ledger "$ALLOWLIST" allowlist
read_ledger "$BACKLOG" backlog
[ "$ledger_bad" -eq 0 ] || exit 1

# Duplicate keys within the ledgers would make the ratchet ambiguous: two
# entries of 1 and one entry of 2 silence the same tree but only one of them
# can be reconciled against it by inspection.
set +e
dupes=$(cut -f1,2 "$work/expected" | sort | uniq -d)
set -e
if [ -n "$dupes" ]; then
  echo "FAIL: duplicate <path, function> key(s) across the ledgers:" >&2
  printf '%s\n' "$dupes" | sed 's/^/    /' >&2
  echo "      Merge them into one entry with the combined count." >&2
  exit 1
fi

# Compare, per <path, function>, the number of violations in the tree against
# the number the ledgers account for. Both directions are failures: too many is
# a new swallow, too few is a stale entry whose removal is the ONLY thing that
# stops these files accumulating.
#
# Records are emitted flat and sorted, and the offending source lines are
# recovered in a second pass keyed off them. An earlier draft interleaved the
# lines with their header record and then sorted the lot, which reordered each
# block away from its own header.
status=0
#
# `FILENAME == ex` rather than the usual `NR == FNR`: with an EMPTY ledger --
# the state this gate ships in for a repo with no exemptions -- awk never runs a
# rule for the first file, so `NR == FNR` is still true on the first line of the
# SECOND file and every violation would be read as a ledger entry. The gate then
# reports the whole tree as stale ledger entries and nothing as a violation,
# which is red, but red for the wrong reason and with the wrong list.
awk -F'\t' -v ex="$work/expected" '
  FILENAME == ex { expected[$1 "\t" $2] += $3; next }
  { actual[$1 "\t" $2]++ }
  END {
    for (k in actual) {
      want = (k in expected) ? expected[k] : 0
      if (actual[k] > want) printf "NEW\t%s\t%d\t%d\n", k, actual[k], want
      else if (actual[k] < want) printf "FIXED\t%s\t%d\t%d\n", k, actual[k], want
    }
    for (k in expected) if (!(k in actual)) printf "GONE\t%s\t0\t%d\n", k, expected[k]
  }
' "$work/expected" "$work/violations" | sort > "$work/report"

# `grep -c` prints 0 and exits 1 when it counts nothing, and nothing is the good
# case here; under `set -euo pipefail` that status would kill the gate on a
# clean tree. The printed count is the answer, so the status is discarded --
# `|| true` rather than a `set +e` bracket because `grep` is a command, not a
# function, so SC2310 does not apply.
new_count=$(grep -c '^NEW' "$work/report" || true)
stale_count=$(grep -cE '^(FIXED|GONE)' "$work/report" || true)

if [ "$new_count" -gt 0 ]; then
  echo "Broad catch not preceded by a CancellationException clause (issue #674):"
  echo
  # `path:line: catch (Type)`, the shape every other gate here reports in, so a
  # red run names the offending file and line rather than only a count.
  awk -F'\t' '
    NR == FNR { if ($1 == "NEW") flagged[$2 "\t" $3] = 1; next }
    ($1 "\t" $2) in flagged { printf "  %s:%s: catch (%s)\n", $1, $3, $4 }
  ' "$work/report" "$work/violations" | sort -t: -k1,1 -k2,2n
  cat <<'EOF'

`CancellationException` extends `IllegalStateException`, so every one of
`Exception`, `Throwable`, `RuntimeException` and `IllegalStateException`
receives it. Put

    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {

ABOVE the broad clause -- source order is what decides delivery, and the
compiler diagnoses neither an unreachable nor a mis-ordered clause.

If the `try` genuinely contains no suspension point, cancellation cannot be
delivered there: add the site to scripts/cancellation-catch-allowlist.tsv with
the reason. See .claude/rules/coroutines.md and issues #660, #667, #674.
EOF
  status=1
fi

if [ "$stale_count" -gt 0 ]; then
  echo
  echo "Stale ledger entries (issue #674): the tree no longer matches."
  echo
  awk -F'\t' '
    $1 == "FIXED" { printf "  %s (%s): ledger says %d, tree has %d -- lower or remove the entry\n", $2, $3, $5, $4 }
    $1 == "GONE"  { printf "  %s (%s): ledger says %d, tree has none -- remove the entry\n", $2, $3, $5 }
  ' "$work/report"
  cat <<'EOF'

These files are exact-match ratchets on purpose. An entry that outlives the
violation it describes is how an allowlist turns into a place things go to be
forgotten, so removing it is part of the fix rather than optional tidying.
EOF
  status=1
fi

[ "$status" -eq 0 ] || exit 1

set +e
allow_total=$(awk -F'\t' '$1 == "allowlist" { n += $4 } END { print n + 0 }' "$work/ledger")
backlog_total=$(awk -F'\t' '$1 == "backlog" { n += $4 } END { print n + 0 }' "$work/ledger")
set -e

echo "OK: every broad catch in ${#DIRS[@]} production source set(s) is preceded by a"
echo "    cancellation clause, or is on a ledger."
echo "    $total_catches catch clause(s) parsed across $file_count file(s)."
echo "    $allow_total allowlisted (reasoned exemptions, $ALLOWLIST)."
if [ "$backlog_total" -gt 0 ]; then
  echo
  echo "    !! $backlog_total KNOWN cancellation swallow(s) remain unfixed."
  echo "       These are defects, not exemptions. See $BACKLOG."
fi
