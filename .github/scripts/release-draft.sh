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
#               new draft release for the tag (a single POST, never
#               retried), verifies it by its exact numeric id, then
#               bounds how long it waits for GitHub's separately
#               eventually-consistent releases list to show that draft
#               as the tag's only release -- list lag is never confused
#               with a concurrent creation; an actual ownership conflict
#               or multi-release state still fails immediately. Prints
#               the created release's numeric id.
#   upload   -- uploads one or more asset files to a release by its
#               exact numeric id. Never searches by tag. Refuses if a
#               file is missing or empty, if a *.sha256 file's content
#               does not match the file it is meant to checksum, or if
#               the upload does not return success. Idempotent: an
#               asset already present under the exact expected name is
#               never re-uploaded or silently overwritten -- it is
#               downloaded and hash-compared first, and only an exact
#               byte match is treated as "already done".
#   finalize -- re-fetches the release by its exact numeric id, refuses
#               unless it is still a draft (a rerun must never silently
#               "finish" a release that was already published, by this
#               workflow or otherwise), then flips it to a published,
#               non-draft release.
#   recovery-finalize -- the recovery-only counterpart to finalize: same
#               re-check and PATCH when the release is still a draft,
#               but treats an already-public release as an idempotent
#               success instead of a failure (recovery may be resuming a
#               release a human already published out-of-band). Never
#               used by the normal publish workflow.
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
#   release-draft.sh recovery-finalize <release_id> <tag>
#
# Required env: GITHUB_REPOSITORY (owner/repo), GITHUB_TOKEN
# Optional env: GITHUB_API_URL (defaults to https://api.github.com),
#               GITHUB_UPLOAD_URL (defaults to https://uploads.github.com)
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./check-release-not-published.sh
source "$script_dir/check-release-not-published.sh"

# Bounded, read-only polling defaults for the post-create visibility
# check in wait_for_unique_release_visibility. Configurable via env so
# tests can run deterministically without real delays; production
# defaults keep the total observation window to a few seconds.
RELEASE_VISIBILITY_MAX_ATTEMPTS="${RELEASE_VISIBILITY_MAX_ATTEMPTS:-5}"
RELEASE_VISIBILITY_POLL_DELAY_SECONDS="${RELEASE_VISIBILITY_POLL_DELAY_SECONDS:-1}"

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

# github_api_download_binary <url> <output_file>
# Downloads binary content (following redirects, e.g. to a signed
# storage URL) to output_file. Prints the HTTP status code on stdout.
# Returns non-zero only on a transport-level failure. Used exclusively
# to fetch an *already-existing* release asset's bytes for a strong,
# content-level identity comparison before ever deciding to skip or
# refuse an upload -- never to fetch anything this run is about to
# create or modify.
github_api_download_binary() {
  local url="$1" output_file="$2"
  curl \
    --silent --show-error --location --max-time 300 \
    --request GET \
    --output "$output_file" \
    --write-out '%{http_code}' \
    --header "Authorization: Bearer ${GITHUB_TOKEN}" \
    --header "Accept: application/octet-stream" \
    --header "X-GitHub-Api-Version: 2022-11-28" \
    "$url"
}

# MAX_RELEASE_ASSET_LIST_PAGES caps pagination for list_release_assets,
# mirroring MAX_RELEASE_LIST_PAGES in check-release-not-published.sh.
MAX_RELEASE_ASSET_LIST_PAGES=50

