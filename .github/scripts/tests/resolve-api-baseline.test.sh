#!/usr/bin/env bash
# Regression tests for .github/scripts/resolve-api-baseline.sh.
#
# Every case exercises the pure decision functions (determine_effective_
# baseline, is_stable_version) or the local-file-only read_declared_
# baseline directly, with already-resolved plain-string inputs. No git
# remote, no network call, and no real Maven invocation is ever made --
# main()'s own git fetch/worktree/mvnw orchestration is intentionally not
# exercised here, exactly like this suite's siblings never touch a real
# GitHub API or Maven repository.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
resolve_api_baseline_script="$script_dir/../resolve-api-baseline.sh"

failures=0
cases_run=0

# shellcheck source=/dev/null
source "$resolve_api_baseline_script"

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

assert_exit_code() {
  local name="$1" expected="$2" actual="$3"
  cases_run=$((cases_run + 1))
  if [[ "$actual" -eq "$expected" ]]; then
    echo "PASS: $name"
  else
    echo "FAIL: $name -- expected exit $expected, got $actual" >&2
    failures=$((failures + 1))
  fi
}

assert_equals() {
  local name="$1" expected="$2" actual="$3"
  cases_run=$((cases_run + 1))
  if [[ "$actual" == "$expected" ]]; then
    echo "PASS: $name"
  else
    echo "FAIL: $name -- expected '$expected', got '$actual'" >&2
    failures=$((failures + 1))
  fi
}

# run_determine <event> <base_ref> <candidate> <declared> <declared_tag_exists> <main_version> <main_tag_exists>
# Invokes determine_effective_baseline and prints "<exit_code>|<stdout>".
run_determine() {
  local out exit_code
  set +e
  out="$(determine_effective_baseline "$@" 2>/dev/null)"
  exit_code=$?
  set -e
  echo "${exit_code}|${out}"
}

# --- API-REL-001: normal PR, baseline tag exists ---------------------------

result="$(run_determine "pull_request" "develop" "1.3.0-SNAPSHOT" "1.2.0" "true" "" "false")"
assert_exit_code "API-REL-001: exits 0 when the declared baseline tag already exists" 0 "${result%%|*}"
assert_equals "API-REL-001: effective baseline is the declared one (1.2.0 v1.2.0)" "1.2.0 v1.2.0" "${result#*|}"

# --- API-REL-002: release PR to main, tag missing, main resolves -----------

result="$(run_determine "pull_request" "main" "1.3.0" "1.3.0" "false" "1.2.0" "true")"
assert_exit_code "API-REL-002: exits 0 for a release PR to main whose future tag is missing" 0 "${result%%|*}"
assert_equals "API-REL-002: effective baseline is main's current stable version (1.2.0 v1.2.0)" "1.2.0 v1.2.0" "${result#*|}"

# --- API-REL-003: non-main PR, tag missing -> FAIL --------------------------

result="$(run_determine "pull_request" "develop" "1.3.0" "1.3.0" "false" "" "false")"
assert_exit_code "API-REL-003: a non-main PR with a missing baseline tag fails closed" 1 "${result%%|*}"

# --- API-REL-004: release PR but candidate is still -SNAPSHOT -> FAIL ------

result="$(run_determine "pull_request" "main" "1.3.0-SNAPSHOT" "1.3.0" "false" "1.2.0" "true")"
assert_exit_code "API-REL-004: a -SNAPSHOT candidate in a release PR to main fails closed" 1 "${result%%|*}"

# --- API-REL-005: release PR but declared baseline != candidate -> FAIL ----

result="$(run_determine "pull_request" "main" "1.3.0" "1.2.5" "false" "1.2.0" "true")"
assert_exit_code "API-REL-005: declared baseline not equal to the candidate version fails closed" 1 "${result%%|*}"

# --- API-REL-006: previous stable tag on main is absent -> FAIL ------------

result="$(run_determine "pull_request" "main" "1.3.0" "1.3.0" "false" "1.2.0" "false")"
assert_exit_code "API-REL-006: main's own stable version has no matching tag -- fails closed" 1 "${result%%|*}"

# --- API-REL-007: future tag already exists -> normal baseline path --------

