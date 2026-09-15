#!/usr/bin/env bash
# TEMPORARY -- removed in the very next commit.
#
# A gate with no `scripts/tests/check-fixture-no-harness-test.sh`, added solely
# to watch `scripts/check-gate-selftests.sh` go RED in CI on this PR. Without a
# demonstrated red case on the real tree, the coverage gate would be one more
# unverified gate -- which is the defect #705 is about.
set -euo pipefail
echo "OK: fixture gate (this script exists only to be missing a self-test)"