# list_release_assets <release_id> <assets_file>
# Pages through the full list of assets already attached to a release
# and writes one compact JSON object {id, name} per line to
# assets_file. Returns 0 only if every page was fetched and parsed
# successfully (0 assets is a normal, successful outcome). Returns
# non-zero on any transport failure, unexpected HTTP status, unparseable
# page, non-array page shape, a malformed asset entry, or a page count
# exceeding the bounded cap -- an asset list that cannot be proven
# complete must never be treated as "no existing assets", since that
# would risk a silent overwrite.
list_release_assets() {
  local release_id="$1" assets_file="$2"
  local page_body page=1

  : > "$assets_file"
  page_body="$(mktemp)"

  while true; do
    if (( page > MAX_RELEASE_ASSET_LIST_PAGES )); then
      echo "Asset list for release $release_id did not terminate within ${MAX_RELEASE_ASSET_LIST_PAGES} pages." >&2
      rm -f "$page_body"
      return 1
    fi

    local page_status
    if ! page_status="$(github_api_call GET "$(api_base)/repos/${GITHUB_REPOSITORY}/releases/${release_id}/assets?per_page=100&page=${page}" "$page_body")"; then
      echo "Unable to reach the GitHub API while listing assets (page $page) for release $release_id." >&2
      rm -f "$page_body"
      return 1
    fi

    if [[ "$page_status" != "200" ]]; then
      echo "Unexpected response while listing assets (page $page) for release $release_id: HTTP $page_status." >&2
      rm -f "$page_body"
      return 1
    fi

    local page_type
    if ! page_type="$(jq -r 'type' "$page_body" 2>/dev/null)"; then
      echo "Asset list page $page for release $release_id could not be parsed as JSON." >&2
      rm -f "$page_body"
      return 1
    fi

    if [[ "$page_type" != "array" ]]; then
      echo "Asset list page $page for release $release_id returned valid JSON but not a JSON array (top-level type: $page_type)." >&2
      rm -f "$page_body"
      return 1
    fi

    local page_assets
    if ! page_assets="$(jq -c '
          if all(.[]; type == "object" and (.id | type) == "number" and ((.id | floor) == .id) and (.id > 0) and (.name | type) == "string")
          then .[] | {id, name}
          else error("malformed asset")
          end
        ' "$page_body" 2>/dev/null)"; then
      echo "Asset list page $page for release $release_id contains an asset with malformed identity fields (id/name)." >&2
      rm -f "$page_body"
      return 1
    fi

    if [[ -n "$page_assets" ]]; then
      printf '%s\n' "$page_assets" >> "$assets_file"
    fi

    local page_count
    if ! page_count="$(jq 'length' "$page_body" 2>/dev/null)"; then
      echo "Asset list page $page for release $release_id could not be parsed as JSON." >&2
      rm -f "$page_body"
      return 1
    fi

    if [[ "$page_count" -lt 100 ]]; then
      break
    fi

    page=$((page + 1))
  done

  rm -f "$page_body"
  return 0
}

