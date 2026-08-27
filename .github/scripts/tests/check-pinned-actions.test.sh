#!/usr/bin/env bash
# Regression tests for .github/scripts/check-pinned-actions.sh.
#
# All cases run against synthetic fixture workflow files under a
# temporary directory; no real workflow file is required to exist and
# none of the repository's actual workflows are modified.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
pin_script="$script_dir/../check-pinned-actions.sh"

failures=0
cases_run=0

# shellcheck source=/dev/null
source "$pin_script"

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

# assert_file <name> <expected: pass|fail> <expected_message_substring> <fixture_content>
assert_file() {
  local name="$1" expected="$2" expected_message="$3" content="$4"
  cases_run=$((cases_run + 1))

  local fixture="$work_dir/fixture-$cases_run.yml"
  printf '%s' "$content" > "$fixture"

  local output result
  set +e
  if output="$(check_workflow_file "$fixture" 2>&1)"; then result="pass"; else result="fail"; fi
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

assert_file "a bare 40-hex-char SHA is accepted" pass "" \
  '      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7'

assert_file "a version tag is refused" fail "not pinned to a full 40-character commit SHA" \
  '      - uses: actions/checkout@v7'

assert_file "the main branch is refused" fail "not pinned to a full 40-character commit SHA" \
  '      - uses: some/action@main'

assert_file "the master branch is refused" fail "not pinned to a full 40-character commit SHA" \
  '      - uses: some/action@master'

assert_file "an arbitrary named branch is refused" fail "not pinned to a full 40-character commit SHA" \
  '      - uses: some/action@release-branch'

assert_file "a short (abbreviated) SHA is refused" fail "not pinned to a full 40-character commit SHA" \
  '      - uses: actions/checkout@3d3c42e'

assert_file "a local action path is exempt" pass "" \
  '      - uses: ./.github/actions/local-thing'

assert_file "a docker-image action is exempt" pass "" \
  '      - uses: docker://alpine:3.19'

assert_file "a uses value with no @ref at all is refused" fail "has no @ref at all" \
  '      - uses: some/action-without-a-ref'

assert_file "multiple pinned actions in one file are all accepted" pass "" \
'      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7
      - uses: actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961 # v5'

assert_file "one unpinned action among several pinned ones is still refused" fail "not pinned to a full 40-character commit SHA" \
'      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7
      - uses: actions/setup-java@v5'

assert_file "a plain (non-list-item) uses: line is still checked" fail "not pinned to a full 40-character commit SHA" \
  '        uses: actions/checkout@v7'

assert_file "a file with no uses: lines at all is trivially accepted" pass "" \
'name: Empty
on: push
jobs:
  noop:
    runs-on: ubuntu-latest
    steps:
      - run: echo hi'

# The real repository's own workflows must pass this check end to end,
# not just the isolated function-level fixtures above.
cases_run=$((cases_run + 1))
repo_root="$(cd "$script_dir/../../.." && pwd)"
set +e
real_output="$(cd "$repo_root" && bash "$pin_script" 2>&1)"
real_result=$?
set -e
if [[ "$real_result" -eq 0 ]]; then
  echo "PASS: the repository's actual workflow files all pass this check"
else
  echo "FAIL: the repository's actual workflow files do not all pass this check" >&2
  printf '%s\n' "$real_output" | sed 's/^/    /' >&2
  failures=$((failures + 1))
fi

echo
echo "$cases_run assertion case(s) run, $failures failure(s)."
if [[ "$failures" -ne 0 ]]; then
  exit 1
fi
