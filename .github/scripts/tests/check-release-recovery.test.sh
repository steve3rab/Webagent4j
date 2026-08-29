#!/usr/bin/env bash
# Regression tests for .github/scripts/check-release-recovery.sh.
#
# All cases run against a stubbed `curl`, so no real GitHub API call is
# ever made and no real release, workflow run, job, or artifact is ever
# read from or written to.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
check_release_recovery_script="$script_dir/../check-release-recovery.sh"

failures=0
cases_run=0

# shellcheck source=/dev/null
source "$check_release_recovery_script"

export GITHUB_REPOSITORY="octo/example"
export GITHUB_TOKEN="test-token"

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

# Every scenario configures the stub via these variables before calling
# the function under test:
#   MOCK_GET_ID_STATUS   / MOCK_GET_ID_BODY   -- GET .../releases/{id}
#   MOCK_BY_TAG_STATUS   / MOCK_BY_TAG_BODY   -- GET .../releases/tags/{tag}
#   MOCK_RUN_STATUS      / MOCK_RUN_BODY      -- GET .../actions/runs/{id}
#   MOCK_JOBS_STATUS     / MOCK_JOBS_BODY     -- GET .../actions/runs/{id}/jobs
#   MOCK_ARTIFACTS_STATUS/ MOCK_ARTIFACTS_BODY-- GET .../actions/runs/{id}/artifacts
#   MOCK_TRANSPORT_FAIL_PATTERN -- when a call's URL contains this
#                                   (non-empty) substring, curl returns a
#                                   transport-level failure (exit 7)
#                                   instead of any status/body.
reset_mocks() {
  MOCK_GET_ID_STATUS=200
  MOCK_GET_ID_BODY='{"id": 379056264, "tag_name": "v1.1.0", "draft": true, "prerelease": false, "target_commitish": "a7f43b43004a4ba475a66a5e9fb1fe9c8f9a9961"}'
  MOCK_BY_TAG_STATUS=404
  MOCK_BY_TAG_BODY='{"message":"Not Found"}'
  MOCK_RUN_STATUS=200
  MOCK_RUN_BODY='{"path": ".github/workflows/release.yml", "event": "push", "head_branch": "v1.1.0", "head_sha": "a7f43b43004a4ba475a66a5e9fb1fe9c8f9a9961"}'
  MOCK_JOBS_STATUS=200
  MOCK_JOBS_BODY='{"total_count": 1, "jobs": [{"name": "Verify release candidate", "conclusion": "success"}]}'
  MOCK_ARTIFACTS_STATUS=200
  MOCK_ARTIFACTS_BODY='{"total_count": 1, "artifacts": [{"name": "release-bundle-1.1.0", "expired": false, "digest": "sha256:abc123"}]}'
  MOCK_TRANSPORT_FAIL_PATTERN=""
}

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

  if [[ -n "$MOCK_TRANSPORT_FAIL_PATTERN" && "$url" == *"$MOCK_TRANSPORT_FAIL_PATTERN"* ]]; then
    return 7
  fi

  if [[ "$url" == *"/releases/tags/"* ]]; then
    printf '%s' "${MOCK_BY_TAG_BODY:-"{}"}" > "$out_file"
    printf '%s' "${MOCK_BY_TAG_STATUS:-404}"
    return 0
  fi

  if [[ "$url" == *"/actions/runs/"*"/jobs"* ]]; then
    printf '%s' "${MOCK_JOBS_BODY:-"{}"}" > "$out_file"
    printf '%s' "${MOCK_JOBS_STATUS:-200}"
    return 0
  fi

  if [[ "$url" == *"/actions/runs/"*"/artifacts"* ]]; then
    printf '%s' "${MOCK_ARTIFACTS_BODY:-"{}"}" > "$out_file"
    printf '%s' "${MOCK_ARTIFACTS_STATUS:-200}"
    return 0
  fi

  if [[ "$url" == *"/actions/runs/"* ]]; then
    printf '%s' "${MOCK_RUN_BODY:-"{}"}" > "$out_file"
    printf '%s' "${MOCK_RUN_STATUS:-200}"
    return 0
  fi

  if [[ "$url" == *"/releases/"[0-9]* ]]; then
    printf '%s' "${MOCK_GET_ID_BODY:-"{}"}" > "$out_file"
    printf '%s' "${MOCK_GET_ID_STATUS:-200}"
    return 0
  fi

  echo "unexpected curl invocation in test stub: url=$url" >&2
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

