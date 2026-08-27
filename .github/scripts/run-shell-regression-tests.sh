#!/usr/bin/env bash
# Discovers and runs every pure shell regression suite under
# .github/scripts/tests/*.test.sh, in deterministic lexical order, so a
# future suite added under that directory is automatically exercised by CI
# without anyone having to remember to wire it in by hand.
#
# audit-cli-jar.test.sh is the one documented exception and is
# deliberately NOT run here: it is already executed inside the
# "Java 21 / Linux" CI job immediately after build-distribution.sh
# produces the real shaded CLI JAR that its case 8 audits directly.
# Running it here, before any build has happened, would only repeat its
# synthetic-fixture cases while silently skipping the real-JAR case that
# is the actual point of running it in CI -- so it stays where its build
# prerequisite is satisfied instead of being duplicated in this
# lightweight job.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
tests_dir="$script_dir/tests"

# Suites with a documented special precondition satisfied elsewhere in
# CI, and therefore deliberately excluded from this discovery loop.
declare -A excluded_tests=(
  [audit-cli-jar.test.sh]=1
)

shopt -s nullglob
raw_test_files=("$tests_dir"/*.test.sh)
shopt -u nullglob

# printf still runs its format once against empty/missing arguments when
# given zero actual arguments, so calling it unconditionally on an empty
# raw_test_files would feed mapfile one spurious blank line -- guard on
# the array actually having elements instead.
test_files=()
if [[ "${#raw_test_files[@]}" -gt 0 ]]; then
  mapfile -t test_files < <(printf '%s\n' "${raw_test_files[@]}" | sort)
fi

eligible_tests=()
for test_file in "${test_files[@]}"; do
  test_name="$(basename "$test_file")"
  if [[ -n "${excluded_tests[$test_name]:-}" ]]; then
    continue
  fi
  eligible_tests+=("$test_file")
done

if [[ "${#eligible_tests[@]}" -eq 0 ]]; then
  echo "No eligible shell regression suite found under $tests_dir." >&2
  echo "A lightweight CI job that silently runs nothing proves nothing; treating this as a failure." >&2
  exit 1
fi

for test_file in "${eligible_tests[@]}"; do
  echo "Running $(basename "$test_file")"
  bash "$test_file"
done

echo
echo "All ${#eligible_tests[@]} eligible shell regression suite(s) passed."
