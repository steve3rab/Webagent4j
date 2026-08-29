#!/usr/bin/env bash
# Regression tests for .github/scripts/check-branch-policy.sh.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
policy_script="$script_dir/../check-branch-policy.sh"

failures=0
cases_run=0

# shellcheck source=/dev/null
source "$policy_script"

# assert_case <name> <expected: pass|fail> -- <base> <head>
assert_case() {
  local name="$1" expected="$2"
  shift 2
  [[ "$1" == "--" ]]
  shift
  cases_run=$((cases_run + 1))

  local result
  if is_allowed_head_for_base "$1" "$2"; then result="pass"; else result="fail"; fi

  if [[ "$result" == "$expected" ]]; then
    echo "PASS: $name"
  else
    echo "FAIL: $name -- expected $expected, got $result (base='$1' head='$2')" >&2
    failures=$((failures + 1))
  fi
}

# --- Examples given directly by the policy -------------------------------

assert_case "feat/new-locator -> develop" pass -- develop feat/new-locator
assert_case "fix/crawler-timeout -> develop" pass -- develop fix/crawler-timeout
assert_case "version/1.1.0 -> main" pass -- main version/1.1.0
assert_case "fix/1.0.1-hotfix -> main" pass -- main fix/1.0.1-hotfix
assert_case "feat/new-locator -> main" fail -- main feat/new-locator
assert_case "develop -> main" fail -- main develop
assert_case "random-branch -> develop" fail -- develop random-branch
assert_case "main -> develop" fail -- develop main

# --- Every allowed prefix into develop ------------------------------------

assert_case "refactor/x -> develop" pass -- develop refactor/x
assert_case "version/x -> develop" pass -- develop version/x
assert_case "task/x -> develop" pass -- develop task/x
assert_case "docs/x -> develop" pass -- develop docs/x
assert_case "test/x -> develop" pass -- develop test/x

# --- Prefixes not allowed into main even though allowed into develop -----

assert_case "refactor/x -> main is refused" fail -- main refactor/x
assert_case "task/x -> main is refused" fail -- main task/x
assert_case "docs/x -> main is refused" fail -- main docs/x
assert_case "test/x -> main is refused" fail -- main test/x

# --- Prefix matching must anchor at a real path segment, not just a
# string prefix (e.g. "feature/x" must not be accepted as "feat/*").

assert_case "feature/x -> develop is refused (not the feat/ prefix)" fail -- develop feature/x
assert_case "fixup/x -> develop is refused (not the fix/ prefix)" fail -- develop fixup/x

# --- Unknown base branches fail closed by default -------------------------

assert_case "any head -> an unrecognized base is refused" fail -- staging feat/new-locator
assert_case "any head -> an empty-string-like unknown base is refused" fail -- release feat/new-locator

# --- main() wiring: usage, empty args, exit codes, and diagnostic text ---

# assert_main <name> <expected_exit> <expected_message_substring> -- args...
assert_main() {
  local name="$1" expected_exit="$2" expected_message="$3"
  shift 3
  [[ "$1" == "--" ]]
  shift
  cases_run=$((cases_run + 1))

  local output actual_exit
  set +e
  output="$(main "$@" 2>&1)"
  actual_exit=$?
  set -e

  local ok=1
  if [[ "$actual_exit" -ne "$expected_exit" ]]; then
    echo "FAIL: $name -- expected exit $expected_exit, got $actual_exit" >&2
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

assert_main "main() accepts a valid combination" 0 "Branch policy satisfied" \
  -- develop feat/new-locator

assert_main "main() refuses an invalid combination with an explicit reason" 1 "Branch policy violation" \
  -- main feat/new-locator

assert_main "main() reports the specific develop policy on refusal" 1 "PRs into 'develop' must come from" \
  -- develop random-branch

assert_main "main() reports the specific main policy on refusal" 1 "PRs into 'main' must come from" \
  -- main feat/new-locator

assert_main "main() refuses an unrecognized base with a fail-closed message" 1 "not a base branch this policy recognizes" \
  -- staging feat/new-locator

assert_main "main() refuses a missing head ref" 1 "Head ref must not be empty" \
  -- develop ""

assert_main "main() refuses a missing base ref" 1 "Base ref must not be empty" \
  -- "" feat/new-locator

assert_main "main() refuses wrong argument count" 1 "Usage:" \
  -- develop

echo
echo "$cases_run assertion case(s) run, $failures failure(s)."
if [[ "$failures" -ne 0 ]]; then
  exit 1
fi
