#!/usr/bin/env bash
# Publishes a GitHub Release for an already-verified tag through three
# explicit, ID-addressed steps instead of a single find-or-create/update
# call, so a concurrently created release can never be silently adopted,
# merged into, or overwritten:
#
#   create   -- refuses if any release (draft or published) already
#               exists for the tag (reusing check-release-not-published.sh's
#               detection, which also scans the full releases list since
#               drafts are invisible to the by-tag endpoint), creates a
#               new draft release for the tag, then re-scans the releases
#               list to confirm this run's draft is the *only* release
#               for that tag -- refusing if a concurrent creation is
#               detected. Prints the created release's numeric id.
#   upload   -- uploads one asset file to a release by its exact numeric
#               id. Never searches by tag. Refuses if the file is missing
#               or empty, or if the upload does not return success.
#   finalize -- re-fetches the release by its exact numeric id, refuses
#               unless it is still a draft (a rerun must never silently
#               "finish" a release that was already published, by this
#               workflow or otherwise), then flips it to a published,
#               non-draft release.
#
# GitHub's REST API has no atomic "create only if absent" primitive for
# releases, so the create step cannot offer a true compare-and-swap
# guarantee -- it minimizes and detects the race instead of assuming it
# away: every step here either succeeds with the exact release it created
# or refuses outright. None of these subcommands ever deletes, replaces,
# or moves a tag or a release; recovering from a genuine conflict is a
# deliberate human action.
#
# Usage:
#   release-draft.sh create   <tag> <target_commitish> <prerelease:true|false>
#   release-draft.sh notes    <tag> <target_commitish>
#   release-draft.sh upload   <release_id> <asset_path> [<asset_path> ...]
#   release-draft.sh finalize <release_id> <tag>
#
# Required env: GITHUB_REPOSITORY (owner/repo), GITHUB_TOKEN
# Optional env: GITHUB_API_URL (defaults to https://api.github.com),
#               GITHUB_UPLOAD_URL (defaults to https://uploads.github.com)
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./check-release-not-published.sh
source "$script_dir/check-release-not-published.sh"

# github_api_call <method> <url> <response_body_file> [request_body_file]
# Generic authenticated JSON request against an arbitrary absolute URL
# (used for both the REST API and the separate uploads host). Prints the
# HTTP status code on stdout. Returns non-zero only on a transport-level
# failure, mirroring github_api_get's contract.
github_api_call() {
  local method="$1" url="$2" body_file="$3" request_body_file="${4:-}"
  local -a curl_args=(
    --silent --show-error --location --max-time 60
    --request "$method"
    --output "$body_file"
    --write-out '%{http_code}'
    --header "Authorization: Bearer ${GITHUB_TOKEN}"
    --header "Accept: application/vnd.github+json"
    --header "X-GitHub-Api-Version: 2022-11-28"
  )
  if [[ -n "$request_body_file" ]]; then
    curl_args+=(--header "Content-Type: application/json" --data "@${request_body_file}")
  fi
  curl "${curl_args[@]}" "$url"
}

api_base() {
  echo "${GITHUB_API_URL:-https://api.github.com}"
}

upload_base() {
  echo "${GITHUB_UPLOAD_URL:-https://uploads.github.com}"
}

# cmd_notes <tag> <target_commitish>
# Prints auto-generated release notes body text on stdout (may be empty).
# Failing to generate notes is not itself fatal to the release -- an
# empty body is a valid release -- but any non-2xx/non-network-failure
# response is still surfaced so the caller can decide.
cmd_notes() {
  local tag="$1" target_commitish="$2"
  local request_body body_file status

  request_body="$(mktemp)"
  jq -n --arg tag "$tag" --arg target "$target_commitish" \
    '{tag_name: $tag, target_commitish: $target}' > "$request_body"

  body_file="$(mktemp)"
  if ! status="$(github_api_call POST "$(api_base)/repos/${GITHUB_REPOSITORY}/releases/generate-notes" "$body_file" "$request_body")"; then
    echo "Unable to reach the GitHub API to generate release notes for tag '$tag'." >&2
    rm -f "$request_body" "$body_file"
    return 1
  fi
  rm -f "$request_body"

  if [[ "$status" != "200" ]]; then
    echo "Unexpected response while generating release notes for tag '$tag': HTTP $status." >&2
    rm -f "$body_file"
    return 1
  fi

  if ! jq -r '.body // ""' "$body_file" 2>/dev/null; then
    echo "Generated release notes response for tag '$tag' could not be parsed as JSON." >&2
    rm -f "$body_file"
    return 1
  fi
  rm -f "$body_file"
}