# local_sha256 <path>
# Prints the lowercase hex sha256 digest of a local file.
local_sha256() {
  sha256sum "$1" | awk '{print $1}'
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

# verify_release_by_id <release_id> <tag> <target_commitish> <prerelease>
# Fetches the release directly by its exact numeric id (GET
# /releases/{release_id}) and strictly validates it against the exact
# identity a freshly created draft must have: HTTP 200, the exact
# requested id, the exact requested tag, draft == true, the exact
# requested prerelease flag, and target_commitish when the response
# reports it as a verifiable string -- every field checked by JSON type
# first so a malformed response (e.g. draft as the string "true" instead
# of the boolean true) can never be silently accepted as a real match.
# This function only reads; it never retries or repeats the create POST, and
# any failure here is immediate and fail-closed, never retried by this
# function itself.
verify_release_by_id() {
  local release_id="$1" tag="$2" target_commitish="$3" prerelease="$4"
  local body_file status

  body_file="$(mktemp)"
  if ! status="$(github_api_call GET "$(api_base)/repos/${GITHUB_REPOSITORY}/releases/${release_id}" "$body_file")"; then
    echo "Unable to reach the GitHub API to verify release $release_id by id for tag '$tag'." >&2
    rm -f "$body_file"
    return 1
  fi

  if [[ "$status" != "200" ]]; then
    echo "Refusing to proceed: GET by id for release $release_id (tag '$tag') returned HTTP $status, not 200." >&2
    cat "$body_file" >&2
    rm -f "$body_file"
    return 1
  fi

  local response_type
  if ! response_type="$(jq -r 'type' "$body_file" 2>/dev/null)" || [[ "$response_type" != "object" ]]; then
    echo "Response for release $release_id (tag '$tag') could not be parsed as a JSON object." >&2
    rm -f "$body_file"
    return 1
  fi

  local id_valid
  id_valid="$(jq -r --argjson expected "$release_id" \
    '(.id | type) == "number" and (.id | floor) == .id and (.id > 0) and (.id == $expected)' \
    "$body_file" 2>/dev/null || echo "false")"
  if [[ "$id_valid" != "true" ]]; then
    echo "Response for release $release_id (tag '$tag') did not report the exact expected numeric id." >&2
    rm -f "$body_file"
    return 1
  fi

  local response_tag
  response_tag="$(jq -r 'if (.tag_name | type) == "string" then .tag_name else "" end' "$body_file" 2>/dev/null)"
  if [[ "$response_tag" != "$tag" ]]; then
    echo "Release $release_id has tag '$response_tag', expected '$tag'." >&2
    rm -f "$body_file"
    return 1
  fi

  local draft_valid
  draft_valid="$(jq -r '(.draft | type) == "boolean" and (.draft == true)' "$body_file" 2>/dev/null || echo "false")"
  if [[ "$draft_valid" != "true" ]]; then
    echo "Release $release_id (tag '$tag') did not report draft == true (strict boolean check)." >&2
    rm -f "$body_file"
    return 1
  fi

  local prerelease_valid
  prerelease_valid="$(jq -r --argjson expected "$prerelease" \
    '(.prerelease | type) == "boolean" and (.prerelease == $expected)' \
    "$body_file" 2>/dev/null || echo "false")"
  if [[ "$prerelease_valid" != "true" ]]; then
    echo "Release $release_id (tag '$tag') did not report the expected prerelease flag ($prerelease)." >&2
    rm -f "$body_file"
    return 1
  fi

  # target_commitish is only checked when the response reports it as a
  # non-empty string -- "verifiable" here means directly comparable to
  # what was requested. A missing/non-string value is not itself treated
  # as a failure since every other identity field has already been
  # proven exact.
  local response_target_type
  response_target_type="$(jq -r '.target_commitish | type' "$body_file" 2>/dev/null || echo "null")"
  if [[ "$response_target_type" == "string" ]]; then
    local response_target
    response_target="$(jq -r '.target_commitish' "$body_file" 2>/dev/null)"
    if [[ -n "$response_target" && "$response_target" != "$target_commitish" ]]; then
      echo "Release $release_id (tag '$tag') has target_commitish '$response_target', expected '$target_commitish'." >&2
      rm -f "$body_file"
      return 1
    fi
  fi

  rm -f "$body_file"
  return 0
}

# wait_for_unique_release_visibility <tag> <release_id>
# GET-only, bounded, read-only polling of the full releases list to
# confirm the release this run just created (release_id, already proven
# to exist by verify_release_by_id) becomes the *only* release matching
# the tag in that list too. GitHub's releases-list endpoint is not
# guaranteed to be immediately consistent with a just-completed create,
# so a freshly created release can be directly addressable by id yet
# briefly absent from the list -- that is expected lag, not evidence of
# a concurrent creation, and must never be reported as such. This
# function never retries or repeats the create POST; it only re-reads
# the list, at most RELEASE_VISIBILITY_MAX_ATTEMPTS times with a short
# delay between reads, for a bounded total duration.
#
# Zero matches keeps polling until the attempt budget is exhausted, at
# which point it fails closed because uniqueness could not be proven --
# never silently assumed. More than one match, or exactly one match
# owned by a different release id/tag/draft state, fails immediately
# without further polling: no amount of additional waiting can resolve
# an ownership conflict or a contradictory multi-release state. A
# transport failure, unexpected HTTP status, or malformed page from the
# underlying scan also fails immediately (via scan_releases_for_tag).
wait_for_unique_release_visibility() {
  local tag="$1" release_id="$2"
  local attempt matches_file match_count

  for (( attempt = 1; attempt <= RELEASE_VISIBILITY_MAX_ATTEMPTS; attempt++ )); do
    matches_file="$(mktemp)"
    if ! scan_releases_for_tag "$tag" "$matches_file"; then
      echo "Created release $release_id for tag '$tag', but the post-create uniqueness scan could not be completed (attempt $attempt/$RELEASE_VISIBILITY_MAX_ATTEMPTS)." >&2
      echo "Refusing to proceed: the created release is left as-is for manual review." >&2
      rm -f "$matches_file"
      return 1
    fi

    match_count="$(wc -l < "$matches_file" | tr -d '[:space:]')"

    if [[ "$match_count" -eq 0 ]]; then
      rm -f "$matches_file"
      if [[ "$attempt" -lt "$RELEASE_VISIBILITY_MAX_ATTEMPTS" ]]; then
        echo "Created release $release_id for tag '$tag' is not yet visible in the releases list (attempt $attempt/$RELEASE_VISIBILITY_MAX_ATTEMPTS); this is expected list-endpoint lag, not evidence of concurrency. Retrying after a bounded delay." >&2
        sleep "$RELEASE_VISIBILITY_POLL_DELAY_SECONDS"
        continue
      fi
      echo "Created release $release_id is directly addressable by ID but did not become visible in the releases list within the bounded observation window ($RELEASE_VISIBILITY_MAX_ATTEMPTS attempts). Refusing to continue because uniqueness could not be proven." >&2
      return 1
    fi

    if [[ "$match_count" -gt 1 ]]; then
      echo "Created release $release_id for tag '$tag', but $match_count releases now match that tag (expected exactly 1)." >&2
      echo "Multiple releases now claim tag '$tag' -- a concurrent or contradictory release state. Refusing to proceed; the created release is left as-is for manual review." >&2
      rm -f "$matches_file"
      return 1
    fi

    # Exactly one match: prove it is this run's own release before
    # declaring success -- a same-tag release owned by someone else must
    # never be silently mistaken for the one this run just created.
    local match_id match_tag match_draft
    match_id="$(jq -r '.id' "$matches_file")"
    match_tag="$(jq -r '.tag_name' "$matches_file")"
    match_draft="$(jq -r '.draft' "$matches_file")"
    rm -f "$matches_file"

    if [[ "$match_id" != "$release_id" ]]; then
      echo "Post-create scan found release $match_id for tag '$tag', but the create response returned release $release_id." >&2
      echo "A different release owns tag '$tag' (ownership mismatch). Refusing to proceed; the created release is left as-is for manual review." >&2
      return 1
    fi

    if [[ "$match_tag" != "$tag" ]]; then
      echo "Post-create scan match for release $release_id has tag '$match_tag', expected '$tag'." >&2
      echo "Created release $release_id could not be proven as the unique post-create owner of tag '$tag'. Refusing to proceed; the created release is left as-is for manual review." >&2
      return 1
    fi

    if [[ "$match_draft" != "true" ]]; then
      echo "Post-create scan match for release $release_id is not a draft (draft=$match_draft)." >&2
      echo "Created release $release_id could not be proven as the unique post-create owner of tag '$tag'. Refusing to proceed; the created release is left as-is for manual review." >&2
      return 1
    fi

    return 0
  done

  # Unreachable: every loop iteration returns before falling off the end.
  return 1
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

  # GitHub offers no atomic "create only if absent" call for releases,
  # and its releases-list endpoint is not guaranteed to be immediately
  # consistent with a just-completed create -- a freshly created release
  # can be directly addressable by id yet briefly absent from the list.
  # The create POST above is never retried past this point: everything
  # below is read-only (GET) verification of the release this run
  # already created. verify_release_by_id proves this exact release
  # exists and has the exact expected identity;
  # wait_for_unique_release_visibility then bounds how long this run
  # waits for the (separately eventually-consistent) releases list to
  # agree, without ever concluding "concurrent creation" merely because
  # that list has not caught up yet.
  if ! verify_release_by_id "$release_id" "$tag" "$target_commitish" "$prerelease"; then
    echo "Refusing to proceed: release $release_id could not be verified by direct id lookup immediately after creation." >&2
    return 1
  fi

  if ! wait_for_unique_release_visibility "$tag" "$release_id"; then
    return 1
  fi

  printf '%s\n' "$release_id"
}

# cmd_upload <release_id> <asset_path> [<asset_path> ...]
# Idempotent and re-runnable without corruption: an asset already
# present under the exact expected name is never silently overwritten
# or re-uploaded. Before uploading anything, every *.sha256 file passed
# alongside the file it checksums (same basename minus the suffix, also
# in this invocation) is verified against that file's actual sha256 --
# catching a stale/wrong checksum before it is ever published. Then, for
# each asset: if no existing asset has that exact name, it is uploaded;
# if one already does, its content is downloaded and hashed for a strong
# byte-identical proof -- matching means "already done, skip", anything
# else (mismatch, or the proof itself could not be obtained) fails
# closed rather than risk silently overwriting a different file under
# the same name. Finally the release's asset list is re-fetched and
# every expected name is confirmed present exactly once.
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
    local asset_name
    asset_name="$(basename "$asset_path")"
    case "$asset_name" in
      *.sha256)
        local subject_name="${asset_name%.sha256}"
        local subject_path="" candidate
        for candidate in "$@"; do
          if [[ "$(basename "$candidate")" == "$subject_name" ]]; then
            subject_path="$candidate"
            break
          fi
        done
        if [[ -n "$subject_path" ]]; then
          local expected_hash actual_hash
          expected_hash="$(awk '{print $1}' "$asset_path" | tr '[:upper:]' '[:lower:]')"
          actual_hash="$(local_sha256 "$subject_path")"
          if [[ -z "$expected_hash" || "$expected_hash" != "$actual_hash" ]]; then
            echo "Refusing to upload: checksum file '$asset_name' does not match the actual sha256 of '$subject_name'." >&2
            return 1
          fi
        fi
        ;;
    esac
  done

  local assets_file
  assets_file="$(mktemp)"
  if ! list_release_assets "$release_id" "$assets_file"; then
    echo "Refusing to upload: the existing asset list for release $release_id could not be verified, so overwrite safety cannot be proven." >&2
    rm -f "$assets_file"
    return 1
  fi

  for asset_path in "$@"; do
    local asset_name matching_ids match_count
    asset_name="$(basename "$asset_path")"
    matching_ids="$(jq -r --arg name "$asset_name" 'select(.name == $name) | .id' "$assets_file" 2>/dev/null)"
    match_count=0
    if [[ -n "$matching_ids" ]]; then
      match_count="$(printf '%s\n' "$matching_ids" | wc -l | tr -d '[:space:]')"
    fi

    if [[ "$match_count" -eq 0 ]]; then
      continue
    fi

    if [[ "$match_count" -gt 1 ]]; then
      echo "Refusing to upload: release $release_id already has $match_count assets named '$asset_name' (expected at most one)." >&2
      rm -f "$assets_file"
      return 1
    fi

    local existing_asset_id="$matching_ids" existing_copy download_status
    existing_copy="$(mktemp)"
    if ! download_status="$(github_api_download_binary "$(api_base)/repos/${GITHUB_REPOSITORY}/releases/assets/${existing_asset_id}" "$existing_copy")"; then
      echo "Refusing to upload: asset '$asset_name' already exists on release $release_id (id $existing_asset_id), but it could not be downloaded to verify it is identical." >&2
      rm -f "$existing_copy" "$assets_file"
      return 1
    fi

    if [[ "$download_status" != "200" ]]; then
      echo "Refusing to upload: asset '$asset_name' already exists on release $release_id (id $existing_asset_id), but downloading it to verify identity returned HTTP $download_status." >&2
      rm -f "$existing_copy" "$assets_file"
      return 1
    fi

    local existing_hash local_hash
    existing_hash="$(local_sha256 "$existing_copy")"
    local_hash="$(local_sha256 "$asset_path")"
    rm -f "$existing_copy"

    if [[ "$existing_hash" != "$local_hash" ]]; then
      echo "Refusing to upload: asset '$asset_name' already exists on release $release_id (id $existing_asset_id) with different content (sha256 mismatch). Never silently overwriting an existing release asset." >&2
      rm -f "$assets_file"
      return 1
    fi

    echo "Asset '$asset_name' already present on release $release_id (id $existing_asset_id) and verified byte-identical; skipping upload."
  done

  for asset_path in "$@"; do
    local asset_name already_present
    asset_name="$(basename "$asset_path")"
    already_present="$(jq -r --arg name "$asset_name" 'select(.name == $name) | .id' "$assets_file" 2>/dev/null)"
    if [[ -n "$already_present" ]]; then
      continue
    fi

    local body_file status
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
      rm -f "$body_file" "$assets_file"
      return 1
    fi

    if [[ "$status" != "201" ]]; then
      echo "Failed to upload asset '$asset_name' to release $release_id: HTTP $status." >&2
      cat "$body_file" >&2
      rm -f "$body_file" "$assets_file"
      return 1
    fi
    rm -f "$body_file"

    echo "Uploaded asset '$asset_name' to release $release_id."
  done
  rm -f "$assets_file"

  local final_assets_file
  final_assets_file="$(mktemp)"
  if ! list_release_assets "$release_id" "$final_assets_file"; then
    echo "Refusing to declare the upload successful: the final asset list for release $release_id could not be verified." >&2
    rm -f "$final_assets_file"
    return 1
  fi

  for asset_path in "$@"; do
    local asset_name final_matches final_count
    asset_name="$(basename "$asset_path")"
    final_matches="$(jq -r --arg name "$asset_name" 'select(.name == $name) | .id' "$final_assets_file" 2>/dev/null)"
    final_count=0
    if [[ -n "$final_matches" ]]; then
      final_count="$(printf '%s\n' "$final_matches" | wc -l | tr -d '[:space:]')"
    fi
    if [[ "$final_count" -ne 1 ]]; then
      echo "Refusing to declare the upload successful: release $release_id has $final_count asset(s) named '$asset_name' after upload (expected exactly 1)." >&2
      rm -f "$final_assets_file"
      return 1
    fi
  done
  rm -f "$final_assets_file"
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

