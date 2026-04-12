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
EXIT=0
ZOMBIES=()

# Tracked-but-known zombies. Remove entries here once the corresponding issue
# is resolved (delete the file or wire it into navigation).
KNOWN_EXEMPT=(
  "OnboardingErrorScreen.kt"  # issue #85 — wire or delete
)

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
  is_exempt "$base" && continue
  # Skip files that hold no composables (enums, sealed classes, SetupMode, etc.)
  grep -q '^fun [A-Z]' "$file" || continue

  # Public top-level @Composable functions: anything starting with `fun CapName`
  # at column 0, excluding private (prefixed with `private `) — those appear
  # on the same line but detect via context line.
  mapfile -t funcs < <(
    awk '
      /@Preview/ { next }
      /^private fun [A-Z]/ { next }
      /^fun [A-Z]/ {
        sub(/^fun /, "", $0); sub(/[^A-Za-z0-9_].*$/, "", $0); print
      }
    ' "$file" | sort -u
  )

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
    EXIT=1
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