# validate_created_release_response <tag> <body_file>
# Strictly validates the create POST's response body against the exact
# schema/identity a freshly created draft must have, and -- only if every
# check passes -- prints the validated release id as the sole line of
# stdout. Prints nothing to stdout on failure; every check failure is
# explained on stderr. A malformed, wrong-shaped, or semantically wrong
# response (non-object body, non-numeric/non-positive/non-integer id, a
# different tag, a draft flag that is missing, non-boolean, or false)
# must never be treated as "no usable id" being merely absent -- it is a
# distinct, fail-closed refusal for each condition.
validate_created_release_response() {
  local tag="$1" body_file="$2"

  local response_type
  if ! response_type="$(jq -r 'type' "$body_file" 2>/dev/null)"; then
    echo "Draft release creation response for tag '$tag' could not be parsed as JSON." >&2
    return 1
  fi

  if [[ "$response_type" != "object" ]]; then
    echo "Draft release creation response for tag '$tag' was valid JSON but not a JSON object (top-level type: $response_type)." >&2
    return 1
  fi

  local id_valid
  id_valid="$(jq -r '(.id | type) == "number" and (.id | floor) == .id and (.id > 0)' "$body_file" 2>/dev/null || echo "false")"
  if [[ "$id_valid" != "true" ]]; then
    echo "Draft release creation response for tag '$tag' did not contain a valid positive integer id." >&2
    return 1
  fi

  local response_tag
  response_tag="$(jq -r 'if (.tag_name | type) == "string" then .tag_name else "" end' "$body_file" 2>/dev/null)"
  if [[ "$response_tag" != "$tag" ]]; then
    echo "Draft release creation returned tag '$response_tag', expected '$tag'." >&2
    return 1
  fi

  local draft_valid
  draft_valid="$(jq -r '(.draft | type) == "boolean" and (.draft == true)' "$body_file" 2>/dev/null || echo "false")"
  if [[ "$draft_valid" != "true" ]]; then
    echo "Draft release creation response for tag '$tag' did not report draft == true." >&2
    return 1
  fi

  jq -r '.id' "$body_file"
}

