#!/usr/bin/env bash
# Regression tests for scripts/check-shell-lint.sh (#592).
#
# The gate is a linter wrapper, which is precisely the shape that rots into a
# no-op: point it at a path that no longer exists, hand `shellcheck` an empty
# argument list, and it prints nothing and exits 0 -- indistinguishable from a
# clean tree. #629's Roborazzi coverage control passed at `0 == 0` for exactly
# that reason, so this asserts the gate FIRES and asserts it cannot pass
# vacuously.
#
# Same harness shape as #577/#590/#594/#628: a throwaway git work tree, the REAL
# gate run end-to-end against it.

set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
gate="$repo_root/scripts/check-shell-lint.sh"

sandbox=$(mktemp -d)
trap 'rm -rf "$sandbox"' EXIT

failures=0

# Reset the sandbox to an empty, tracked-nothing git work tree.
reset_sandbox() {
  rm -rf "${sandbox:?}/repo"
  mkdir -p "$sandbox/repo"
  git -C "$sandbox/repo" init -q
}

# write <relative-path> <<'EOF' ... : create a script and stage it, so
# `git ls-files` sees it.
write_script() {
  local rel=$1
  mkdir -p "$(dirname "$sandbox/repo/$rel")"
  cat > "$sandbox/repo/$rel"
  chmod +x "$sandbox/repo/$rel"
  git -C "$sandbox/repo" add -- "$rel"
}

# expect <exit-status> <name> [<substring the output must contain>]...
#
# The substrings are the point. A gate that exits non-zero without naming what
# is wrong is the #628 failure mode (exit 1, no output), so a red case here has
# to SAY the file and the rule code, not merely go red.
expect() {
  local want=$1 name=$2
  shift 2
  local out status must
  set +e
  out=$(bash "$gate" "$sandbox/repo" 2>&1)
  status=$?
  set -e
  if [[ "$status" -ne "$want" ]]; then
    echo "FAIL: $name -- expected exit $want, got $status"
    printf '%s\n' "$out" | sed 's/^/    /'
    failures=$((failures + 1))
    return
  fi
  for must in "$@"; do
    if ! printf '%s\n' "$out" | grep -qF -- "$must"; then
      echo "FAIL: $name -- exited $status but never said '$must'"
      printf '%s\n' "$out" | sed 's/^/    /'
      failures=$((failures + 1))
      return
    fi
  done
  echo "ok: $name (exit $status)"
}

# --- green -------------------------------------------------------------------
reset_sandbox
write_script scripts/clean.sh <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
target=${1:-.}
echo "checking ${target}"
EOF
expect 0 "a clean tracked script passes" "OK: shellcheck clean" "scripts/clean.sh"

# --- the gate must FIRE ------------------------------------------------------
# SC2155 is the masked-return-value check that IS on by default: `local x=$(cmd)`
# discards cmd's exit status because `local` itself succeeds. This is the
# closest default-severity relative of the load-bearing-exit-status shape these
# gates are built out of.
reset_sandbox
write_script scripts/bad.sh <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
index_of() {
  local hit=$(grep -nF -- "$1" /dev/null | head -1)
  echo "$hit"
}
index_of x
EOF
expect 1 "a masked return value is caught, named by file and rule" \
  "scripts/bad.sh" "SC2155"

# SC2086: an unquoted expansion word-splits. Every gate in scripts/ passes
# directory lists and grep patterns through variables, so this is the live risk.
reset_sandbox
write_script scripts/bad.sh <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
dirs=$(cat /dev/null)
for d in $dirs; do
  grep -r foo $d
done
EOF
expect 1 "an unquoted expansion is caught" "scripts/bad.sh" "SC2086"

# --- coverage is recursive, not just scripts/ --------------------------------
# The self-tests under scripts/tests/ are the things that caught #577, #590 and
# both #628 bugs, and they are shell too. A `scripts/*.sh` glob would miss them
# entirely, so a violation one directory down must still go red.
reset_sandbox
write_script scripts/clean.sh <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
echo ok
EOF
write_script scripts/tests/nested-test.sh <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [ $? -ne 0 ]; then echo bad; fi
EOF
expect 1 "a violation under scripts/tests/ is covered too" \
  "scripts/tests/nested-test.sh" "SC2181"

