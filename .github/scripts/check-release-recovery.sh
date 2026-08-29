#!/usr/bin/env bash
# Verifies that an EXISTING GitHub Release is exactly the release a
# manually triggered recovery run was told to resume -- never "find a
# release matching this tag and trust it". This is the recovery-only
# counterpart to check-release-not-published.sh, and deliberately does
# not touch or extend that script's semantics: the normal guard keeps
# meaning "no release may exist yet"; this one means "the one release
# that does exist must be proven, by exact id, to be the one recovery
# was explicitly told to resume".
#
# Every check here is ID-addressed: it fetches exactly the release the
# caller named (GET /repos/{owner}/{repo}/releases/{release_id}) and
# never lists or searches. A release found only because its tag happens
# to match is never adopted -- an additional by-tag cross-check refuses
# if some *other* release id is the one the by-tag endpoint reports for
# the same tag, since that would mean two releases contradict each other
# over ownership of the tag and neither may be silently preferred.
#
# This script only reads. It never creates, modifies, or deletes a
# release, an asset, or a tag; it is a precondition check for the
# separate recovery workflow to consult before it performs any write.
#
# A second, independent check lives in this same script: proving the
# *source workflow run* recovery was told to resume from is itself
# trustworthy provenance for the artifact recovery is about to publish
# (Part 5.C). This is a distinct concern from release identity above --
# one release id can only ever ID-address one release, but the source
# run additionally needs its head commit, its "Verify release
# candidate" job's own conclusion, and its uploaded artifact all proven,
# never assumed from the run's own overall (possibly unrelated-job-
# caused) conclusion.
#
# Usage:
#   check-release-recovery.sh <release_id> <tag> <expected_sha>
#   check-release-recovery.sh source-run <source_run_id> <tag> <expected_sha> <expected_artifact_name> [<expected_artifact_digest>]
# Required env: GITHUB_REPOSITORY (owner/repo), GITHUB_TOKEN
# Optional env: GITHUB_API_URL (defaults to https://api.github.com)
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./check-release-not-published.sh
source "$script_dir/check-release-not-published.sh"

# The exact workflow file and job name a source run must carry to be
# trusted as release provenance. Generic across every recovery -- never
# a specific tag or version -- so this script never hardcodes a release
# like v1.1.0 into its logic.
RELEASE_WORKFLOW_PATH=".github/workflows/release.yml"
RELEASE_VERIFY_JOB_NAME="Verify release candidate"