# --- identity: check_release_recovery_identity -----------------------------

reset_mocks
assert_case "REC-001: exact release id + tag + sha verifies as the expected release" pass "verified as the exact expected release for recovery" \
  -- check_release_recovery_identity "379056264" "v1.1.0" "a7f43b43004a4ba475a66a5e9fb1fe9c8f9a9961"

reset_mocks
MOCK_GET_ID_STATUS=404
MOCK_GET_ID_BODY='{"message":"Not Found"}'
assert_case "REC-002: a nonexistent release id fails closed" fail "returned HTTP 404, not 200" \
  -- check_release_recovery_identity "999999999" "v1.1.0" "a7f43b43004a4ba475a66a5e9fb1fe9c8f9a9961"

reset_mocks
MOCK_GET_ID_BODY='{"id": 379056264, "tag_name": "v-wrong-tag", "draft": true, "prerelease": false, "target_commitish": "a7f43b43004a4ba475a66a5e9fb1fe9c8f9a9961"}'
assert_case "REC-003: exact release id but the wrong tag fails closed" fail "expected 'v1.1.0'" \
  -- check_release_recovery_identity "379056264" "v1.1.0" "a7f43b43004a4ba475a66a5e9fb1fe9c8f9a9961"

# REC-004: the release at the given id reports the right tag, but the
# by-tag endpoint says a *different* release id is the published owner
# of that same tag -- a contradiction that must never be silently
# resolved in favor of the id recovery was told to resume.
reset_mocks
MOCK_BY_TAG_STATUS=200
MOCK_BY_TAG_BODY='{"id": 111111111, "tag_name": "v1.1.0", "draft": false}'
assert_case "REC-004: a different release id owns the tag per the by-tag lookup -- cannot adopt" fail "A different release owns this tag" \
  -- check_release_recovery_identity "379056264" "v1.1.0" "a7f43b43004a4ba475a66a5e9fb1fe9c8f9a9961"

reset_mocks
MOCK_GET_ID_BODY='{"id": 379056264, "tag_name": "v1.1.0", "draft": false, "prerelease": false, "target_commitish": "a7f43b43004a4ba475a66a5e9fb1fe9c8f9a9961", "assets": []}'
assert_case "REC-005: a correct PUBLIC release with no assets still verifies (recovery may resume a public release)" pass "verified as the exact expected release for recovery" \
  -- check_release_recovery_identity "379056264" "v1.1.0" "a7f43b43004a4ba475a66a5e9fb1fe9c8f9a9961"

# REC-006 (a public release with a same-named asset that cannot be
# proven identical must refuse to upload) is an asset-upload concern,
# not a release-identity concern -- check-release-recovery.sh never
# looks at assets. It is covered by release-draft.sh's cmd_upload
# idempotence tests: see release-draft.test.sh, cases "REC-006: upload
# refuses when an existing same-named asset cannot be proven identical
# (content differs)" and "...cannot even be downloaded to prove
# identity".

reset_mocks
MOCK_GET_ID_BODY='{"id": 379056264, "tag_name": "v1.1.0", "draft": true, "prerelease": false, "target_commitish": "a7f43b43004a4ba475a66a5e9fb1fe9c8f9a9961"}'
assert_case "REC-007: a correct DRAFT release verifies" pass "verified as the exact expected release for recovery" \
  -- check_release_recovery_identity "379056264" "v1.1.0" "a7f43b43004a4ba475a66a5e9fb1fe9c8f9a9961"

