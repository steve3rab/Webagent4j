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

export GITHUB_REPOSITORY="octo/example"
export GITHUB_TOKEN="test-token"

# --- Pure-function cases: evaluate_by_tag_status ---------------------------

# assert_by_tag <name> <expected_verdict> -- evaluate_by_tag_status args...
assert_by_tag() {
  local name="$1" expected="$2"
  shift 2
  [[ "$1" == "--" ]]
  shift
  cases_run=$((cases_run + 1))

  local verdict
  verdict="$(evaluate_by_tag_status "$@" 2>/dev/null)"

  if [[ "$verdict" == "$expected" ]]; then
    echo "PASS: $name"
  else
    echo "FAIL: $name -- expected verdict '$expected', got '$verdict'" >&2
    failures=$((failures + 1))
  fi
}

no_release_body="$work_dir/no-release.json"
printf '{"message":"Not Found"}' > "$no_release_body"
assert_by_tag "by-tag 404 is 'absent'" absent -- "v9.9.9" "404" "$no_release_body"

public_release_body="$work_dir/public-release.json"
printf '{"draft": false, "prerelease": false, "tag_name": "v9.9.9"}' > "$public_release_body"
assert_by_tag "by-tag 200 draft:false is 'public'" public -- "v9.9.9" "200" "$public_release_body"

draft_release_body="$work_dir/draft-release.json"
printf '{"draft": true, "prerelease": false, "tag_name": "v9.9.9"}' > "$draft_release_body"
assert_by_tag "by-tag 200 draft:true is 'draft'" draft -- "v9.9.9" "200" "$draft_release_body"

malformed_body="$work_dir/malformed.json"
printf 'not json at all' > "$malformed_body"
assert_by_tag "by-tag malformed 200 body is 'indeterminate'" indeterminate -- "v9.9.9" "200" "$malformed_body"

missing_field_body="$work_dir/missing-field.json"
printf '{"tag_name": "v9.9.9"}' > "$missing_field_body"
assert_by_tag "by-tag 200 without a draft field is 'indeterminate'" indeterminate -- "v9.9.9" "200" "$missing_field_body"

server_error_body="$work_dir/server-error.json"
printf '{"message":"Internal Server Error"}' > "$server_error_body"
assert_by_tag "by-tag HTTP 500 is 'indeterminate'" indeterminate -- "v9.9.9" "500" "$server_error_body"

rate_limited_body="$work_dir/rate-limited.json"
printf '{"message":"API rate limit exceeded"}' > "$rate_limited_body"
assert_by_tag "by-tag HTTP 403 is 'indeterminate'" indeterminate -- "v9.9.9" "403" "$rate_limited_body"

# --- End-to-end cases: check_release_absent, with curl stubbed -------------
# A single stub serves both the by-tag endpoint and the paginated releases
# list, dispatching on the requested URL, so each scenario below exercises
# the full check_release_absent flow exactly as main() invokes it.

# mock_curl_dispatch reads a "plan" the test case defines as environment
# variables before calling curl:
#   MOCK_BY_TAG_STATUS / MOCK_BY_TAG_BODY   -- response for /releases/tags/*
#   MOCK_LIST_STATUS   / MOCK_LIST_BODY     -- response for the *first* list
#                                              page; subsequent pages (if
#                                              MOCK_LIST_BODY has 100+
#                                              entries) are not exercised by
#                                              these tests, which all use
#                                              small fixture lists
#   MOCK_TRANSPORT_FAIL_ON                  -- "by-tag" or "list" to make
#                                              that call fail as if curl
#                                              itself could not connect
curl() {
  local args=("$@")
  local out_file="" url=""
  local i
  for i in "${!args[@]}"; do
    case "${args[$i]}" in
      --output) out_file="${args[$((i + 1))]}" ;;
    esac
  done
  url="${args[-1]}"

  if [[ "$url" == *"/releases/tags/"* ]]; then
    if [[ "${MOCK_TRANSPORT_FAIL_ON:-}" == "by-tag" ]]; then
      return 7
    fi
    printf '%s' "${MOCK_BY_TAG_BODY:-"{}"}" > "$out_file"
    printf '%s' "${MOCK_BY_TAG_STATUS:-404}"
    return 0
  fi

  if [[ "$url" == *"/releases?per_page="* ]]; then
    if [[ "${MOCK_TRANSPORT_FAIL_ON:-}" == "list" ]]; then
      return 7
    fi
    # Only ever answer page 1 with the fixture; any further page (which
    # would only be requested if page 1 returned a full 100 entries, never
    # the case in these small fixtures) is an empty page, ending pagination.
    if [[ "$url" == *"&page=1" ]]; then
      printf '%s' "${MOCK_LIST_BODY:-"[]"}" > "$out_file"
      printf '%s' "${MOCK_LIST_STATUS:-200}"
    else
      printf '[]' > "$out_file"
      printf '200'
    fi
    return 0
  fi

  echo "unexpected curl invocation in test stub: ${args[*]}" >&2
  return 99
}
export -f curl

