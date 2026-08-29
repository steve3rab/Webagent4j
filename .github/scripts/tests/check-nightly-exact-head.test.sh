#!/usr/bin/env bash
# Regression tests for .github/scripts/check-nightly-exact-head.sh.
#
# Every case runs the script against a synthetic workflow-file fixture
# under a temporary directory. No real GitHub Actions workflow is
# triggered; the script only reads plain text.
#
# Fixture strings below deliberately hold literal GitHub Actions
# `${{ ... }}` expression syntax that must NOT be shell-expanded; they are
# single-quoted on purpose.
# shellcheck disable=SC2016
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
check_script="$script_dir/../check-nightly-exact-head.sh"

failures=0
cases_run=0

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

# assert_case <name> <expected: pass|fail> <expected_message_substring> <fixture_content>
assert_case() {
  local name="$1" expected="$2" expected_message="$3" fixture_content="$4"
  cases_run=$((cases_run + 1))

  local fixture="$work_dir/fixture-$cases_run.yml"
  printf '%s' "$fixture_content" > "$fixture"

  local output result
  set +e
  if output="$(bash "$check_script" "$fixture" 2>&1)"; then result="pass"; else result="fail"; fi
  set -e

  local ok=1
  if [[ "$result" != "$expected" ]]; then
    echo "FAIL: $name -- expected $expected, got $result" >&2
    ok=0
  fi
  if [[ -n "$expected_message" ]] && [[ "$output" != *"$expected_message"* ]]; then
    echo "FAIL: $name -- expected message containing: $expected_message" >&2
    ok=0
  fi

  if [[ "$ok" -eq 1 ]]; then
    echo "PASS: $name"
  else
    echo "  output:" >&2
    printf '%s\n' "$output" | sed 's/^/    /' >&2
    failures=$((failures + 1))
  fi
}

all_sha_fixture='jobs:
  a:
    steps:
      - uses: actions/checkout@deadbeef
        with:
          ref: ${{ github.event_name == '"'"'schedule'"'"' && '"'"'develop'"'"' || github.sha }}
  b:
    steps:
      - uses: actions/checkout@deadbeef
        with:
          ref: ${{ github.event_name == '"'"'schedule'"'"' && '"'"'develop'"'"' || github.sha }}
'

assert_case "every schedule/dispatch checkout using github.sha passes" pass "" \
  "$all_sha_fixture"

mixed_ref_fixture='jobs:
  a:
    steps:
      - uses: actions/checkout@deadbeef
        with:
          ref: ${{ github.event_name == '"'"'schedule'"'"' && '"'"'develop'"'"' || github.sha }}
  b:
    steps:
      - uses: actions/checkout@deadbeef
        with:
          ref: ${{ github.event_name == '"'"'schedule'"'"' && '"'"'develop'"'"' || github.ref }}
'

assert_case "a single checkout still using github.ref is refused" fail "mutable ref expression" \
  "$mixed_ref_fixture"

head_ref_fixture='jobs:
  a:
    steps:
      - uses: actions/checkout@deadbeef
        with:
          ref: ${{ github.event_name == '"'"'schedule'"'"' && '"'"'develop'"'"' || github.head_ref }}
'

assert_case "github.head_ref is refused" fail "github.head_ref" \
  "$head_ref_fixture"

ref_name_fixture='jobs:
  a:
    steps:
      - uses: actions/checkout@deadbeef
        with:
          ref: ${{ github.event_name == '"'"'schedule'"'"' && '"'"'develop'"'"' || github.ref_name }}
'

assert_case "github.ref_name is refused" fail "github.ref_name" \
  "$ref_name_fixture"

no_expression_fixture='jobs:
  a:
    steps:
      - uses: actions/checkout@deadbeef
'

assert_case "a workflow with no schedule/dispatch checkout expression at all is refused" fail "No schedule/dispatch checkout ref expression" \
  "$no_expression_fixture"

assert_case "an empty workflow file with no relevant content is refused" fail "No schedule/dispatch checkout ref expression" \
  ""

cases_run=$((cases_run + 1))
set +e
missing_output="$(bash "$check_script" "$work_dir/does-not-exist.yml" 2>&1)"
missing_exit=$?
set -e
if [[ "$missing_exit" -ne 0 ]] && [[ "$missing_output" == *"Workflow file not found"* ]]; then
  echo "PASS: a nonexistent workflow file path is refused"
else
  echo "FAIL: a nonexistent workflow file path is refused" >&2
  printf '%s\n' "$missing_output" | sed 's/^/    /' >&2
  failures=$((failures + 1))
fi

echo
echo "$cases_run assertion case(s) run, $failures failure(s)."
if [[ "$failures" -ne 0 ]]; then
  exit 1
fi