# cmd_create <tag> <target_commitish> <prerelease>
# Prints the created draft release's numeric id on stdout as the sole
# line of output. Every diagnostic goes to stderr.
cmd_create() {
  local tag="$1" target_commitish="$2" prerelease="$3"

  if [[ "$prerelease" != "true" && "$prerelease" != "false" ]]; then
    echo "cmd_create: prerelease flag must be 'true' or 'false', got '$prerelease'." >&2
    return 1
  fi

  if ! check_release_absent "$tag" >&2; then
    echo "Refusing to create a release for tag '$tag': a release already exists or its state could not be determined." >&2
    return 1
  fi

  local notes
  if ! notes="$(cmd_notes "$tag" "$target_commitish")"; then
    echo "Refusing to create a release for tag '$tag': could not generate release notes." >&2
    return 1
  fi

  local request_body body_file status
  request_body="$(mktemp)"
  jq -n \
    --arg tag "$tag" \
    --arg target "$target_commitish" \
    --arg body "$notes" \
    --argjson prerelease "$prerelease" \
    '{tag_name: $tag, target_commitish: $target, draft: true, prerelease: $prerelease, body: $body, generate_release_notes: false}' \
    > "$request_body"

  body_file="$(mktemp)"
  if ! status="$(github_api_call POST "$(api_base)/repos/${GITHUB_REPOSITORY}/releases" "$body_file" "$request_body")"; then
    echo "Unable to reach the GitHub API to create the draft release for tag '$tag'." >&2
    rm -f "$request_body" "$body_file"
    return 1
  fi
  rm -f "$request_body"

  if [[ "$status" != "201" ]]; then
    echo "Failed to create the draft release for tag '$tag': HTTP $status." >&2
    cat "$body_file" >&2
    rm -f "$body_file"
    return 1
  fi

  local release_id
  if ! release_id="$(validate_created_release_response "$tag" "$body_file")"; then
    echo "Refusing to proceed with an unverified draft release creation response for tag '$tag'." >&2
    rm -f "$body_file"
    return 1
  fi
  rm -f "$body_file"

  # GitHub offers no atomic "create only if absent" call for releases.
  # Immediately re-scan the releases list: if any other release also
  # claims this tag now, a concurrent creation raced us and this run must
  # not proceed to touch anything further -- including the draft it just
  # created, which is left untouched for a human to reconcile. Proving
  # exactly one release matches the tag is necessary but not sufficient:
  # the workflow must also prove that the *one* matching release is the
  # exact release this run just created (same id), still reports the
  # exact requested tag, and is still a draft -- otherwise a same-tag
  # release owned by someone else could be silently mistaken for this
  # run's own draft before assets are uploaded to it.
  local matches_file match_count
  matches_file="$(mktemp)"
  if ! scan_releases_for_tag "$tag" "$matches_file"; then
    echo "Created draft release $release_id for tag '$tag', but the post-create uniqueness scan could not be completed." >&2
    echo "Refusing to proceed: the created draft is left as-is for manual review." >&2
    rm -f "$matches_file"
    return 1
  fi

  match_count="$(wc -l < "$matches_file" | tr -d '[:space:]')"

  if [[ "$match_count" -ne 1 ]]; then
    echo "Created draft release $release_id for tag '$tag', but $match_count releases now match that tag (expected exactly 1)." >&2
    echo "This indicates a concurrent release creation. Refusing to proceed; the created draft is left as-is for manual review." >&2
    rm -f "$matches_file"
    return 1
  fi

  local match_id match_tag match_draft
  match_id="$(jq -r '.id' "$matches_file")"
  match_tag="$(jq -r '.tag_name' "$matches_file")"
  match_draft="$(jq -r '.draft' "$matches_file")"
  rm -f "$matches_file"

  if [[ "$match_id" != "$release_id" ]]; then
    echo "Post-create scan found release $match_id for tag '$tag', but the create response returned release $release_id." >&2
    echo "Created release $release_id could not be proven as the unique post-create owner of tag '$tag'. Refusing to proceed; the created draft is left as-is for manual review." >&2
    return 1
  fi

  if [[ "$match_tag" != "$tag" ]]; then
    echo "Post-create scan match for release $release_id has tag '$match_tag', expected '$tag'." >&2
    echo "Created release $release_id could not be proven as the unique post-create owner of tag '$tag'. Refusing to proceed; the created draft is left as-is for manual review." >&2
    return 1
  fi

  if [[ "$match_draft" != "true" ]]; then
    echo "Post-create scan match for release $release_id is not a draft (draft=$match_draft)." >&2
    echo "Created release $release_id could not be proven as the unique post-create owner of tag '$tag'. Refusing to proceed; the created draft is left as-is for manual review." >&2
    return 1
  fi

  printf '%s\n' "$release_id"
}

