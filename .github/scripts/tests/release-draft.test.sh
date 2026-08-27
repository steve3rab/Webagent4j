#!/usr/bin/env bash
# Regression tests for .github/scripts/release-draft.sh.
#
# All cases run against a stubbed `curl`, so no real GitHub API or
# uploads-API call is ever made and no real GitHub Release is ever
# created, uploaded to, or finalized.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
release_draft_script="$script_dir/../release-draft.sh"

failures=0
cases_run=0

# shellcheck source=/dev/null
source "$release_draft_script"

export GITHUB_REPOSITORY="octo/example"
export GITHUB_TOKEN="test-token"

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

# Every scenario configures the stub via these variables before calling
# the function under test:
#   MOCK_BY_TAG_STATUS / MOCK_BY_TAG_BODY   -- GET .../releases/tags/{tag}
#   MOCK_LIST_STATUS   / MOCK_LIST_BODY     -- GET .../releases?per_page=...
#   MOCK_NOTES_STATUS  / MOCK_NOTES_BODY    -- POST .../releases/generate-notes
#   MOCK_CREATE_STATUS / MOCK_CREATE_BODY   -- POST .../releases
#   MOCK_UPLOAD_STATUS / MOCK_UPLOAD_BODY   -- POST uploads.../assets
#   MOCK_GET_ID_STATUS / MOCK_GET_ID_BODY   -- GET .../releases/{id}
#   MOCK_PATCH_STATUS  / MOCK_PATCH_BODY    -- PATCH .../releases/{id}
reset_mocks() {
  MOCK_BY_TAG_STATUS=404
  MOCK_BY_TAG_BODY='{"message":"Not Found"}'
  MOCK_LIST_STATUS=200
  MOCK_LIST_BODY='[]'
  MOCK_NOTES_STATUS=200
  MOCK_NOTES_BODY='{"name":"v9.9.9","body":"generated notes"}'
  MOCK_CREATE_STATUS=201
  MOCK_CREATE_BODY='{"id": 4242, "tag_name": "v9.9.9", "draft": true}'
  MOCK_UPLOAD_STATUS=201
  MOCK_UPLOAD_BODY='{"id": 1, "name": "asset"}'
  MOCK_GET_ID_STATUS=200
  MOCK_GET_ID_BODY='{"id": 4242, "tag_name": "v9.9.9", "draft": true}'
  MOCK_PATCH_STATUS=200
  MOCK_PATCH_BODY='{"id": 4242, "tag_name": "v9.9.9", "draft": false}'
  # The post-create uniqueness scan is a *second* call to the releases
  # list, made after the create POST -- realistically it should now see
  # the release create just made (id 4242, matching MOCK_CREATE_BODY),
  # unlike the first (pre-create) scan which saw nothing. A scenario that
  # wants to simulate a concurrent creation overrides this to include
  # additional entries.
  MOCK_POST_CREATE_LIST_BODY='[{"id": 4242, "draft": true, "tag_name": "v9.9.9"}]'
  # A plain shell variable does not survive across the $(...) command
  # substitutions every curl call in release-draft.sh is wrapped in --
  # each one forks a subshell, so increments made inside curl() here would
  # be lost the instant that particular subshell exits. A file persists
  # across those forks within one test case, since each test case gets a
  # fresh counter file.
  list_call_count_file="$work_dir/list-call-count"
  printf '0' > "$list_call_count_file"
}

curl() {
  local args=("$@")
  local out_file="" url="" method="GET"
  local i
  for i in "${!args[@]}"; do
    case "${args[$i]}" in
      --output) out_file="${args[$((i + 1))]}" ;;
      --request) method="${args[$((i + 1))]}" ;;
    esac
  done
  url="${args[-1]}"

  if [[ "$url" == *"/releases/tags/"* ]]; then
    printf '%s' "${MOCK_BY_TAG_BODY:-"{}"}" > "$out_file"
    printf '%s' "${MOCK_BY_TAG_STATUS:-404}"
    return 0
  fi

  if [[ "$url" == *"/releases?per_page="* ]]; then
    local list_call_count
    list_call_count="$(( $(cat "$list_call_count_file") + 1 ))"
    printf '%s' "$list_call_count" > "$list_call_count_file"
    if [[ "$url" == *"&page=1" ]]; then
      if [[ "$list_call_count" -gt 1 && -n "${MOCK_POST_CREATE_LIST_BODY:-}" ]]; then
        printf '%s' "$MOCK_POST_CREATE_LIST_BODY" > "$out_file"
      else
        printf '%s' "${MOCK_LIST_BODY:-"[]"}" > "$out_file"
      fi
      printf '%s' "${MOCK_LIST_STATUS:-200}"
    else
      printf '[]' > "$out_file"
      printf '200'
    fi
    return 0
  fi

  if [[ "$url" == *"/releases/generate-notes" ]]; then
    printf '%s' "${MOCK_NOTES_BODY:-"{}"}" > "$out_file"
    printf '%s' "${MOCK_NOTES_STATUS:-200}"
    return 0
  fi

  if [[ "$url" == *"uploads.github.com"* ]]; then
    printf '%s' "${MOCK_UPLOAD_BODY:-"{}"}" > "$out_file"
    printf '%s' "${MOCK_UPLOAD_STATUS:-201}"
    return 0
  fi

  if [[ "$url" == *"/releases"* && "$method" == "POST" ]]; then
    printf '%s' "${MOCK_CREATE_BODY:-"{}"}" > "$out_file"
    printf '%s' "${MOCK_CREATE_STATUS:-201}"
    return 0
  fi

  if [[ "$url" == *"/releases/"[0-9]* && "$method" == "GET" ]]; then
    printf '%s' "${MOCK_GET_ID_BODY:-"{}"}" > "$out_file"
    printf '%s' "${MOCK_GET_ID_STATUS:-200}"
    return 0
  fi

  if [[ "$url" == *"/releases/"[0-9]* && "$method" == "PATCH" ]]; then
    printf '%s' "${MOCK_PATCH_BODY:-"{}"}" > "$out_file"
    printf '%s' "${MOCK_PATCH_STATUS:-200}"
    return 0
  fi

  echo "unexpected curl invocation in test stub: method=$method url=$url" >&2
  return 99
}
export -f curl