# check_release_recovery_identity <release_id> <tag> <expected_sha>
# Returns 0 only when every one of the following is proven; explains
# every failure on stderr and returns non-zero otherwise:
#   - GET /releases/{release_id} returns HTTP 200 with a JSON object;
#   - its id is the exact expected positive integer (defensive: the URL
#     already addressed this id, but a lying/malformed response body
#     must never be trusted regardless);
#   - its tag_name is exactly the expected tag (string, exact match);
#   - its draft field is present and strictly boolean -- recovery may
#     target either a draft (resume upload/finalize) or an already
#     public release (resume only what is missing), so either value is
#     accepted here as long as it is an unambiguous real boolean, never
#     a missing/non-boolean value silently treated as one state or the
#     other;
#   - its prerelease field, if present, is strictly boolean (type-only
#     check: no specific expected value is supplied by this script's
#     3-argument contract);
#   - its target_commitish, when reported as a non-empty string, equals
#     expected_sha exactly -- "when verifiable" means a non-string or
#     absent value is not itself treated as failure, since every other
#     identity field has already been proven exact;
#   - the by-tag endpoint (GET /releases/tags/{tag}), which only ever
#     reports a *published* release, does not contradict this id: if it
#     returns 200, its id must equal release_id exactly; a 404 (no
#     published release for that tag -- expected when the target is
#     still a draft, or simply absent) is not itself a failure; any
#     other status is indeterminate and fails closed.
check_release_recovery_identity() {
  local release_id="$1" tag="$2" expected_sha="$3"

  if ! [[ "$release_id" =~ ^[1-9][0-9]*$ ]]; then
    echo "Refusing to proceed: release id '$release_id' is not a positive integer." >&2
    return 1
  fi

  local body_file status
  body_file="$(mktemp)"
  if ! status="$(github_api_get "/releases/${release_id}" "$body_file")"; then
    echo "Unable to reach the GitHub API to verify release $release_id for recovery (tag '$tag')." >&2
    rm -f "$body_file"
    return 1
  fi

  if [[ "$status" != "200" ]]; then
    echo "Refusing to proceed: GET /releases/$release_id returned HTTP $status, not 200. This release id does not exist or is not reachable -- recovery cannot resume a release it cannot exactly identify." >&2
    rm -f "$body_file"
    return 1
  fi

  local response_type
  if ! response_type="$(jq -r 'type' "$body_file" 2>/dev/null)" || [[ "$response_type" != "object" ]]; then
    echo "Response for release $release_id could not be parsed as a JSON object." >&2
    rm -f "$body_file"
    return 1
  fi

  local id_valid
  id_valid="$(jq -r --argjson expected "$release_id" \
    '(.id | type) == "number" and (.id | floor) == .id and (.id > 0) and (.id == $expected)' \
    "$body_file" 2>/dev/null || echo "false")"
  if [[ "$id_valid" != "true" ]]; then
    echo "Response for release $release_id did not report the exact expected numeric id." >&2
    rm -f "$body_file"
    return 1
  fi

  local response_tag
  response_tag="$(jq -r 'if (.tag_name | type) == "string" then .tag_name else "" end' "$body_file" 2>/dev/null)"
  if [[ "$response_tag" != "$tag" ]]; then
    echo "Release $release_id has tag '$response_tag', expected '$tag'. Refusing to adopt a release id that does not exactly match the tag recovery was told to resume." >&2
    rm -f "$body_file"
    return 1
  fi

  local draft_type
  draft_type="$(jq -r '.draft | type' "$body_file" 2>/dev/null || echo "null")"
  if [[ "$draft_type" != "boolean" ]]; then
    echo "Release $release_id (tag '$tag') did not report a well-formed boolean draft state (got type: $draft_type)." >&2
    rm -f "$body_file"
    return 1
  fi

  local prerelease_type
  prerelease_type="$(jq -r '.prerelease | type' "$body_file" 2>/dev/null || echo "null")"
  if [[ "$prerelease_type" != "boolean" ]]; then
    echo "Release $release_id (tag '$tag') did not report a well-formed boolean prerelease state (got type: $prerelease_type)." >&2
    rm -f "$body_file"
    return 1
  fi

  local response_target_type
  response_target_type="$(jq -r '.target_commitish | type' "$body_file" 2>/dev/null || echo "null")"
  if [[ "$response_target_type" == "string" ]]; then
    local response_target
    response_target="$(jq -r '.target_commitish' "$body_file" 2>/dev/null)"
    if [[ -n "$response_target" && "$response_target" != "$expected_sha" ]]; then
      echo "Release $release_id (tag '$tag') has target_commitish '$response_target', expected '$expected_sha'." >&2
      rm -f "$body_file"
      return 1
    fi
  fi

  local draft_state
  draft_state="$(jq -r '.draft' "$body_file" 2>/dev/null)"
  rm -f "$body_file"

  # Cross-check against the by-tag endpoint: it only ever reports a
  # published release, so a 404 here is expected and harmless both when
  # the target is still a draft and when no published release exists at
  # all. What must never happen is a *different* release id being
  # reported as the published owner of this same tag -- that would mean
  # two releases contradict each other and neither may be silently
  # preferred over the id recovery was explicitly told to resume.
  local by_tag_body by_tag_status
  by_tag_body="$(mktemp)"
  if ! by_tag_status="$(fetch_release_by_tag "$tag" "$by_tag_body")"; then
    echo "Unable to reach the GitHub API to cross-check tag '$tag' against release $release_id." >&2
    rm -f "$by_tag_body"
    return 1
  fi

  case "$by_tag_status" in
    404)
      : # No published release owns this tag; not a contradiction.
      ;;
    200)
      local by_tag_id_valid
      by_tag_id_valid="$(jq -r --argjson expected "$release_id" \
        '(.id | type) == "number" and (.id == $expected)' \
        "$by_tag_body" 2>/dev/null || echo "false")"
      if [[ "$by_tag_id_valid" != "true" ]]; then
        local by_tag_id
        by_tag_id="$(jq -r 'if (.id | type) == "number" then (.id | tostring) else "unknown" end' "$by_tag_body" 2>/dev/null)"
        echo "Refusing to proceed: the by-tag lookup for '$tag' reports release $by_tag_id, but recovery was told to resume release $release_id. A different release owns this tag -- recovery must never adopt a release just because a tag matches." >&2
        rm -f "$by_tag_body"
        return 1
      fi
      ;;
    *)
      echo "Refusing to proceed: unexpected response while cross-checking tag '$tag' by-tag lookup against release $release_id: HTTP $by_tag_status." >&2
      rm -f "$by_tag_body"
      return 1
      ;;
  esac
  rm -f "$by_tag_body"

  echo "Release $release_id (tag '$tag', draft=$draft_state) verified as the exact expected release for recovery."
  return 0
}