# cmd_upload <release_id> <asset_path> [<asset_path> ...]
cmd_upload() {
  local release_id="$1"
  shift

  if [[ $# -eq 0 ]]; then
    echo "cmd_upload: at least one asset path is required." >&2
    return 1
  fi

  local asset_path
  for asset_path in "$@"; do
    if [[ ! -s "$asset_path" ]]; then
      echo "Refusing to upload: asset does not exist or is empty: $asset_path" >&2
      return 1
    fi
  done

  for asset_path in "$@"; do
    local asset_name body_file status
    asset_name="$(basename "$asset_path")"
    body_file="$(mktemp)"

    if ! status="$(
      curl \
        --silent --show-error --location --max-time 300 \
        --request POST \
        --output "$body_file" \
        --write-out '%{http_code}' \
        --header "Authorization: Bearer ${GITHUB_TOKEN}" \
        --header "Accept: application/vnd.github+json" \
        --header "X-GitHub-Api-Version: 2022-11-28" \
        --header "Content-Type: application/octet-stream" \
        --data-binary "@${asset_path}" \
        "$(upload_base)/repos/${GITHUB_REPOSITORY}/releases/${release_id}/assets?name=$(printf '%s' "$asset_name" | jq -sRr @uri)"
    )"; then
      echo "Unable to reach the GitHub uploads API to upload asset '$asset_name' to release $release_id." >&2
      rm -f "$body_file"
      return 1
    fi

    if [[ "$status" != "201" ]]; then
      echo "Failed to upload asset '$asset_name' to release $release_id: HTTP $status." >&2
      cat "$body_file" >&2
      rm -f "$body_file"
      return 1
    fi
    rm -f "$body_file"

    echo "Uploaded asset '$asset_name' to release $release_id."
  done
}

# cmd_finalize <release_id> <tag>
# Refuses unless the release identified by release_id is still a draft
# at the moment of finalization -- a rerun must never silently treat an
# already-published release (by this workflow or anyone else) as a fresh
# success.
cmd_finalize() {
  local release_id="$1" tag="$2"
  local body_file status

  body_file="$(mktemp)"
  if ! status="$(github_api_call GET "$(api_base)/repos/${GITHUB_REPOSITORY}/releases/${release_id}" "$body_file")"; then
    echo "Unable to reach the GitHub API to re-check release $release_id before finalizing it." >&2
    rm -f "$body_file"
    return 1
  fi

  if [[ "$status" != "200" ]]; then
    echo "Unexpected response while re-checking release $release_id before finalizing it: HTTP $status." >&2
    rm -f "$body_file"
    return 1
  fi

  local current_tag current_draft current_draft_type
  if ! current_tag="$(jq -r '.tag_name' "$body_file" 2>/dev/null)" || \
     ! current_draft_type="$(jq -r '.draft | type' "$body_file" 2>/dev/null)" || \
     ! current_draft="$(jq -r '.draft' "$body_file" 2>/dev/null)"; then
    echo "Response for release $release_id could not be parsed as JSON before finalizing." >&2
    rm -f "$body_file"
    return 1
  fi
  rm -f "$body_file"

  if [[ "$current_tag" != "$tag" ]]; then
    echo "Refusing to finalize release $release_id: it is tagged '$current_tag', expected '$tag'." >&2
    return 1
  fi

  # current_draft_type must be checked, not just current_draft's text: jq
  # -r strips quotes from a JSON *string* the same way it renders a JSON
  # boolean, so a malformed draft: "true" (string) response would
  # otherwise read identically to a real draft: true and be silently
  # accepted as proof the release is still a draft.
  if [[ "$current_draft_type" != "boolean" || "$current_draft" != "true" ]]; then
    echo "Refusing to finalize release $release_id: it is no longer a draft (draft=$current_draft)." >&2
    echo "A rerun must not silently re-treat an already-published release as a fresh success." >&2
    return 1
  fi

  local request_body
  request_body="$(mktemp)"
  jq -n '{draft: false}' > "$request_body"

  body_file="$(mktemp)"
  if ! status="$(github_api_call PATCH "$(api_base)/repos/${GITHUB_REPOSITORY}/releases/${release_id}" "$body_file" "$request_body")"; then
    echo "Unable to reach the GitHub API to finalize release $release_id." >&2
    rm -f "$request_body" "$body_file"
    return 1
  fi
  rm -f "$request_body"

  if [[ "$status" != "200" ]]; then
    echo "Failed to finalize release $release_id: HTTP $status." >&2
    cat "$body_file" >&2
    rm -f "$body_file"
    return 1
  fi
  rm -f "$body_file"

  echo "Release $release_id (tag '$tag') published."
}

main() {
  if [[ $# -lt 1 ]]; then
    echo "Usage: $0 <create|notes|upload|finalize> ..." >&2
    exit 1
  fi

  local subcommand="$1"
  shift

  : "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY must be set (owner/repo)}"
  : "${GITHUB_TOKEN:?GITHUB_TOKEN must be set}"

  case "$subcommand" in
    create)
      [[ $# -eq 3 ]] || { echo "Usage: $0 create <tag> <target_commitish> <prerelease:true|false>" >&2; exit 1; }
      cmd_create "$1" "$2" "$3"
      ;;
    notes)
      [[ $# -eq 2 ]] || { echo "Usage: $0 notes <tag> <target_commitish>" >&2; exit 1; }
      cmd_notes "$1" "$2"
      ;;
    upload)
      [[ $# -ge 2 ]] || { echo "Usage: $0 upload <release_id> <asset_path> [<asset_path> ...]" >&2; exit 1; }
      cmd_upload "$@"
      ;;
    finalize)
      [[ $# -eq 2 ]] || { echo "Usage: $0 finalize <release_id> <tag>" >&2; exit 1; }
      cmd_finalize "$1" "$2"
      ;;
    *)
      echo "Unknown subcommand: $subcommand" >&2
      exit 1
      ;;
  esac
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  main "$@"
fi