# Any depth, not a hardcoded two levels: deploy/ and relay/scripts/ hold shell
# too, and a future directory must not need this gate edited to be covered.
reset_sandbox
write_script deploy/deep/nested/thing.sh <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [ $? -ne 0 ]; then echo bad; fi
EOF
expect 1 "a violation at arbitrary depth is covered" \
  "deploy/deep/nested/thing.sh" "SC2181"

# --- the gate must not pass vacuously ----------------------------------------
# THE case this test exists for. `shellcheck` with no file arguments reads stdin
# and exits 0, so a discovery that matches nothing would report success over an
# empty set -- a gate that passes everything, forever, silently.
reset_sandbox
expect 1 "an empty work tree goes RED, not vacuously green" \
  "no tracked *.sh files"

# Untracked-only is the same hazard wearing a different hat: the files exist on
# disk, so a human glancing at the directory would say the gate had work to do.
reset_sandbox
mkdir -p "$sandbox/repo/scripts"
cat > "$sandbox/repo/scripts/untracked.sh" <<'EOF'
#!/usr/bin/env bash
if [ $? -ne 0 ]; then echo bad; fi
EOF
expect 1 "an untracked-only tree goes RED rather than reporting clean" \
  "no tracked *.sh files"

# Outside a git work tree, `git ls-files` yields nothing; without the explicit
# guard that would look identical to a clean tree.
rm -rf "${sandbox:?}/repo"
mkdir -p "$sandbox/repo"
expect 1 "a non-git directory goes RED" "is not a git work tree"

# A path that does not exist at all -- the "someone moved scripts/" case.
rm -rf "${sandbox:?}/repo"
expect 1 "a missing root goes RED" "no such directory"
mkdir -p "$sandbox/repo"

# --- the version pin is enforced ---------------------------------------------
# The pin is the only thing standing between this gate and whatever shellcheck
# the runner image happens to ship. If it were advisory, a release that DROPPED
# a check would weaken the gate with no signal at all. Proven by putting a fake
# `shellcheck` reporting a different version first on PATH.
reset_sandbox
write_script scripts/clean.sh <<'EOF'
#!/usr/bin/env bash
echo ok
EOF
mkdir -p "$sandbox/fakebin"
cat > "$sandbox/fakebin/shellcheck" <<'EOF'
#!/usr/bin/env bash
echo "ShellCheck - shell script analysis tool"
echo "version: 0.0.1-fake"
exit 0
EOF
chmod +x "$sandbox/fakebin/shellcheck"
saved_path=$PATH
PATH="$sandbox/fakebin:$PATH"
expect 1 "a mismatched shellcheck version goes RED" "expected"
PATH=$saved_path

# ...and with no shellcheck on PATH at all, it must fail rather than skip. The
# minimal PATH below carries exactly what the gate and this harness need to run
# (bash, git, sed, grep) and nothing else, so `command -v shellcheck` genuinely
# finds nothing.
mkdir -p "$sandbox/minbin"
for tool in bash git sed grep; do
  ln -sf "$(command -v "$tool")" "$sandbox/minbin/$tool"
done
saved_path=$PATH
# The only suppression in the tree, and it is the point of the case: SC2123
# warns when PATH is REPLACED by a single directory rather than extended,
# because that is normally a typo. Here it is exactly what is being tested --
# a PATH deliberately narrowed until `command -v shellcheck` finds nothing.
# Restored on the next line but one.
# shellcheck disable=SC2123
PATH="$sandbox/minbin"
export PATH
expect 1 "a missing shellcheck goes RED rather than being skipped" \
  "shellcheck is not installed"
PATH=$saved_path
export PATH

if [[ "$failures" -ne 0 ]]; then
  echo
  echo "$failures case(s) failed: scripts/check-shell-lint.sh is not behaving as"
  echo "its own tests describe. Fix the gate, not these tests."
  exit 1
fi

echo
echo "All check-shell-lint.sh cases behaved as expected."
