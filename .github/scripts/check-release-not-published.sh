#!/usr/bin/env bash
# Fails closed unless the GitHub API confirms, unambiguously, that no
# GitHub Release exists yet for the given tag. A network failure, an
# unexpected HTTP status, or a response whose "draft" field cannot be
# parsed are all treated as an indeterminate state -- never as "no
# release exists".
#
# This workflow never creates a draft release ahead of verification, so
# a pre-existing release for the candidate tag -- draft or published --
# is always unexpected here and causes a fail-closed refusal. Recovery is
# a deliberate human action (inspect and remove the stray release by
# hand), never something this script or the workflow does automatically.
#
# Usage: check-release-not-published.sh <tag>
# Required env: GITHUB_REPOSITORY (owner/repo), GITHUB_TOKEN
# Optional env: GITHUB_API_URL (defaults to https://api.github.com)
set -euo pipefail

# fetch_release_status <tag> <response_body_file>
# Writes the response body to the given file and prints the HTTP status
# code on stdout. Returns non-zero only on a transport-level failure
# (DNS, TLS, timeout, connection refused, ...), never on an HTTP error
# status -- those are returned as a normal status code for the caller to
# evaluate explicitly.
fetch_release_status() {
  local tag="$1" body_file="$2"
  local api_url="${GITHUB_API_URL:-https://api.github.com}"

  curl \
    --silent \
    --show-error \
    --location \
    --max-time 30 \
    --output "$body_file" \
    --write-out '%{http_code}' \
    --header "Authorization: Bearer ${GITHUB_TOKEN}" \
    --header "Accept: application/vnd.github+json" \
    --header "X-GitHub-Api-Version: 2022-11-28" \
    "${api_url}/repos/${GITHUB_REPOSITORY}/releases/tags/${tag}"
}

# evaluate_release_status <tag> <http_status> <response_body_file>
# Pure decision logic, isolated for unit testing without any network
# access. Prints a human-readable explanation and returns 0 only when it
# is safe to proceed with publication (no release exists yet for the tag).
evaluate_release_status() {
  local tag="$1" http_status="$2" body_file="$3"

  case "$http_status" in
    404)
      echo "No existing GitHub Release found for tag '$tag'. Safe to proceed to publish."
      return 0
      ;;
    200)
      local is_draft
      if ! is_draft="$(jq -r '.draft' "$body_file" 2>/dev/null)"; then
        echo "A release was found for tag '$tag' but its draft status could not be determined from the API response." >&2
        echo "Refusing to proceed with an indeterminate release state." >&2
        return 1
      fi

      if [[ "$is_draft" == "null" ]]; then
        echo "A release was found for tag '$tag' but the API response has no 'draft' field." >&2
        echo "Refusing to proceed with an indeterminate release state." >&2
        return 1
      fi

      case "$is_draft" in
        true)
          echo "A DRAFT GitHub Release already exists for tag '$tag'." >&2
          echo "This release pipeline never creates a draft release ahead of verification, so a pre-existing draft is unexpected." >&2
          echo "Refusing to publish automatically. Resolve manually: inspect and remove the stray draft by hand, then re-run." >&2
          ;;
        false)
          echo "A PUBLIC GitHub Release already exists for tag '$tag'." >&2
          echo "This release was not produced by a completed, verified run of this workflow and must not be silently accepted as validated." >&2
          echo "Refusing to modify, replace, or merge into it automatically." >&2
          ;;
        *)
          echo "A release was found for tag '$tag' with an unrecognized draft value: '$is_draft'." >&2
          echo "Refusing to proceed with an indeterminate release state." >&2
          ;;
      esac
      return 1
      ;;
    *)
      echo "Unexpected response from the GitHub API while checking for an existing release on tag '$tag': HTTP $http_status." >&2
      echo "Refusing to proceed with an ambiguous release-status response." >&2
      return 1
      ;;
  esac
}

main() {
  if [[ $# -ne 1 || -z "${1:-}" ]]; then
    echo "Usage: $0 <tag>" >&2
    exit 1
  fi

  local tag="$1"

  : "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY must be set (owner/repo)}"
  : "${GITHUB_TOKEN:?GITHUB_TOKEN must be set}"

  # Intentionally not `local`: the EXIT trap below still needs to read
  # this after main() itself returns, once main's own local scope is
  # already gone.
  body_file="$(mktemp)"
  trap 'rm -f "$body_file"' EXIT

  local http_status
  if ! http_status="$(fetch_release_status "$tag" "$body_file")"; then
    echo "Unable to reach the GitHub API to check for an existing release on tag '$tag'." >&2
    echo "Refusing to proceed: an unreachable release-status check must never be treated as 'no release exists'." >&2
    exit 1
  fi

  evaluate_release_status "$tag" "$http_status" "$body_file"
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  main "$@"
fi