reset_mocks
MOCK_GET_ID_BODY='not json at all'
assert_case "REC-008: a malformed (non-JSON) API response fails closed" fail "could not be parsed as a JSON object" \
  -- check_release_recovery_identity "379056264" "v1.1.0" "a7f43b43004a4ba475a66a5e9fb1fe9c8f9a9961"

reset_mocks
MOCK_TRANSPORT_FAIL_PATTERN="/releases/379056264"
assert_case "REC-009: a network/transport error fails closed" fail "Unable to reach the GitHub API to verify release" \
  -- check_release_recovery_identity "379056264" "v1.1.0" "a7f43b43004a4ba475a66a5e9fb1fe9c8f9a9961"

# Defensive: draft as a non-boolean must not be silently accepted as
# either state.
reset_mocks
MOCK_GET_ID_BODY='{"id": 379056264, "tag_name": "v1.1.0", "draft": "true", "prerelease": false, "target_commitish": "a7f43b43004a4ba475a66a5e9fb1fe9c8f9a9961"}'
assert_case "identity refuses a non-boolean draft field" fail "did not report a well-formed boolean draft state" \
  -- check_release_recovery_identity "379056264" "v1.1.0" "a7f43b43004a4ba475a66a5e9fb1fe9c8f9a9961"

reset_mocks
MOCK_GET_ID_BODY='{"id": 379056264, "tag_name": "v1.1.0", "draft": true, "prerelease": "false", "target_commitish": "a7f43b43004a4ba475a66a5e9fb1fe9c8f9a9961"}'
assert_case "identity refuses a non-boolean prerelease field" fail "did not report a well-formed boolean prerelease state" \
  -- check_release_recovery_identity "379056264" "v1.1.0" "a7f43b43004a4ba475a66a5e9fb1fe9c8f9a9961"

reset_mocks
MOCK_GET_ID_BODY='{"id": 379056264, "tag_name": "v1.1.0", "draft": true, "prerelease": false, "target_commitish": "0000000000000000000000000000000000000"}'
assert_case "identity refuses a target_commitish that does not match the expected sha" fail "expected 'a7f43b43004a4ba475a66a5e9fb1fe9c8f9a9961'" \
  -- check_release_recovery_identity "379056264" "v1.1.0" "a7f43b43004a4ba475a66a5e9fb1fe9c8f9a9961"

reset_mocks
MOCK_GET_ID_BODY='{"id": 999, "tag_name": "v1.1.0", "draft": true, "prerelease": false}'
assert_case "identity refuses a response whose id does not match the requested release id" fail "did not report the exact expected numeric id" \
  -- check_release_recovery_identity "379056264" "v1.1.0" "a7f43b43004a4ba475a66a5e9fb1fe9c8f9a9961"

reset_mocks
assert_case "identity refuses a non-numeric release id argument" fail "is not a positive integer" \
  -- check_release_recovery_identity "not-a-number" "v1.1.0" "a7f43b43004a4ba475a66a5e9fb1fe9c8f9a9961"

# --- source-run: check_source_run_provenance --------------------------------

reset_mocks
assert_case "source-run: exact head_sha, correct job success, artifact present -> verified" pass "verified: workflow/event/head match" \
  -- check_source_run_provenance "33263907316" "v1.1.0" "a7f43b43004a4ba475a66a5e9fb1fe9c8f9a9961" "release-bundle-1.1.0"

reset_mocks
MOCK_RUN_BODY='{"path": ".github/workflows/release.yml", "event": "push", "head_branch": "v1.1.0", "head_sha": "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef"}'
assert_case "REC-010: the source run's head_sha differs from the expected commit -> fail" fail "has head_sha 'deadbeefdeadbeefdeadbeefdeadbeefdeadbeef', expected" \
  -- check_source_run_provenance "33263907316" "v1.1.0" "a7f43b43004a4ba475a66a5e9fb1fe9c8f9a9961" "release-bundle-1.1.0"

reset_mocks
MOCK_JOBS_BODY='{"total_count": 1, "jobs": [{"name": "Verify release candidate", "conclusion": "failure"}]}'
assert_case "REC-011: the 'Verify release candidate' job did not conclude success -> fail" fail "concluded 'failure', not 'success'" \
  -- check_source_run_provenance "33263907316" "v1.1.0" "a7f43b43004a4ba475a66a5e9fb1fe9c8f9a9961" "release-bundle-1.1.0"