# assert_case <name> <expected: pass|fail> <expected_message_substring> -- fn args...
assert_case() {
  local name="$1" expected="$2" expected_message="$3"
  shift 3
  [[ "$1" == "--" ]]
  shift
  cases_run=$((cases_run + 1))

  local output result
  set +e
  if output="$("$@" 2>&1)"; then result="pass"; else result="fail"; fi
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

# --- create -----------------------------------------------------------

reset_mocks
assert_case "create succeeds when no release exists and prints the release id" pass "4242" \
  -- cmd_create "v9.9.9" "abc123sha" "false"

# assert_create_stdout_contract <name> <expected_release_id> -- args...
#
# Proves the exact stdout/stderr contract a successful `create` must
# honor: callers such as release.yml capture cmd_create's stdout
# verbatim as the release id (release_id="$(... release-draft.sh create
# ...)"), so *any* extra line on stdout -- including
# check_release_absent's own "No existing GitHub Release..." success
# message -- would corrupt that value. Captures stdout and stderr into
# separate files (not merged via 2>&1, unlike assert_case above) so each
# stream can be asserted independently. This is the test that must fail
# if the `check_release_absent "$tag" >&2` redirection inside
# cmd_create() is ever removed.
assert_create_stdout_contract() {
  local name="$1" expected_release_id="$2"
  shift 2
  [[ "$1" == "--" ]]
  shift
  cases_run=$((cases_run + 1))

  local stdout_file stderr_file exit_code
  stdout_file="$work_dir/stdout-$cases_run"
  stderr_file="$work_dir/stderr-$cases_run"

  set +e
  "$@" >"$stdout_file" 2>"$stderr_file"
  exit_code=$?
  set -e

  local stdout_content stdout_line_count
  stdout_content="$(cat "$stdout_file")"
  stdout_line_count="$(wc -l < "$stdout_file" | tr -d '[:space:]')"

  local ok=1
  local -a problems=()

  if [[ "$exit_code" -ne 0 ]]; then
    problems+=("expected exit code 0, got $exit_code")
    ok=0
  fi

  if [[ "$stdout_content" != "$expected_release_id" ]]; then
    problems+=("expected stdout to be exactly '$expected_release_id', got '$stdout_content'")
    ok=0
  fi

  if [[ "$stdout_line_count" -ne 1 ]]; then
    problems+=("expected stdout to contain exactly one line, got $stdout_line_count")
    ok=0
  fi

  if ! [[ "$stdout_content" =~ ^[0-9]+$ ]]; then
    problems+=("expected stdout to match a numeric release id only, got '$stdout_content'")
    ok=0
  fi

  if [[ "$stdout_content" == *"No existing GitHub Release"* ]]; then
    problems+=("the 'No existing GitHub Release' diagnostic leaked onto stdout")
    ok=0
  fi

  if [[ "$ok" -eq 1 ]]; then
    echo "PASS: $name"
  else
    echo "FAIL: $name" >&2
    local problem
    for problem in "${problems[@]}"; do
      echo "  - $problem" >&2
    done
    echo "  stdout:" >&2
    sed 's/^/    /' "$stdout_file" >&2
    echo "  stderr:" >&2
    sed 's/^/    /' "$stderr_file" >&2
    failures=$((failures + 1))
  fi
}

reset_mocks
assert_create_stdout_contract "create's successful stdout contains only the numeric release id (release.yml captures this verbatim)" "4242" \
  -- cmd_create "v9.9.9" "abc123sha" "false"

# The pre-create informational diagnostic is expected to still exist --
# just on stderr, where it belongs, not swallowed entirely.
reset_mocks
create_stderr_file="$work_dir/create-diagnostic-stderr"
cmd_create "v9.9.9" "abc123sha" "false" >/dev/null 2>"$create_stderr_file"
cases_run=$((cases_run + 1))
if grep -q "No existing GitHub Release" "$create_stderr_file"; then
  echo "PASS: create's pre-flight 'No existing GitHub Release' diagnostic still reaches stderr"
else
  echo "FAIL: create's pre-flight 'No existing GitHub Release' diagnostic did not reach stderr" >&2
  sed 's/^/    /' "$create_stderr_file" >&2
  failures=$((failures + 1))
fi

reset_mocks
MOCK_BY_TAG_STATUS=200
MOCK_BY_TAG_BODY='{"draft": false, "tag_name": "v9.9.9"}'
assert_case "create refuses when a public release already exists for the tag" fail "already exists" \
  -- cmd_create "v9.9.9" "abc123sha" "false"

reset_mocks
MOCK_LIST_BODY='[{"id": 111, "draft": true, "tag_name": "v9.9.9"}]'
assert_case "create refuses when a draft only visible via the releases list already exists" fail "already exists" \
  -- cmd_create "v9.9.9" "abc123sha" "false"

reset_mocks
MOCK_CREATE_STATUS=422
MOCK_CREATE_BODY='{"message":"Validation Failed"}'
assert_case "create refuses when the create POST itself fails" fail "Failed to create the draft release" \
  -- cmd_create "v9.9.9" "abc123sha" "false"

reset_mocks
# The pre-create scan sees nothing, the create POST succeeds, but the
# immediate post-create uniqueness re-scan now finds two releases for the
# tag -- a concurrent creation raced this run.
MOCK_POST_CREATE_LIST_BODY='[{"id": 4242, "draft": true, "tag_name": "v9.9.9"}, {"id": 9999, "draft": true, "tag_name": "v9.9.9"}]'
assert_case "create refuses when a concurrent release creation is detected after its own POST" fail "concurrent release creation" \
  -- cmd_create "v9.9.9" "abc123sha" "false"

reset_mocks
assert_case "create rejects an invalid prerelease flag" fail "prerelease flag must be" \
  -- cmd_create "v9.9.9" "abc123sha" "maybe"

# --- upload -------------------------------------------------------------

reset_mocks
asset_file="$work_dir/asset.bin"
printf 'fake jar bytes' > "$asset_file"
assert_case "upload succeeds for an existing, non-empty asset file" pass "Uploaded asset" \
  -- cmd_upload "4242" "$asset_file"

reset_mocks
assert_case "upload refuses a missing asset file" fail "does not exist or is empty" \
  -- cmd_upload "4242" "$work_dir/does-not-exist.bin"

reset_mocks
empty_file="$work_dir/empty.bin"
: > "$empty_file"
assert_case "upload refuses an empty asset file" fail "does not exist or is empty" \
  -- cmd_upload "4242" "$empty_file"

reset_mocks
MOCK_UPLOAD_STATUS=422
MOCK_UPLOAD_BODY='{"message":"Validation Failed", "errors":[{"code":"already_exists"}]}'
assert_case "upload refuses when the uploads API rejects the asset" fail "Failed to upload asset" \
  -- cmd_upload "4242" "$asset_file"

# --- finalize -------------------------------------------------------------

reset_mocks
assert_case "finalize publishes a release that is still a draft" pass "published" \
  -- cmd_finalize "4242" "v9.9.9"

reset_mocks
MOCK_GET_ID_BODY='{"id": 4242, "tag_name": "v9.9.9", "draft": false}'
assert_case "finalize refuses a release that is no longer a draft" fail "no longer a draft" \
  -- cmd_finalize "4242" "v9.9.9"

reset_mocks
MOCK_GET_ID_BODY='{"id": 4242, "tag_name": "v-some-other-tag", "draft": true}'
assert_case "finalize refuses on a tag mismatch" fail "expected 'v9.9.9'" \
  -- cmd_finalize "4242" "v9.9.9"

reset_mocks
MOCK_GET_ID_STATUS=500
MOCK_GET_ID_BODY='{"message":"Internal Server Error"}'
assert_case "finalize refuses when the pre-finalize re-check fails" fail "Unexpected response" \
  -- cmd_finalize "4242" "v9.9.9"

reset_mocks
MOCK_PATCH_STATUS=500
MOCK_PATCH_BODY='{"message":"Internal Server Error"}'
assert_case "finalize refuses when the publish PATCH itself fails" fail "Failed to finalize" \
  -- cmd_finalize "4242" "v9.9.9"

unset -f curl

echo
echo "$cases_run assertion case(s) run, $failures failure(s)."
if [[ "$failures" -ne 0 ]]; then
  exit 1
fi
