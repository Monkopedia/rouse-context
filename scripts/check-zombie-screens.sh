#!/usr/bin/env bash
# Fails if any *Screen.kt file declares public @Composable functions that no
# production code (i.e. AppNavigation.kt or any non-self file under app/src/main)
# references. Prevents the class of bug where an edit lands in a dead file.
#
# Flags are based on function NAMES appearing in production code — cheap,
# coarse, and sufficient. Previews (`@Preview` annotated private funcs) are
# ignored because they aren't rendered at runtime.

set -euo pipefail

SCREENS_DIR="app/src/main/java/com/rousecontext/app/ui/screens"
PROD_ROOT="app/src/main"
# Dead `EXIT` accumulator removed (#592): the script has always exited on the
# ZOMBIES array below, so it was assigned and never read -- shellcheck SC2034.
ZOMBIES=()

# Tracked-but-known zombies. Remove entries here once the corresponding issue
# is resolved (delete the file or wire it into navigation).
KNOWN_EXEMPT=()

is_exempt() {
  local base="$1"
  for e in "${KNOWN_EXEMPT[@]}"; do
    [[ "$base" == "$e" ]] && return 0
  done
  return 1
}

for file in "$SCREENS_DIR"/*.kt; do
  [[ -f "$file" ]] || continue
  base="$(basename "$file")"
  # SC2310 (`set -e` is off inside a function invoked in a condition) is
  # suppressed rather than restructured, because there is nothing to
  # restructure TO: `is_exempt` is a predicate, being asked in a condition is
  # its entire purpose, and `if is_exempt ...; then` reports the same note. The
  # suppression is safe on its own terms and not just convenient -- the body is
  # a `[[ ]]` over a local array plus a `return`, with no external command in
  # it, so there is no failure for `set -e` to have caught.
  # shellcheck disable=SC2310
  is_exempt "$base" && continue
  # Skip files that hold no composables (enums, sealed classes, SetupMode, etc.)
  grep -q '^fun [A-Z]' "$file" || continue

  # Public top-level @Composable functions: anything starting with `fun CapName`
  # at column 0, excluding private (prefixed with `private `) — those appear
  # on the same line but detect via context line.
  # Captured with its status CHECKED, not read through `< <(...)`. A process
  # substitution reports no status at all, so an `awk` that failed to read this
  # file produced an EMPTY list -- indistinguishable from "this file declares no
  # composables" -- and the `continue` below then skipped it in silence. That is
  # not hypothetical: measured against this gate with a deliberately broken
  # `awk`, the old form printed "OK: no zombie screens" and exited 0 over a tree
  # containing a zombie. A gate that skips what it could not read is the
  # #547/#579 shape; this one fails, by name, instead.
  if ! func_names=$(
    awk '
      /@Preview/ { next }
      /^private fun [A-Z]/ { next }
      /^fun [A-Z]/ {
        sub(/^fun /, "", $0); sub(/[^A-Za-z0-9_].*$/, "", $0); print
      }
    ' "$file" | sort -u
  ); then
    echo "ERROR: could not extract composable names from $file." >&2
    echo "       The file was not scanned, so this is a failure and not a pass." >&2
    exit 1
  fi

  # The emptiness test has to happen BEFORE the split, not after it, and that is
  # not ceremony: `mapfile -t x <<<""` yields a ONE-element array holding the
  # empty string where the process substitution yielded zero elements -- and an
  # empty "$func" below turns the reference grep into `\b\b`, which matches
  # every file, marks every screen as referenced, and makes this gate pass
  # everything. Measured: `mapfile -t a <<<""` gives ${#a[@]} == 1.
  [[ -n "$func_names" ]] || continue
  mapfile -t funcs <<<"$func_names"
  [[ "${#funcs[@]}" -gt 0 ]] || continue

  # At least ONE function must be referenced in a non-self production file.
  found=no
  for func in "${funcs[@]}"; do
    # grep in production code, exclude the file itself
    if grep -rlE "\\b${func}\\b" --include='*.kt' "$PROD_ROOT" | grep -qv "^${file}$"; then
      found=yes
      break
    fi
  done

  if [[ "$found" == "no" ]]; then
    ZOMBIES+=("$file  (functions: ${funcs[*]})")
  fi
done

if (( ${#ZOMBIES[@]} > 0 )); then
  echo "ERROR: zombie screen files detected (not referenced by any production code):"
  printf '  %s\n' "${ZOMBIES[@]}"
  echo
  echo "Each file above declares @Composable functions that no other production"
  echo "Kotlin source references. Either wire the file into AppNavigation.kt,"
  echo "delete it, or add an explicit exemption below if intentional."
  exit 1
fi

echo "OK: no zombie screens under $SCREENS_DIR"
exit 0