# cmd_recovery_finalize <release_id> <tag>
# The recovery-only counterpart to cmd_finalize (Part 9): if the release
# is still a draft, this performs the exact same finalize (re-check,
# then PATCH draft:false) as cmd_finalize. If it is already public --
# an expected, valid state for recovery to resume, e.g. a release a
# human already published out-of-band -- this is never treated as a
# failure: it is verified (exact tag match) and reported as an
# idempotent success without ever re-PATCHing an already-public
# release. This distinction only exists for recovery; the normal
# workflow keeps using cmd_finalize, which must keep refusing an
# already-public release outright so a rerun never silently treats one
# as a fresh success.
cmd_recovery_finalize() {
  local release_id="$1" tag="$2"
  local body_file status

  body_file="$(mktemp)"
  if ! status="$(github_api_call GET "$(api_base)/repos/${GITHUB_REPOSITORY}/releases/${release_id}" "$body_file")"; then
    echo "Unable to reach the GitHub API to re-check release $release_id before recovery finalization." >&2
    rm -f "$body_file"
    return 1
  fi

  if [[ "$status" != "200" ]]; then
    echo "Unexpected response while re-checking release $release_id before recovery finalization: HTTP $status." >&2
    rm -f "$body_file"
    return 1
  fi

  local current_tag current_draft_type current_draft
  if ! current_tag="$(jq -r 'if (.tag_name | type) == "string" then .tag_name else "" end' "$body_file" 2>/dev/null)" || \
     ! current_draft_type="$(jq -r '.draft | type' "$body_file" 2>/dev/null)" || \
     ! current_draft="$(jq -r '.draft' "$body_file" 2>/dev/null)"; then
    echo "Response for release $release_id could not be parsed as JSON before recovery finalization." >&2
    rm -f "$body_file"
    return 1
  fi
  rm -f "$body_file"

  if [[ "$current_tag" != "$tag" ]]; then
    echo "Refusing to finalize release $release_id: it is tagged '$current_tag', expected '$tag'." >&2
    return 1
  fi

  if [[ "$current_draft_type" != "boolean" ]]; then
    echo "Refusing to finalize release $release_id: its draft state is not a well-formed boolean (draft=$current_draft)." >&2
    return 1
  fi

  if [[ "$current_draft" == "false" ]]; then
    echo "Release $release_id (tag '$tag') is already public; recovery never re-finalizes an already-public release. Verified as already published."
    return 0
  fi

  cmd_finalize "$release_id" "$tag"
}

main() {
  if [[ $# -lt 1 ]]; then
    echo "Usage: $0 <create|notes|upload|finalize|recovery-finalize> ..." >&2
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
    recovery-finalize)
      [[ $# -eq 2 ]] || { echo "Usage: $0 recovery-finalize <release_id> <tag>" >&2; exit 1; }
      cmd_recovery_finalize "$1" "$2"
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