# assert_absent <name> <expected: pass|fail> <expected_message_substring>
assert_absent() {
  local name="$1" expected="$2" expected_message="$3"
  cases_run=$((cases_run + 1))

  local output result
  set +e
  if output="$(check_release_absent "v9.9.9" 2>&1)"; then result="pass"; else result="fail"; fi
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

# Case: no release at all -> success.
MOCK_BY_TAG_STATUS=404
MOCK_BY_TAG_BODY='{"message":"Not Found"}'
MOCK_LIST_STATUS=200
MOCK_LIST_BODY='[]'
unset MOCK_TRANSPORT_FAIL_ON
assert_absent "no release at all is accepted" pass "No existing GitHub Release"

# Case: public release found directly via the by-tag endpoint -> failure.
MOCK_BY_TAG_STATUS=200
MOCK_BY_TAG_BODY='{"draft": false, "tag_name": "v9.9.9"}'
assert_absent "public release via by-tag endpoint is refused" fail "PUBLIC GitHub Release"

# Case: draft reported directly by the by-tag endpoint (defensive case,
# should GitHub's documented behavior ever change) -> failure.
MOCK_BY_TAG_STATUS=200
MOCK_BY_TAG_BODY='{"draft": true, "tag_name": "v9.9.9"}'
assert_absent "draft reported directly via by-tag endpoint is refused" fail "DRAFT GitHub Release"

# Case: the required scenario -- by-tag endpoint says 404 (as GitHub always
# does for a draft), but the release is visible in the full releases list.
MOCK_BY_TAG_STATUS=404
MOCK_BY_TAG_BODY='{"message":"Not Found"}'
MOCK_LIST_STATUS=200
MOCK_LIST_BODY='[{"id": 123, "draft": true, "tag_name": "v9.9.9"}]'
assert_absent "draft invisible via by-tag but present in the releases list is refused" fail "not visible via the by-tag lookup"

# Case: releases list shows a non-draft release for the tag even though
# by-tag said 404 -- a genuine contradiction between the two sources.
MOCK_LIST_BODY='[{"id": 123, "draft": false, "tag_name": "v9.9.9"}]'
assert_absent "contradiction between by-tag 404 and a published match in the list is refused" fail "contradictory release state"

# Case: multiple releases claim the same tag in the list -- contradictory /
# concurrent state.
MOCK_LIST_BODY='[{"id": 123, "draft": true, "tag_name": "v9.9.9"}, {"id": 456, "draft": false, "tag_name": "v9.9.9"}]'
assert_absent "multiple releases matching the same tag in the list are refused" fail "contradictory or concurrent release state"

# Case: releases list page cannot be parsed -> indeterminate, refused.
MOCK_LIST_STATUS=200
MOCK_LIST_BODY='not json at all'
assert_absent "unparseable releases list page is refused as indeterminate" fail "could not be scanned"

# Case: releases list responds with an unexpected HTTP status.
MOCK_LIST_STATUS=500
MOCK_LIST_BODY='{"message":"Internal Server Error"}'
assert_absent "releases list HTTP 500 is refused" fail "could not be scanned"

MOCK_LIST_STATUS=403
MOCK_LIST_BODY='{"message":"API rate limit exceeded"}'
assert_absent "releases list HTTP 403 is refused" fail "could not be scanned"

# Case: transport failure reaching the by-tag endpoint -- never conflated
# with "no release".
MOCK_BY_TAG_STATUS=404
MOCK_BY_TAG_BODY='{"message":"Not Found"}'
MOCK_LIST_STATUS=200
MOCK_LIST_BODY='[]'
MOCK_TRANSPORT_FAIL_ON="by-tag"
assert_absent "transport failure on the by-tag lookup is refused, not treated as absent" fail "Unable to reach the GitHub API"

# Case: transport failure reaching the releases list (by-tag succeeded
# with 404, so the scan is still required and its failure must not be
# treated as "no draft found").
MOCK_TRANSPORT_FAIL_ON="list"
assert_absent "transport failure on the releases list scan is refused, not treated as absent" fail "could not be scanned"

# --- releases-list shape validation -----------------------------------
# scan_releases_for_tag() (and therefore check_release_absent()) must
# explicitly validate that a 200 releases-list page is a JSON array
# before trusting jq's `.[]`/`length` behavior on it: both silently treat
# a `{}` object as if it were an empty array, which would otherwise make
# a wrong-shaped response indistinguishable from "no release exists".
MOCK_BY_TAG_STATUS=404
MOCK_BY_TAG_BODY='{"message":"Not Found"}'
unset MOCK_TRANSPORT_FAIL_ON

MOCK_LIST_STATUS=200
MOCK_LIST_BODY='{}'
assert_absent "releases list HTTP 200 with a top-level JSON object is refused as indeterminate" fail "not a JSON array"

MOCK_LIST_BODY='null'
assert_absent "releases list HTTP 200 with a top-level JSON null is refused as indeterminate" fail "not a JSON array"

MOCK_LIST_BODY='"hello"'
assert_absent "releases list HTTP 200 with a top-level JSON string is refused as indeterminate" fail "not a JSON array"

MOCK_LIST_BODY='123'
assert_absent "releases list HTTP 200 with a top-level JSON number is refused as indeterminate" fail "not a JSON array"

MOCK_LIST_BODY='true'
assert_absent "releases list HTTP 200 with a top-level JSON boolean is refused as indeterminate" fail "not a JSON array"

# A matching-tag entry with malformed/missing required fields must not be
# silently transformed into a partially-null match record and accepted.
MOCK_LIST_BODY='[{"tag_name": "v9.9.9", "draft": true}]'
assert_absent "a matching-tag entry with a missing id is refused, not silently accepted" fail "malformed identity fields"

MOCK_LIST_BODY='[{"id": "123", "tag_name": "v9.9.9", "draft": true}]'
assert_absent "a matching-tag entry with a non-numeric id is refused, not silently accepted" fail "malformed identity fields"

MOCK_LIST_BODY='[{"id": 123, "tag_name": "v9.9.9"}]'
assert_absent "a matching-tag entry with a missing draft field is refused, not silently accepted" fail "malformed identity fields"

unset -f curl

echo
echo "$cases_run assertion case(s) run, $failures failure(s)."
if [[ "$failures" -ne 0 ]]; then
  exit 1
fi