result="$(run_determine "pull_request" "main" "1.3.0" "1.3.0" "true" "" "false")"
assert_exit_code "API-REL-007: exits 0 when the future tag already exists, even on a release PR" 0 "${result%%|*}"
assert_equals "API-REL-007: effective baseline is the now-existing declared one (1.3.0 v1.3.0)" "1.3.0 v1.3.0" "${result#*|}"

# --- Additional coverage beyond the required matrix ------------------------

# An ordinary push (develop/main CI) with a missing tag must still fail
# closed exactly as it always did -- the exception never applies outside
# a pull_request event at all.
result="$(run_determine "push" "" "1.4.0-SNAPSHOT" "1.2.0" "false" "" "false")"
assert_exit_code "ordinary push with a missing baseline tag fails closed" 1 "${result%%|*}"

# A workflow_dispatch run with a missing tag must also fail closed.
result="$(run_determine "workflow_dispatch" "" "1.4.0-SNAPSHOT" "1.2.0" "false" "" "false")"
assert_exit_code "workflow_dispatch with a missing baseline tag fails closed" 1 "${result%%|*}"

# main's own resolved version is not a valid stable release version.
result="$(run_determine "pull_request" "main" "1.3.0" "1.3.0" "false" "1.2.0-SNAPSHOT" "true")"
assert_exit_code "main resolving to a non-stable version fails closed" 1 "${result%%|*}"

# main's resolved version equals the candidate -- refuse to compare a
# release candidate against itself.
result="$(run_determine "pull_request" "main" "1.3.0" "1.3.0" "false" "1.3.0" "true")"
assert_exit_code "main's version equal to the candidate version fails closed" 1 "${result%%|*}"

# --- is_stable_version: pure SemVer-stable predicate -----------------------

for stable_version in "1.0.0" "1.2.0" "10.20.30" "1.3.0"; do
  cases_run=$((cases_run + 1))
  if is_stable_version "$stable_version"; then
    echo "PASS: is_stable_version accepts stable version '$stable_version'"
  else
    echo "FAIL: is_stable_version rejected stable version '$stable_version'" >&2
    failures=$((failures + 1))
  fi
done

for unstable_version in "1.3.0-SNAPSHOT" "" "1.3" "v1.3.0" "1.3.0.0" "latest"; do
  cases_run=$((cases_run + 1))
  if ! is_stable_version "$unstable_version"; then
    echo "PASS: is_stable_version rejects '$unstable_version'"
  else
    echo "FAIL: is_stable_version accepted invalid version '$unstable_version'" >&2
    failures=$((failures + 1))
  fi
done

# --- read_declared_baseline: local-file-only validation --------------------

run_read_declared_baseline() {
  local file="$1"
  local out exit_code
  set +e
  out="$(read_declared_baseline "$file" 2>/dev/null)"
  exit_code=$?
  set -e
  echo "${exit_code}|${out}"
}

valid_file="$work_dir/valid-baseline"
printf '1.3.0\n' > "$valid_file"
result="$(run_read_declared_baseline "$valid_file")"
assert_exit_code "read_declared_baseline: a valid stable version exits 0" 0 "${result%%|*}"
assert_equals "read_declared_baseline: whitespace is trimmed" "1.3.0" "${result#*|}"

missing_file="$work_dir/does-not-exist"
result="$(run_read_declared_baseline "$missing_file")"
assert_exit_code "read_declared_baseline: a missing file fails closed" 1 "${result%%|*}"

empty_file="$work_dir/empty-baseline"
printf '' > "$empty_file"
result="$(run_read_declared_baseline "$empty_file")"
assert_exit_code "read_declared_baseline: an empty file fails closed" 1 "${result%%|*}"

invalid_file="$work_dir/invalid-baseline"
printf '1.3.0-SNAPSHOT\n' > "$invalid_file"
result="$(run_read_declared_baseline "$invalid_file")"
assert_exit_code "read_declared_baseline: a -SNAPSHOT baseline file fails closed" 1 "${result%%|*}"

echo
echo "$cases_run assertion case(s) run, $failures failure(s)."
if [[ "$failures" -ne 0 ]]; then
  exit 1
fi
