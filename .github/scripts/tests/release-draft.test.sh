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

# Deterministic tests must never sleep for real: the production default
# (a few seconds) would make the bounded-polling test cases slow without
# adding any coverage. Attempt count stays generous enough that VIS-002
# (several empty scans before success) and POST-001 have room to exercise
# more than one retry within the budget.
export RELEASE_VISIBILITY_MAX_ATTEMPTS=5
export RELEASE_VISIBILITY_POLL_DELAY_SECONDS=0

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
#   MOCK_POST_CREATE_LIST_SEQUENCE          -- optional array: one distinct
#                                               releases-list body per
#                                               post-create scan attempt
#                                               (index 0 = first post-create
#                                               attempt, index 1 = second,
#                                               ...); the last element
#                                               repeats once exhausted. When
#                                               unset, every post-create
#                                               attempt reuses
#                                               MOCK_POST_CREATE_LIST_BODY,
#                                               same as before this array
#                                               existed.
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
  # Includes prerelease/target_commitish so the default scenario also
  # satisfies verify_release_by_id's strict post-create identity check
  # (cmd_create tests below call it with target_commitish "abc123sha" and
  # prerelease "false" unless a case says otherwise).
  MOCK_GET_ID_BODY='{"id": 4242, "tag_name": "v9.9.9", "draft": true, "prerelease": false, "target_commitish": "abc123sha"}'
  MOCK_PATCH_STATUS=200
  MOCK_PATCH_BODY='{"id": 4242, "tag_name": "v9.9.9", "draft": false}'
  # The post-create visibility poll re-reads the releases list after the
  # create POST -- realistically it should now see the release create
  # just made (id 4242, matching MOCK_CREATE_BODY), unlike the first
  # (pre-create) scan which saw nothing. A scenario that wants to
  # simulate a concurrent/contradictory state, or delayed list
  # visibility, overrides this (or MOCK_POST_CREATE_LIST_SEQUENCE for a
  # multi-attempt scenario).
  MOCK_POST_CREATE_LIST_BODY='[{"id": 4242, "draft": true, "tag_name": "v9.9.9"}]'
  MOCK_POST_CREATE_LIST_SEQUENCE=()
  MOCK_POST_CREATE_LIST_TRANSPORT_FAIL=false
  # A plain shell variable does not survive across the $(...) command
  # substitutions every curl call in release-draft.sh is wrapped in --
  # each one forks a subshell, so increments made inside curl() here would
  # be lost the instant that particular subshell exits. A file persists
  # across those forks within one test case, since each test case gets a
  # fresh counter file.
  list_call_count_file="$work_dir/list-call-count"
  printf '0' > "$list_call_count_file"
  # Counts only POST .../releases (create), never generate-notes or asset
  # uploads, so POST-001 can prove the create POST is invoked exactly
  # once even when the post-create visibility poll needs several GET
  # attempts before the release becomes visible in the list.
  create_post_call_count_file="$work_dir/create-post-call-count"
  printf '0' > "$create_post_call_count_file"

  # Pre-existing assets on the release, as seen by cmd_upload's
  # pre-upload check (GET .../releases/{id}/assets). Default: none, so
  # existing "upload succeeds" cases behave exactly as before Part 7's
  # idempotence check was added.
  MOCK_RELEASE_ASSETS_STATUS=200
  MOCK_RELEASE_ASSETS_BODY='[]'
  # Overrides the second (post-upload) assets-list response verbatim.
  # Left unset by default so the stub auto-derives it from
  # MOCK_RELEASE_ASSETS_BODY plus whatever was actually POSTed to the
  # uploads stub during the call -- the natural "it worked" shape.
  MOCK_RELEASE_ASSETS_AFTER_UPLOAD_BODY=""
  asset_list_call_count_file="$work_dir/asset-list-call-count"
  printf '0' > "$asset_list_call_count_file"
  uploaded_asset_names_file="$work_dir/uploaded-asset-names"
  : > "$uploaded_asset_names_file"

  # Content/status returned when cmd_upload downloads an already-present
  # asset (by numeric id) to prove byte-identity before skipping a
  # re-upload.
  MOCK_ASSET_DOWNLOAD_STATUS=200
  MOCK_ASSET_DOWNLOAD_CONTENT=""
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
      if [[ "$list_call_count" -gt 1 ]]; then
        if [[ "${MOCK_POST_CREATE_LIST_TRANSPORT_FAIL:-false}" == "true" ]]; then
          return 7
        fi
        local post_create_attempt_index=$(( list_call_count - 2 ))
        local body_to_use
        if [[ "${#MOCK_POST_CREATE_LIST_SEQUENCE[@]}" -gt 0 ]]; then
          local seq_index="$post_create_attempt_index"
          local seq_last=$(( ${#MOCK_POST_CREATE_LIST_SEQUENCE[@]} - 1 ))
          if [[ "$seq_index" -gt "$seq_last" ]]; then
            seq_index="$seq_last"
          fi
          body_to_use="${MOCK_POST_CREATE_LIST_SEQUENCE[$seq_index]}"
        elif [[ -n "${MOCK_POST_CREATE_LIST_BODY:-}" ]]; then
          body_to_use="$MOCK_POST_CREATE_LIST_BODY"
        else
          body_to_use="${MOCK_LIST_BODY:-"[]"}"
        fi
        printf '%s' "$body_to_use" > "$out_file"
        printf '%s' "${MOCK_POST_CREATE_LIST_STATUS:-${MOCK_LIST_STATUS:-200}}"
      else
        printf '%s' "${MOCK_LIST_BODY:-"[]"}" > "$out_file"
        printf '%s' "${MOCK_LIST_STATUS:-200}"
      fi
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
    # Records the exact asset name this upload POST targeted (the URL's
    # trailing name=... query param; test fixtures never use characters
    # that would need percent-decoding) so the assets-list stub below
    # can reflect it back on the post-upload re-fetch.
    printf '%s\n' "${url##*name=}" >> "$uploaded_asset_names_file"
    printf '%s' "${MOCK_UPLOAD_BODY:-"{}"}" > "$out_file"
    printf '%s' "${MOCK_UPLOAD_STATUS:-201}"
    return 0
  fi

  if [[ "$url" == *"/releases"* && "$method" == "POST" ]]; then
    local create_post_call_count
    create_post_call_count="$(( $(cat "$create_post_call_count_file") + 1 ))"
    printf '%s' "$create_post_call_count" > "$create_post_call_count_file"
    printf '%s' "${MOCK_CREATE_BODY:-"{}"}" > "$out_file"
    printf '%s' "${MOCK_CREATE_STATUS:-201}"
    return 0
  fi

  if [[ "$url" == *"/releases/assets/"[0-9]* ]]; then
    printf '%s' "${MOCK_ASSET_DOWNLOAD_CONTENT:-}" > "$out_file"
    printf '%s' "${MOCK_ASSET_DOWNLOAD_STATUS:-200}"
    return 0
  fi

  if [[ "$url" == *"/releases/"*"/assets?per_page="* ]]; then
    local asset_list_call_count
    asset_list_call_count="$(( $(cat "$asset_list_call_count_file") + 1 ))"
    printf '%s' "$asset_list_call_count" > "$asset_list_call_count_file"
    if [[ "$url" == *"&page=1" ]]; then
      if [[ "$asset_list_call_count" -eq 1 ]]; then
        printf '%s' "${MOCK_RELEASE_ASSETS_BODY:-"[]"}" > "$out_file"
      elif [[ -n "${MOCK_RELEASE_ASSETS_AFTER_UPLOAD_BODY:-}" ]]; then
        printf '%s' "$MOCK_RELEASE_ASSETS_AFTER_UPLOAD_BODY" > "$out_file"
      else
        # Auto-derive the post-upload state: the pre-existing entries
        # plus a synthetic entry for every asset name actually POSTed
        # to the uploads stub above during this call, so the final
        # "exists exactly once" re-check naturally succeeds for a
        # scenario that does not care about that exact response shape.
        local uploaded_names_json
        uploaded_names_json="$(jq -R -s -c 'split("\n") | map(select(length > 0))' "$uploaded_asset_names_file" 2>/dev/null || echo '[]')"
        jq -c -n \
          --argjson existing "${MOCK_RELEASE_ASSETS_BODY:-"[]"}" \
          --argjson names "$uploaded_names_json" \
          '$existing + ( $names | to_entries | map({id: (900000 + .key), name: .value}) )' \
          > "$out_file"
      fi
      printf '%s' "${MOCK_RELEASE_ASSETS_STATUS:-200}"
    else
      printf '[]' > "$out_file"
      printf '200'
    fi
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
# VIS-004: the pre-create scan sees nothing, the create POST succeeds,
# but the very first post-create visibility scan already finds two
# releases for the tag -- a genuine multi-release/concurrent state must
# fail immediately, with no further polling (more waiting cannot resolve
# a contradiction that already has more than one match).
MOCK_POST_CREATE_LIST_BODY='[{"id": 4242, "draft": true, "tag_name": "v9.9.9"}, {"id": 9999, "draft": true, "tag_name": "v9.9.9"}]'
assert_case "VIS-004: create refuses immediately when the first post-create scan already finds 2 releases for the tag" fail "Multiple releases now claim tag" \
  -- cmd_create "v9.9.9" "abc123sha" "false"

reset_mocks
assert_case "create rejects an invalid prerelease flag" fail "prerelease flag must be" \
  -- cmd_create "v9.9.9" "abc123sha" "maybe"

# --- create: strict create-response validation ---------------------------
#
# Every case below reuses the default reset_mocks scenario (no pre-existing
# release, a normal successful POST) and overrides only MOCK_CREATE_BODY, so
# the sole variable under test is the create POST's response schema/identity.
# All bodies are synthetic literals -- no real GitHub API call is made.

reset_mocks
assert_case "create validates a well-formed response: id 4242, matching tag, draft true" pass "4242" \
  -- cmd_create "v9.9.9" "abc123sha" "false"

reset_mocks
MOCK_CREATE_BODY='{"tag_name": "v9.9.9", "draft": true}'
assert_case "create refuses a response missing id" fail "did not contain a valid positive integer id" \
  -- cmd_create "v9.9.9" "abc123sha" "false"

reset_mocks
MOCK_CREATE_BODY='{"id": null, "tag_name": "v9.9.9", "draft": true}'
assert_case "create refuses a response with id: null" fail "did not contain a valid positive integer id" \
  -- cmd_create "v9.9.9" "abc123sha" "false"

reset_mocks
MOCK_CREATE_BODY='{"id": "4242", "tag_name": "v9.9.9", "draft": true}'
assert_case "create refuses a response with id as a string" fail "did not contain a valid positive integer id" \
  -- cmd_create "v9.9.9" "abc123sha" "false"

reset_mocks
MOCK_CREATE_BODY='{"id": 0, "tag_name": "v9.9.9", "draft": true}'
assert_case "create refuses a response with id: 0" fail "did not contain a valid positive integer id" \
  -- cmd_create "v9.9.9" "abc123sha" "false"

reset_mocks
MOCK_CREATE_BODY='{"id": -5, "tag_name": "v9.9.9", "draft": true}'
assert_case "create refuses a response with a negative id" fail "did not contain a valid positive integer id" \
  -- cmd_create "v9.9.9" "abc123sha" "false"

reset_mocks
MOCK_CREATE_BODY='{"id": 42.5, "tag_name": "v9.9.9", "draft": true}'
assert_case "create refuses a response with a non-integer id" fail "did not contain a valid positive integer id" \
  -- cmd_create "v9.9.9" "abc123sha" "false"

reset_mocks
MOCK_CREATE_BODY='{"id": 4242, "tag_name": "v-wrong-tag", "draft": true}'
assert_case "create refuses a response with the wrong tag_name" fail "expected 'v9.9.9'" \
  -- cmd_create "v9.9.9" "abc123sha" "false"

reset_mocks
MOCK_CREATE_BODY='{"id": 4242, "draft": true}'
assert_case "create refuses a response missing tag_name" fail "expected 'v9.9.9'" \
  -- cmd_create "v9.9.9" "abc123sha" "false"

reset_mocks
MOCK_CREATE_BODY='{"id": 4242, "tag_name": "v9.9.9", "draft": false}'
assert_case "create refuses a response with draft: false" fail "did not report draft == true" \
  -- cmd_create "v9.9.9" "abc123sha" "false"

reset_mocks
MOCK_CREATE_BODY='{"id": 4242, "tag_name": "v9.9.9"}'
assert_case "create refuses a response missing draft" fail "did not report draft == true" \
  -- cmd_create "v9.9.9" "abc123sha" "false"

reset_mocks
MOCK_CREATE_BODY='{"id": 4242, "tag_name": "v9.9.9", "draft": "true"}'
assert_case "create refuses a response with draft as the string \"true\"" fail "did not report draft == true" \
  -- cmd_create "v9.9.9" "abc123sha" "false"

reset_mocks
MOCK_CREATE_BODY='[{"id": 4242, "tag_name": "v9.9.9", "draft": true}]'
assert_case "create refuses a top-level array response" fail "not a JSON object" \
  -- cmd_create "v9.9.9" "abc123sha" "false"

reset_mocks
MOCK_CREATE_BODY='not json at all'
assert_case "create refuses a malformed JSON response" fail "could not be parsed as JSON" \
  -- cmd_create "v9.9.9" "abc123sha" "false"

# --- create: post-create ownership proof ----------------------------------
#
# Proves cmd_create() will never return an id after the post-create scan
# finds a release that does not match the create POST's own id/tag/draft --
# match_count == 1 alone is not enough; identity must also match exactly.

reset_mocks
MOCK_POST_CREATE_LIST_BODY='[{"id": 4242, "draft": true, "tag_name": "v9.9.9"}]'
assert_case "create succeeds when the post-create scan finds exactly the created release" pass "4242" \
  -- cmd_create "v9.9.9" "abc123sha" "false"

# The key regression case: the create POST reports id 4242, but the
# immediate post-create scan finds a *different* release (9999) uniquely
# matching the tag. Exactly one match existing is not proof of ownership --
# the one match must be the release this run itself created. Without the
# match_id == release_id comparison, this state would incorrectly pass
# (match_count == 1) and go on to upload assets to, and finalize, a release
# this run never created.
reset_mocks
MOCK_POST_CREATE_LIST_BODY='[{"id": 9999, "draft": true, "tag_name": "v9.9.9"}]'
assert_case "create refuses when the post-create match has a different id than the create response (ownership mismatch)" fail "Post-create scan found release 9999" \
  -- cmd_create "v9.9.9" "abc123sha" "false"

# VIS-003: the release never becomes visible in the list before the
# bounded deadline -- every attempt sees zero matches. This must fail
# closed (uniqueness could not be proven), but it must NOT be reported
# as "concurrent release creation": zero matches is list-endpoint lag,
# not evidence of a second creator. It also must not repeat the create
# POST -- create_post_call_count_file proves exactly one POST happened
# despite the repeated GET polling.
reset_mocks
MOCK_POST_CREATE_LIST_BODY='[]'
assert_case "VIS-003: create fails closed when the release never becomes visible before the bounded deadline" fail "did not become visible in the releases list within the bounded observation window" \
  -- cmd_create "v9.9.9" "abc123sha" "false"
vis_003_post_count="$(cat "$create_post_call_count_file")"
cases_run=$((cases_run + 1))
if [[ "$vis_003_post_count" == "1" ]]; then
  echo "PASS: VIS-003: the create POST is invoked exactly once even though the release never becomes visible"
else
  echo "FAIL: VIS-003: expected exactly 1 create POST call, got $vis_003_post_count" >&2
  failures=$((failures + 1))
fi
# assert_case only supports asserting a substring IS present, not that a
# different substring is absent, so the "must never say concurrent"
# requirement is checked directly here instead.
reset_mocks
MOCK_POST_CREATE_LIST_BODY='[]'
vis_003_output="$(cmd_create "v9.9.9" "abc123sha" "false" 2>&1 || true)"
cases_run=$((cases_run + 1))
if [[ "$vis_003_output" != *"concurrent release creation"* ]]; then
  echo "PASS: VIS-003: the deadline failure message never says 'concurrent release creation'"
else
  echo "FAIL: VIS-003: the deadline failure message must not say 'concurrent release creation'" >&2
  printf '%s\n' "$vis_003_output" | sed 's/^/    /' >&2
  failures=$((failures + 1))
fi

# Two matches is already covered above by "VIS-004: create refuses
# immediately when the first post-create scan already finds 2 releases
# for the tag".

reset_mocks
MOCK_POST_CREATE_LIST_BODY='[{"id": 4242, "draft": false, "tag_name": "v9.9.9"}]'
assert_case "create refuses when the post-create match has the right id but is no longer a draft" fail "is not a draft (draft=false)" \
  -- cmd_create "v9.9.9" "abc123sha" "false"

# scan_releases_for_tag() itself only ever returns entries whose tag_name
# exactly equals the requested tag (it filters with select(.tag_name ==
# $tag) before this point), so a match with the right id but a different
# tag can never actually reach cmd_create() through a real API response.
# cmd_create()'s own tag-equality check is retained anyway as a defensive,
# belt-and-braces assertion of the same ownership invariant; the only way
# to exercise it in isolation is to stub scan_releases_for_tag() directly
# for this one case, bypassing its own filtering, then restore the real
# implementation immediately afterward.
reset_mocks
original_scan_releases_for_tag_def="$(declare -f scan_releases_for_tag)"
eval "real_scan_releases_for_tag${original_scan_releases_for_tag_def#scan_releases_for_tag}"
scan_stub_call_count_file="$work_dir/scan-stub-call-count"
printf '0' > "$scan_stub_call_count_file"
scan_releases_for_tag() {
  local stub_tag="$1" out_matches_file="$2"
  local stub_call_count
  stub_call_count="$(( $(cat "$scan_stub_call_count_file") + 1 ))"
  printf '%s' "$stub_call_count" > "$scan_stub_call_count_file"
  if [[ "$stub_call_count" -gt 1 ]]; then
    printf '%s\n' '{"id":4242,"draft":true,"tag_name":"v-other-tag"}' > "$out_matches_file"
    return 0
  fi
  real_scan_releases_for_tag "$stub_tag" "$out_matches_file"
}
assert_case "create refuses when the post-create match has the right id but a different tag (defensive check)" fail "has tag 'v-other-tag'" \
  -- cmd_create "v9.9.9" "abc123sha" "false"
unset -f real_scan_releases_for_tag
eval "$original_scan_releases_for_tag_def"

reset_mocks
MOCK_POST_CREATE_LIST_BODY='not json at all'
assert_case "create refuses when the post-create list scan is malformed JSON" fail "post-create uniqueness scan could not be completed" \
  -- cmd_create "v9.9.9" "abc123sha" "false"

reset_mocks
MOCK_POST_CREATE_LIST_BODY='{}'
assert_case "create refuses when the post-create list scan is valid JSON but a top-level object" fail "post-create uniqueness scan could not be completed" \
  -- cmd_create "v9.9.9" "abc123sha" "false"

reset_mocks
MOCK_POST_CREATE_LIST_BODY='[{"tag_name": "v9.9.9", "draft": true}]'
assert_case "create refuses when the post-create list scan has a matching entry with a missing id" fail "post-create uniqueness scan could not be completed" \
  -- cmd_create "v9.9.9" "abc123sha" "false"

reset_mocks
MOCK_POST_CREATE_LIST_BODY='[{"id": "4242", "tag_name": "v9.9.9", "draft": true}]'
assert_case "create refuses when the post-create list scan has a matching entry with a non-numeric id" fail "post-create uniqueness scan could not be completed" \
  -- cmd_create "v9.9.9" "abc123sha" "false"

reset_mocks
MOCK_POST_CREATE_LIST_TRANSPORT_FAIL="true"
assert_case "create refuses when the post-create list scan hits a transport error" fail "post-create uniqueness scan could not be completed" \
  -- cmd_create "v9.9.9" "abc123sha" "false"

# --- create: bounded post-create visibility polling -----------------------
#
# These cases exercise wait_for_unique_release_visibility()'s bounded,
# read-only GET polling directly: GitHub's releases-list endpoint is not
# guaranteed to be immediately consistent with a just-completed create,
# so a freshly created release can be directly addressable by id yet
# briefly absent from the list. VIS-003/VIS-004 above cover the
# immediate-failure edges (never visible, contradictory multi-match);
# the cases below cover eventual success and a same-tag ownership
# mismatch that only appears after temporary invisibility.

reset_mocks
MOCK_POST_CREATE_LIST_SEQUENCE=(
  '[]'
  '[{"id": 4242, "draft": true, "tag_name": "v9.9.9"}]'
)
assert_create_stdout_contract "VIS-001: create succeeds once the second post-create scan finds the release (first scan empty)" "4242" \
  -- cmd_create "v9.9.9" "abc123sha" "false"

reset_mocks
MOCK_POST_CREATE_LIST_SEQUENCE=(
  '[]'
  '[]'
  '[]'
  '[{"id": 4242, "draft": true, "tag_name": "v9.9.9"}]'
)
assert_create_stdout_contract "VIS-002: create succeeds after several empty scans, before the bounded deadline" "4242" \
  -- cmd_create "v9.9.9" "abc123sha" "false"

# VIS-005: the release is not visible on the first post-create scan, and
# when it does become visible, a *different* release id owns the tag.
# Temporary invisibility must never relax the ownership check once a
# match does appear.
reset_mocks
MOCK_POST_CREATE_LIST_SEQUENCE=(
  '[]'
  '[{"id": 9999, "draft": true, "tag_name": "v9.9.9"}]'
)
assert_case "VIS-005: create refuses when a different release id owns the tag after temporary invisibility" fail "ownership mismatch" \
  -- cmd_create "v9.9.9" "abc123sha" "false"

# POST-001: prove the create POST is invoked exactly once even though
# the release only becomes visible after several bounded polling
# attempts -- the fix must never retry the POST itself, only the
# read-only GET/list verification after it.
reset_mocks
MOCK_POST_CREATE_LIST_SEQUENCE=(
  '[]'
  '[]'
  '[{"id": 4242, "draft": true, "tag_name": "v9.9.9"}]'
)
assert_create_stdout_contract "POST-001: create succeeds under delayed visibility" "4242" \
  -- cmd_create "v9.9.9" "abc123sha" "false"
post_001_count="$(cat "$create_post_call_count_file")"
cases_run=$((cases_run + 1))
if [[ "$post_001_count" == "1" ]]; then
  echo "PASS: POST-001: the create POST is invoked exactly once despite delayed visibility"
else
  echo "FAIL: POST-001: expected exactly 1 create POST call, got $post_001_count" >&2
  failures=$((failures + 1))
fi

# --- create: verify_release_by_id (GET-by-id identity proof) --------------
#
# validate_created_release_response already proved the create POST's own
# response was well-formed; these cases prove the *separate* immediate
# GET-by-id re-check (verify_release_by_id) also fails closed on its own,
# independent response -- a POST that reports success does not by itself
# guarantee the release is retrievable/consistent by id.

reset_mocks
MOCK_GET_ID_STATUS=404
MOCK_GET_ID_BODY='{"message":"Not Found"}'
assert_case "ID-001: create refuses when GET /releases/{id} returns 404 right after a successful POST" fail "returned HTTP 404, not 200" \
  -- cmd_create "v9.9.9" "abc123sha" "false"

reset_mocks
MOCK_GET_ID_BODY='{"id": 4242, "tag_name": "v-wrong-tag", "draft": true, "prerelease": false, "target_commitish": "abc123sha"}'
assert_case "ID-002: create refuses when GET-by-id reports the wrong tag" fail "expected 'v9.9.9'" \
  -- cmd_create "v9.9.9" "abc123sha" "false"

reset_mocks
MOCK_GET_ID_BODY='{"id": 4242, "tag_name": "v9.9.9", "draft": false, "prerelease": false, "target_commitish": "abc123sha"}'
assert_case "ID-003: create refuses when GET-by-id reports draft: false" fail "did not report draft == true (strict boolean check)" \
  -- cmd_create "v9.9.9" "abc123sha" "false"

reset_mocks
MOCK_GET_ID_BODY='{"id": 4242, "tag_name": "v9.9.9", "draft": "true", "prerelease": false, "target_commitish": "abc123sha"}'
assert_case "ID-004: create refuses when GET-by-id reports draft as the string \"true\" rather than a real boolean" fail "did not report draft == true (strict boolean check)" \
  -- cmd_create "v9.9.9" "abc123sha" "false"

# --- Part 13: synthetic reproduction of the exact v1.1.0 incident ---------
#
# Reproduces, with synthetic fixtures only, the exact sequence of GitHub
# API responses observed during the real v1.1.0 release attempt: a
# successful POST (id 4242) whose own releases-list visibility lagged
# behind the by-id GET. The fixed pipeline must publish successfully in
# that case, must fail closed (never adopt) when a second release
# appears in the list, and must fail closed -- with a precise diagnostic,
# never "concurrent release creation" -- when visibility never arrives
# before the bounded deadline.

reset_mocks
MOCK_POST_CREATE_LIST_SEQUENCE=(
  '[]'
  '[]'
  '[{"id": 4242, "draft": true, "tag_name": "v9.9.9"}]'
)
assert_create_stdout_contract "v1.1.0 scenario: pre-create=[], POST 201 id=4242, GET-by-id ok, post-create scans []/[]/[match] -> SUCCESS" "4242" \
  -- cmd_create "v9.9.9" "abc123sha" "false"

reset_mocks
MOCK_POST_CREATE_LIST_SEQUENCE=(
  '[]'
  '[{"id": 4242, "draft": true, "tag_name": "v9.9.9"}, {"id": 9999, "draft": true, "tag_name": "v9.9.9"}]'
)
assert_case "v1.1.0 scenario: post-create scans []/[two releases] -> FAIL" fail "Multiple releases now claim tag" \
  -- cmd_create "v9.9.9" "abc123sha" "false"

reset_mocks
MOCK_POST_CREATE_LIST_BODY='[]'
v1_1_0_deadline_output="$(cmd_create "v9.9.9" "abc123sha" "false" 2>&1 || true)"
cases_run=$((cases_run + 1))
if [[ "$v1_1_0_deadline_output" == *"uniqueness could not be proven"* && "$v1_1_0_deadline_output" != *"concurrent release creation"* ]]; then
  echo "PASS: v1.1.0 scenario: identity confirmed by id but never visible in the list -> FAIL CLOSED with 'uniqueness could not be proven', never 'concurrent release creation'"
else
  echo "FAIL: v1.1.0 scenario: expected a 'uniqueness could not be proven' message and never 'concurrent release creation'" >&2
  printf '%s\n' "$v1_1_0_deadline_output" | sed 's/^/    /' >&2
  failures=$((failures + 1))
fi

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

# --- upload: Part 7 asset idempotence --------------------------------------
#
# Proves cmd_upload() is safely re-runnable: an asset already present
# under the exact expected name is only ever skipped after a strong,
# content-level identity proof (a downloaded byte-for-byte hash match),
# never silently overwritten and never re-uploaded because a name merely
# matches.

reset_mocks
MOCK_RELEASE_ASSETS_BODY='[{"id": 555, "name": "asset.bin"}]'
MOCK_ASSET_DOWNLOAD_CONTENT='fake jar bytes'
assert_case "REC-idempotent-005-style: upload skips re-uploading an asset already present and byte-identical" pass "verified byte-identical; skipping upload" \
  -- cmd_upload "4242" "$asset_file"
idempotent_upload_did_not_reupload=1
if grep -q "^asset.bin$" "$uploaded_asset_names_file" 2>/dev/null; then
  idempotent_upload_did_not_reupload=0
fi
cases_run=$((cases_run + 1))
if [[ "$idempotent_upload_did_not_reupload" -eq 1 ]]; then
  echo "PASS: idempotent upload never re-POSTs an asset it already verified as identical"
else
  echo "FAIL: idempotent upload re-uploaded an asset it should have skipped" >&2
  failures=$((failures + 1))
fi

reset_mocks
MOCK_RELEASE_ASSETS_BODY='[{"id": 555, "name": "asset.bin"}]'
MOCK_ASSET_DOWNLOAD_CONTENT='different content entirely'
assert_case "REC-006: upload refuses when an existing same-named asset cannot be proven identical (content differs)" fail "sha256 mismatch" \
  -- cmd_upload "4242" "$asset_file"

reset_mocks
MOCK_RELEASE_ASSETS_BODY='[{"id": 555, "name": "asset.bin"}]'
MOCK_ASSET_DOWNLOAD_STATUS=500
assert_case "REC-006: upload refuses when downloading the existing same-named asset to prove identity fails" fail "downloading it to verify identity returned HTTP 500" \
  -- cmd_upload "4242" "$asset_file"

reset_mocks
MOCK_RELEASE_ASSETS_STATUS=500
MOCK_RELEASE_ASSETS_BODY='{"message":"Internal Server Error"}'
assert_case "upload refuses when the pre-upload existing-asset list cannot be verified" fail "could not be verified, so overwrite safety cannot be proven" \
  -- cmd_upload "4242" "$asset_file"

reset_mocks
jar_asset_file="$work_dir/webagent4j-cli-9.9.9.jar"
printf 'shaded cli jar bytes' > "$jar_asset_file"
checksum_asset_file="$work_dir/webagent4j-cli-9.9.9.jar.sha256"
printf '%s' "$(local_sha256 "$jar_asset_file")" > "$checksum_asset_file"
assert_case "upload succeeds when the .sha256 checksum file matches the jar it accompanies" pass "Uploaded asset" \
  -- cmd_upload "4242" "$jar_asset_file" "$checksum_asset_file"

reset_mocks
bad_checksum_file="$work_dir/webagent4j-cli-9.9.9.jar.sha256"
printf '%s' "0000000000000000000000000000000000000000000000000000000000000000" > "$bad_checksum_file"
assert_case "upload refuses when the .sha256 checksum file does not match the jar it accompanies" fail "does not match the actual sha256" \
  -- cmd_upload "4242" "$jar_asset_file" "$bad_checksum_file"

# --- finalize -------------------------------------------------------------

reset_mocks
assert_case "finalize publishes a release that is still a draft" pass "published" \
  -- cmd_finalize "4242" "v9.9.9"

reset_mocks
MOCK_GET_ID_BODY='{"id": 4242, "tag_name": "v9.9.9", "draft": false}'
assert_case "finalize refuses a release that is no longer a draft" fail "no longer a draft" \
  -- cmd_finalize "4242" "v9.9.9"

# jq -r strips quotes from a JSON string the same way it renders a JSON
# boolean, so draft: "true" (string) must not be silently accepted as
# equivalent to a real draft: true.
reset_mocks
MOCK_GET_ID_BODY='{"id": 4242, "tag_name": "v9.9.9", "draft": "true"}'
assert_case "finalize refuses when draft is the string \"true\" rather than a real boolean" fail "no longer a draft" \
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

# --- recovery-finalize (Part 9) ---------------------------------------------

reset_mocks
assert_case "recovery-finalize publishes a release that is still a draft, same as finalize" pass "published" \
  -- cmd_recovery_finalize "4242" "v9.9.9"

# The key distinction from finalize: an already-public release is an
# expected, valid state for recovery (a human may have already
# published it out-of-band) -- it must be verified and reported as an
# idempotent success, never re-PATCHed, and never treated as a failure.
reset_mocks
MOCK_GET_ID_BODY='{"id": 4242, "tag_name": "v9.9.9", "draft": false}'
assert_case "recovery-finalize treats an already-public release as an idempotent success, never re-PATCHing" pass "already public; recovery never re-finalizes" \
  -- cmd_recovery_finalize "4242" "v9.9.9"

reset_mocks
MOCK_GET_ID_BODY='{"id": 4242, "tag_name": "v-some-other-tag", "draft": false}'
assert_case "recovery-finalize still refuses on a tag mismatch even for an already-public release" fail "expected 'v9.9.9'" \
  -- cmd_recovery_finalize "4242" "v9.9.9"

reset_mocks
MOCK_GET_ID_BODY='{"id": 4242, "tag_name": "v9.9.9", "draft": "true"}'
assert_case "recovery-finalize refuses a non-boolean draft state rather than guessing" fail "not a well-formed boolean" \
  -- cmd_recovery_finalize "4242" "v9.9.9"

reset_mocks
MOCK_GET_ID_STATUS=500
MOCK_GET_ID_BODY='{"message":"Internal Server Error"}'
assert_case "recovery-finalize refuses when the pre-finalize re-check fails" fail "Unexpected response" \
  -- cmd_recovery_finalize "4242" "v9.9.9"

unset -f curl

echo
echo "$cases_run assertion case(s) run, $failures failure(s)."
if [[ "$failures" -ne 0 ]]; then
  exit 1
fi