reset_mocks
MOCK_JOBS_BODY='{"total_count": 1, "jobs": [{"name": "Some other job", "conclusion": "success"}]}'
assert_case "source-run: no job named 'Verify release candidate' at all -> fail" fail "has no job named 'Verify release candidate'" \
  -- check_source_run_provenance "33263907316" "v1.1.0" "a7f43b43004a4ba475a66a5e9fb1fe9c8f9a9961" "release-bundle-1.1.0"

reset_mocks
MOCK_ARTIFACTS_BODY='{"total_count": 0, "artifacts": []}'
assert_case "REC-012: the expected artifact is absent -> fail" fail "has no artifact named 'release-bundle-1.1.0'" \
  -- check_source_run_provenance "33263907316" "v1.1.0" "a7f43b43004a4ba475a66a5e9fb1fe9c8f9a9961" "release-bundle-1.1.0"

reset_mocks
MOCK_ARTIFACTS_BODY='{"total_count": 1, "artifacts": [{"name": "release-bundle-1.1.0", "expired": true, "digest": "sha256:abc123"}]}'
assert_case "REC-012: the expected artifact is expired -> fail" fail "is expired or its expiry could not be confirmed false" \
  -- check_source_run_provenance "33263907316" "v1.1.0" "a7f43b43004a4ba475a66a5e9fb1fe9c8f9a9961" "release-bundle-1.1.0"

reset_mocks
assert_case "REC-013: the artifact digest does not match the expected digest -> fail" fail "has digest 'sha256:abc123', expected 'sha256:doesnotmatch'" \
  -- check_source_run_provenance "33263907316" "v1.1.0" "a7f43b43004a4ba475a66a5e9fb1fe9c8f9a9961" "release-bundle-1.1.0" "sha256:doesnotmatch"

reset_mocks
assert_case "source-run: the given digest matches exactly -> verified" pass "verified: workflow/event/head match" \
  -- check_source_run_provenance "33263907316" "v1.1.0" "a7f43b43004a4ba475a66a5e9fb1fe9c8f9a9961" "release-bundle-1.1.0" "sha256:abc123"

reset_mocks
MOCK_RUN_BODY='{"path": ".github/workflows/ci.yml", "event": "push", "head_branch": "v1.1.0", "head_sha": "a7f43b43004a4ba475a66a5e9fb1fe9c8f9a9961"}'
assert_case "source-run: the run belongs to a different workflow entirely -> fail" fail "belongs to workflow '.github/workflows/ci.yml'" \
  -- check_source_run_provenance "33263907316" "v1.1.0" "a7f43b43004a4ba475a66a5e9fb1fe9c8f9a9961" "release-bundle-1.1.0"

reset_mocks
MOCK_RUN_BODY='{"path": ".github/workflows/release.yml", "event": "workflow_dispatch", "head_branch": "v1.1.0", "head_sha": "a7f43b43004a4ba475a66a5e9fb1fe9c8f9a9961"}'
assert_case "source-run: the run was not triggered by a tag push -> fail" fail "triggered by event 'workflow_dispatch', expected 'push'" \
  -- check_source_run_provenance "33263907316" "v1.1.0" "a7f43b43004a4ba475a66a5e9fb1fe9c8f9a9961" "release-bundle-1.1.0"

reset_mocks
MOCK_TRANSPORT_FAIL_PATTERN="/actions/runs/"
assert_case "source-run: a network/transport error fails closed" fail "Unable to reach the GitHub API to verify source run" \
  -- check_source_run_provenance "33263907316" "v1.1.0" "a7f43b43004a4ba475a66a5e9fb1fe9c8f9a9961" "release-bundle-1.1.0"

unset -f curl

echo
echo "$cases_run assertion case(s) run, $failures failure(s)."
if [[ "$failures" -ne 0 ]]; then
  exit 1
fi