# check_source_run_provenance <source_run_id> <tag> <expected_sha> <expected_artifact_name> [<expected_artifact_digest>]
# Proves the workflow run recovery was told to resume from is
# trustworthy provenance for the release artifact it already built:
#   - the run exists, is the expected Release workflow
#     (RELEASE_WORKFLOW_PATH), was triggered by a tag push, and its
#     head_branch/head_sha match the expected tag/commit exactly;
#   - it contains a job named exactly RELEASE_VERIFY_JOB_NAME whose own
#     conclusion is "success" -- never trusted on any other conclusion,
#     and never inferred from the run's own overall conclusion, which
#     can be "failure" even when this specific job succeeded (a later,
#     unrelated job failing does not retroactively invalidate a
#     verification that already completed);
#   - it has exactly one artifact named expected_artifact_name, not
#     expired, and -- if expected_artifact_digest is supplied --
#     reporting exactly that digest.
# Never rebuilds or re-verifies anything itself; it only proves the
# already-completed verification is the one recovery may rely on.
check_source_run_provenance() {
  local source_run_id="$1" tag="$2" expected_sha="$3" expected_artifact_name="$4" expected_artifact_digest="${5:-}"

  if ! [[ "$source_run_id" =~ ^[1-9][0-9]*$ ]]; then
    echo "Refusing to proceed: source run id '$source_run_id' is not a positive integer." >&2
    return 1
  fi

  local run_body run_status
  run_body="$(mktemp)"
  if ! run_status="$(github_api_get "/actions/runs/${source_run_id}" "$run_body")"; then
    echo "Unable to reach the GitHub API to verify source run $source_run_id." >&2
    rm -f "$run_body"
    return 1
  fi

  if [[ "$run_status" != "200" ]]; then
    echo "Refusing to proceed: GET /actions/runs/$source_run_id returned HTTP $run_status, not 200." >&2
    rm -f "$run_body"
    return 1
  fi

  local run_type
  if ! run_type="$(jq -r 'type' "$run_body" 2>/dev/null)" || [[ "$run_type" != "object" ]]; then
    echo "Response for source run $source_run_id could not be parsed as a JSON object." >&2
    rm -f "$run_body"
    return 1
  fi

  local run_path run_event run_head_branch run_head_sha
  run_path="$(jq -r 'if (.path | type) == "string" then .path else "" end' "$run_body" 2>/dev/null)"
  run_event="$(jq -r 'if (.event | type) == "string" then .event else "" end' "$run_body" 2>/dev/null)"
  run_head_branch="$(jq -r 'if (.head_branch | type) == "string" then .head_branch else "" end' "$run_body" 2>/dev/null)"
  run_head_sha="$(jq -r 'if (.head_sha | type) == "string" then .head_sha else "" end' "$run_body" 2>/dev/null)"
  rm -f "$run_body"

  if [[ "$run_path" != "$RELEASE_WORKFLOW_PATH" ]]; then
    echo "Refusing to proceed: source run $source_run_id belongs to workflow '$run_path', expected '$RELEASE_WORKFLOW_PATH'." >&2
    return 1
  fi

  if [[ "$run_event" != "push" ]]; then
    echo "Refusing to proceed: source run $source_run_id was triggered by event '$run_event', expected 'push' (a tag push)." >&2
    return 1
  fi

  if [[ "$run_head_branch" != "$tag" ]]; then
    echo "Refusing to proceed: source run $source_run_id has head_branch '$run_head_branch', expected tag '$tag'." >&2
    return 1
  fi

  if [[ "$run_head_sha" != "$expected_sha" ]]; then
    echo "Refusing to proceed: source run $source_run_id has head_sha '$run_head_sha', expected '$expected_sha'." >&2
    return 1
  fi

  local jobs_body jobs_status
  jobs_body="$(mktemp)"
  if ! jobs_status="$(github_api_get "/actions/runs/${source_run_id}/jobs?per_page=100" "$jobs_body")"; then
    echo "Unable to reach the GitHub API to list jobs for source run $source_run_id." >&2
    rm -f "$jobs_body"
    return 1
  fi

  if [[ "$jobs_status" != "200" ]]; then
    echo "Refusing to proceed: GET /actions/runs/$source_run_id/jobs returned HTTP $jobs_status, not 200." >&2
    rm -f "$jobs_body"
    return 1
  fi

  local jobs_type
  if ! jobs_type="$(jq -r 'type' "$jobs_body" 2>/dev/null)" || [[ "$jobs_type" != "object" ]]; then
    echo "Jobs response for source run $source_run_id could not be parsed as a JSON object." >&2
    rm -f "$jobs_body"
    return 1
  fi

  local verify_matches
  verify_matches="$(jq -r --arg name "$RELEASE_VERIFY_JOB_NAME" \
    'if (.jobs | type) == "array" then [.jobs[] | select(.name == $name) | .conclusion] | length else 0 end' \
    "$jobs_body" 2>/dev/null || echo "0")"

  if [[ "$verify_matches" -eq 0 ]]; then
    echo "Refusing to proceed: source run $source_run_id has no job named '$RELEASE_VERIFY_JOB_NAME'." >&2
    rm -f "$jobs_body"
    return 1
  fi

  local verify_conclusion
  verify_conclusion="$(jq -r --arg name "$RELEASE_VERIFY_JOB_NAME" \
    '[.jobs[] | select(.name == $name) | .conclusion][0]' "$jobs_body" 2>/dev/null)"
  rm -f "$jobs_body"

  if [[ "$verify_conclusion" != "success" ]]; then
    echo "Refusing to proceed: source run $source_run_id's '$RELEASE_VERIFY_JOB_NAME' job concluded '$verify_conclusion', not 'success'. A failed, cancelled, or skipped verification must never be trusted as provenance." >&2
    return 1
  fi

  local artifacts_body artifacts_status
  artifacts_body="$(mktemp)"
  if ! artifacts_status="$(github_api_get "/actions/runs/${source_run_id}/artifacts?per_page=100" "$artifacts_body")"; then
    echo "Unable to reach the GitHub API to list artifacts for source run $source_run_id." >&2
    rm -f "$artifacts_body"
    return 1
  fi

  if [[ "$artifacts_status" != "200" ]]; then
    echo "Refusing to proceed: GET /actions/runs/$source_run_id/artifacts returned HTTP $artifacts_status, not 200." >&2
    rm -f "$artifacts_body"
    return 1
  fi

  local artifacts_type
  if ! artifacts_type="$(jq -r 'type' "$artifacts_body" 2>/dev/null)" || [[ "$artifacts_type" != "object" ]]; then
    echo "Artifacts response for source run $source_run_id could not be parsed as a JSON object." >&2
    rm -f "$artifacts_body"
    return 1
  fi

  local matching_artifacts_count
  matching_artifacts_count="$(jq -r --arg name "$expected_artifact_name" \
    'if (.artifacts | type) == "array" then [.artifacts[] | select(.name == $name)] | length else 0 end' \
    "$artifacts_body" 2>/dev/null || echo "0")"

  if [[ "$matching_artifacts_count" -eq 0 ]]; then
    echo "Refusing to proceed: source run $source_run_id has no artifact named '$expected_artifact_name'." >&2
    rm -f "$artifacts_body"
    return 1
  fi

  if [[ "$matching_artifacts_count" -gt 1 ]]; then
    echo "Refusing to proceed: source run $source_run_id has $matching_artifacts_count artifacts named '$expected_artifact_name' (expected exactly 1)." >&2
    rm -f "$artifacts_body"
    return 1
  fi

  local artifact_expired
  artifact_expired="$(jq -r --arg name "$expected_artifact_name" \
    '[.artifacts[] | select(.name == $name) | .expired][0]' "$artifacts_body" 2>/dev/null)"
  if [[ "$artifact_expired" != "false" ]]; then
    echo "Refusing to proceed: artifact '$expected_artifact_name' on source run $source_run_id is expired or its expiry could not be confirmed false (expired=$artifact_expired)." >&2
    rm -f "$artifacts_body"
    return 1
  fi

  if [[ -n "$expected_artifact_digest" ]]; then
    local artifact_digest
    artifact_digest="$(jq -r --arg name "$expected_artifact_name" \
      '[.artifacts[] | select(.name == $name) | (if (.digest | type) == "string" then .digest else "" end)][0]' \
      "$artifacts_body" 2>/dev/null)"
    if [[ "$artifact_digest" != "$expected_artifact_digest" ]]; then
      echo "Refusing to proceed: artifact '$expected_artifact_name' on source run $source_run_id has digest '$artifact_digest', expected '$expected_artifact_digest'." >&2
      rm -f "$artifacts_body"
      return 1
    fi
  fi

  rm -f "$artifacts_body"

  echo "Source run $source_run_id verified: workflow/event/head match, '$RELEASE_VERIFY_JOB_NAME' succeeded, artifact '$expected_artifact_name' present and not expired."
  return 0
}

main() {
  if [[ $# -ge 1 && "$1" == "source-run" ]]; then
    shift
    if [[ $# -ne 4 && $# -ne 5 ]]; then
      echo "Usage: $0 source-run <source_run_id> <tag> <expected_sha> <expected_artifact_name> [<expected_artifact_digest>]" >&2
      exit 1
    fi
    : "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY must be set (owner/repo)}"
    : "${GITHUB_TOKEN:?GITHUB_TOKEN must be set}"
    check_source_run_provenance "$@"
    return
  fi

  if [[ $# -ne 3 ]]; then
    echo "Usage: $0 <release_id> <tag> <expected_sha>" >&2
    echo "   or: $0 source-run <source_run_id> <tag> <expected_sha> <expected_artifact_name> [<expected_artifact_digest>]" >&2
    exit 1
  fi

  local release_id="$1" tag="$2" expected_sha="$3"

  : "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY must be set (owner/repo)}"
  : "${GITHUB_TOKEN:?GITHUB_TOKEN must be set}"

  check_release_recovery_identity "$release_id" "$tag" "$expected_sha"
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  main "$@"
fi
