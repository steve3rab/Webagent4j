#!/usr/bin/env bash
# Regression tests for .github/scripts/check-release-not-published.sh.
#
# All cases run against synthetic HTTP status codes and response bodies,
# or a stubbed `curl`, so no real GitHub API call is ever made and no
# real GitHub Release is ever touched.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
guard_script="$script_dir/../check-release-not-published.sh"

failures=0
cases_run=0

# Source the guard script for its functions without running main(),
# since we are not executing it as $0.
# shellcheck source=/dev/null
source "$guard_script"

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

# assert_status <name> <expected_result: pass|fail> <expected_message_substring> -- evaluate_release_status args...
assert_status() {
  local name="$1" expected="$2" expected_message="$3"
  shift 3
  [[ "$1" == "--" ]]
  shift
  cases_run=$((cases_run + 1))

  local output result
  set +e
  if output="$(evaluate_release_status "$@" 2>&1)"; then result="pass"; else result="fail"; fi
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

# --- Case 1: no release exists (404) -> success --------------------------
no_release_body="$work_dir/no-release.json"
printf '{"message":"Not Found"}' > "$no_release_body"
assert_status "no existing release (404) is accepted" pass "No existing GitHub Release" \
  -- "v9.9.9" "404" "$no_release_body"

# --- Case 2: public release already exists -> failure ---------------------
public_release_body="$work_dir/public-release.json"
printf '{"draft": false, "prerelease": false, "tag_name": "v9.9.9"}' > "$public_release_body"
assert_status "pre-existing public release (200, draft:false) is refused" fail "PUBLIC GitHub Release" \
  -- "v9.9.9" "200" "$public_release_body"

# --- Case 3: draft release already exists -> failure -----------------------
draft_release_body="$work_dir/draft-release.json"
printf '{"draft": true, "prerelease": false, "tag_name": "v9.9.9"}' > "$draft_release_body"
assert_status "pre-existing draft release (200, draft:true) is refused" fail "DRAFT GitHub Release" \
  -- "v9.9.9" "200" "$draft_release_body"

# --- Case 4: response body cannot be parsed -> failure (indeterminate) -----
malformed_body="$work_dir/malformed.json"
printf 'not json at all' > "$malformed_body"
assert_status "malformed 200 response body is refused as indeterminate" fail "could not be determined" \
  -- "v9.9.9" "200" "$malformed_body"

# --- Case 5: 200 response missing the draft field -> failure --------------
missing_field_body="$work_dir/missing-field.json"
printf '{"tag_name": "v9.9.9"}' > "$missing_field_body"
assert_status "200 response without a draft field is refused as indeterminate" fail "no 'draft' field" \
  -- "v9.9.9" "200" "$missing_field_body"

# --- Case 6: unexpected HTTP status (server error) -> failure -------------
server_error_body="$work_dir/server-error.json"
printf '{"message":"Internal Server Error"}' > "$server_error_body"
assert_status "unexpected HTTP status (500) is refused as ambiguous" fail "Unexpected response" \
  -- "v9.9.9" "500" "$server_error_body"

# --- Case 7: unexpected HTTP status (403, e.g. rate limit) -> failure -----
rate_limited_body="$work_dir/rate-limited.json"
printf '{"message":"API rate limit exceeded"}' > "$rate_limited_body"
assert_status "unexpected HTTP status (403) is refused as ambiguous" fail "Unexpected response" \
  -- "v9.9.9" "403" "$rate_limited_body"

# --- Case 8: transport-level failure (network unreachable) never reads as
# "no release" -- exercised end-to-end through main() with curl stubbed
# out to simulate a network failure.
cases_run=$((cases_run + 1))
(
  curl() { return 7; } # simulate curl's "could not connect" exit code
  export -f curl
  export GITHUB_REPOSITORY="octo/example"
  export GITHUB_TOKEN="test-token"

  set +e
  output="$(main "v9.9.9" 2>&1)"
  exit_code=$?
  set -e

  if [[ "$exit_code" -ne 0 ]] && [[ "$output" == *"Unable to reach the GitHub API"* ]] && [[ "$output" != *"No existing GitHub Release"* ]]; then
    echo "PASS: transport failure is refused and never reported as 'no release'"
  else
    echo "FAIL: transport failure -- expected a non-zero exit with an explicit unreachable-API message" >&2
    echo "  exit=$exit_code" >&2
    printf '%s\n' "$output" | sed 's/^/    /' >&2
    exit 1
  fi
) || failures=$((failures + 1))

# --- Case 9: no release, exercised end-to-end through main() with curl
# stubbed to return a genuine 404 -- confirms the wiring between
# fetch_release_status, main and evaluate_release_status, still without
# any real network access.
cases_run=$((cases_run + 1))
(
  curl() {
    # Mimic `curl -o <file> -w '%{http_code}'`: find -o's argument, write
    # a 404 body there, print the status code on stdout.
    local out_file=""
    local args=("$@")
    for i in "${!args[@]}"; do
      if [[ "${args[$i]}" == "--output" ]]; then
        out_file="${args[$((i + 1))]}"
      fi
    done
    printf '{"message":"Not Found"}' > "$out_file"
    printf '404'
  }
  export -f curl
  export GITHUB_REPOSITORY="octo/example"
  export GITHUB_TOKEN="test-token"

  set +e
  output="$(main "v9.9.9" 2>&1)"
  exit_code=$?
  set -e

  if [[ "$exit_code" -eq 0 ]] && [[ "$output" == *"No existing GitHub Release"* ]]; then
    echo "PASS: end-to-end main() accepts a genuine 404 as 'no release'"
  else
    echo "FAIL: end-to-end main() with a stubbed 404 -- expected exit 0 and a 'no release' message" >&2
    echo "  exit=$exit_code" >&2
    printf '%s\n' "$output" | sed 's/^/    /' >&2
    exit 1
  fi
) || failures=$((failures + 1))

echo
echo "$cases_run assertion case(s) run, $failures failure(s)."
if [[ "$failures" -ne 0 ]]; then
  exit 1
fi
