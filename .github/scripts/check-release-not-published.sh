#!/usr/bin/env bash
# Fails closed unless the GitHub API confirms, unambiguously, that no
# GitHub Release -- published or draft -- exists yet for the given tag.
# A network failure, an unexpected HTTP status, an unparseable response,
# or a contradictory/ambiguous combination of results are all treated as
# an indeterminate state -- never as "no release exists".
#
# GET /repos/{owner}/{repo}/releases/tags/{tag} only ever returns a
# *published* release for that tag; GitHub does not expose a draft
# through that endpoint even when one already exists for the same tag
# name. Relying on that endpoint alone is therefore not sufficient: this
# script also scans the full releases list (GET /repos/{owner}/{repo}/
# releases, paginated) for any entry whose tag_name matches exactly,
# published or draft, and fails if it finds one -- or more than one,
# which would itself mean a concurrent/contradictory state.
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

# github_api_get <path_and_query> <response_body_file>
# Performs a GET against the GitHub REST API. Writes the response body to
# the given file and prints the HTTP status code on stdout. Returns
# non-zero only on a transport-level failure (DNS, TLS, timeout,
# connection refused, ...), never on an HTTP error status -- those are
# returned as a normal status code for the caller to evaluate explicitly.
github_api_get() {
  local path_and_query="$1" body_file="$2"
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
    "${api_url}/repos/${GITHUB_REPOSITORY}${path_and_query}"
}

# fetch_release_by_tag <tag> <response_body_file>
# Prints the HTTP status code on stdout. See github_api_get for failure
# semantics.
fetch_release_by_tag() {
  local tag="$1" body_file="$2"
  github_api_get "/releases/tags/${tag}" "$body_file"
}

# fetch_releases_page <page> <response_body_file>
# One page (100 per page) of the full releases list, which -- unlike the
# by-tag endpoint -- includes drafts. Prints the HTTP status code on
# stdout.
fetch_releases_page() {
  local page="$1" body_file="$2"
  github_api_get "/releases?per_page=100&page=${page}" "$body_file"
}

# MAX_RELEASE_LIST_PAGES caps pagination so a misbehaving API can never
# make this script loop forever; hitting the cap is treated as an
# indeterminate state, not as "no more matches".
MAX_RELEASE_LIST_PAGES=50

# scan_releases_for_tag <tag> <matches_file>
# Pages through the full releases list and writes one compact JSON object
# per matching release (tag_name == tag) to matches_file, one per line.
# Returns 0 only if every page was fetched and parsed successfully,
# regardless of how many matches were found (0 matches is a normal,
# successful outcome for a tag with no release at all). Returns non-zero
# on any transport failure, unexpected HTTP status, unparseable page, or
# if the page cap is exceeded.
scan_releases_for_tag() {
  local tag="$1" matches_file="$2"
  local page_body page_status page=1

  : > "$matches_file"
  page_body="$(mktemp)"

  while true; do
    if (( page > MAX_RELEASE_LIST_PAGES )); then
      echo "Releases list for tag '$tag' did not terminate within ${MAX_RELEASE_LIST_PAGES} pages." >&2
      rm -f "$page_body"
      return 1
    fi

    if ! page_status="$(fetch_releases_page "$page" "$page_body")"; then
      echo "Unable to reach the GitHub API while listing releases (page $page) for tag '$tag'." >&2
      rm -f "$page_body"
      return 1
    fi

    if [[ "$page_status" != "200" ]]; then
      echo "Unexpected response while listing releases (page $page) for tag '$tag': HTTP $page_status." >&2
      rm -f "$page_body"
      return 1
    fi

    local page_matches page_count
    if ! page_matches="$(jq -c --arg tag "$tag" '.[] | select(.tag_name == $tag) | {id, draft, tag_name}' "$page_body" 2>/dev/null)"; then
      echo "Releases list page $page for tag '$tag' could not be parsed as JSON." >&2
      rm -f "$page_body"
      return 1
    fi

    if [[ -n "$page_matches" ]]; then
      printf '%s\n' "$page_matches" >> "$matches_file"
    fi

    if ! page_count="$(jq 'length' "$page_body" 2>/dev/null)"; then
      echo "Releases list page $page for tag '$tag' could not be parsed as JSON." >&2
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

# evaluate_by_tag_status <tag> <http_status> <response_body_file>
# Evaluates the /releases/tags/{tag} response alone. Prints a
# human-readable explanation. Echoes one of: absent, public, draft,
# indeterminate -- absent is the only outcome that still requires the
# releases-list scan before a final verdict can be reached, since this
# endpoint never reports a draft.
evaluate_by_tag_status() {
  local tag="$1" http_status="$2" body_file="$3"

  case "$http_status" in
    404)
      echo "absent"
      return 0
      ;;
    200)
      local is_draft
      if ! is_draft="$(jq -r '.draft' "$body_file" 2>/dev/null)"; then
        echo "A release was found for tag '$tag' but its draft status could not be determined from the API response." >&2
        echo "indeterminate"
        return 0
      fi

      case "$is_draft" in
        true)
          echo "draft"
          return 0
          ;;
        false)
          echo "public"
          return 0
          ;;
        *)
          echo "A release was found for tag '$tag' with an unrecognized or missing draft value: '$is_draft'." >&2
          echo "indeterminate"
          return 0
          ;;
      esac
      ;;
    *)
      echo "Unexpected response from the GitHub API while checking for an existing release on tag '$tag': HTTP $http_status." >&2
      echo "indeterminate"
      return 0
      ;;
  esac
}

# check_release_absent <tag>
# The authoritative check: returns 0 only when neither the by-tag lookup
# nor a full scan of the releases list finds any release -- published or
# draft -- for the given tag. Every other outcome, including any error
# encountered while determining this, returns non-zero and explains why
# on stderr.
check_release_absent() {
  local tag="$1"
  local by_tag_body
  by_tag_body="$(mktemp)"

  local by_tag_status
  if ! by_tag_status="$(fetch_release_by_tag "$tag" "$by_tag_body")"; then
    echo "Unable to reach the GitHub API to check for an existing release on tag '$tag'." >&2
    echo "Refusing to proceed: an unreachable release-status check must never be treated as 'no release exists'." >&2
    rm -f "$by_tag_body"
    return 1
  fi

  local by_tag_verdict
  by_tag_verdict="$(evaluate_by_tag_status "$tag" "$by_tag_status" "$by_tag_body")"
  rm -f "$by_tag_body"

  case "$by_tag_verdict" in
    public)
      echo "A PUBLIC GitHub Release already exists for tag '$tag'." >&2
      echo "This release was not produced by a completed, verified run of this workflow and must not be silently accepted as validated." >&2
      echo "Refusing to modify, replace, or merge into it automatically." >&2
      return 1
      ;;
    draft)
      echo "A DRAFT GitHub Release already exists for tag '$tag' (reported directly by the by-tag lookup)." >&2
      echo "This release pipeline never creates a draft release ahead of verification, so a pre-existing draft is unexpected." >&2
      echo "Refusing to publish automatically. Resolve manually: inspect and remove the stray draft by hand, then re-run." >&2
      return 1
      ;;
    indeterminate)
      echo "Refusing to proceed with an indeterminate release state for tag '$tag'." >&2
      return 1
      ;;
    absent)
      : # fall through to the releases-list scan below
      ;;
    *)
      echo "Internal error: unrecognized by-tag verdict '$by_tag_verdict' for tag '$tag'." >&2
      return 1
      ;;
  esac

  # The by-tag endpoint reported nothing, but it never reports drafts, so
  # a draft could still exist without being visible there. Scan the full
  # releases list, which does include drafts, before concluding "absent".
  local matches_file match_count
  matches_file="$(mktemp)"

  if ! scan_releases_for_tag "$tag" "$matches_file"; then
    echo "Refusing to proceed: the releases list for tag '$tag' could not be scanned, so a pre-existing draft cannot be ruled out." >&2
    rm -f "$matches_file"
    return 1
  fi

  match_count="$(wc -l < "$matches_file" | tr -d '[:space:]')"

  if [[ "$match_count" -eq 0 ]]; then
    rm -f "$matches_file"
    echo "No existing GitHub Release found for tag '$tag' (checked both the by-tag lookup and the full releases list). Safe to proceed to publish."
    return 0
  fi

  if [[ "$match_count" -gt 1 ]]; then
    echo "Found $match_count releases in the releases list matching tag '$tag' -- a contradictory or concurrent release state." >&2
    echo "Refusing to proceed: this tag must resolve to at most one release." >&2
    rm -f "$matches_file"
    return 1
  fi

  local matched_draft
  matched_draft="$(jq -r '.draft' "$matches_file" 2>/dev/null || echo "")"
  rm -f "$matches_file"

  if [[ "$matched_draft" == "true" ]]; then
    echo "A DRAFT GitHub Release already exists for tag '$tag' (not visible via the by-tag lookup, found in the releases list)." >&2
    echo "This release pipeline never creates a draft release ahead of verification, so a pre-existing draft is unexpected." >&2
    echo "Refusing to publish automatically. Resolve manually: inspect and remove the stray draft by hand, then re-run." >&2
  else
    echo "A GitHub Release already exists for tag '$tag' in the releases list, contradicting the by-tag lookup's 404." >&2
    echo "Refusing to proceed with a contradictory release state." >&2
  fi

  return 1
}

main() {
  if [[ $# -ne 1 || -z "${1:-}" ]]; then
    echo "Usage: $0 <tag>" >&2
    exit 1
  fi

  local tag="$1"

  : "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY must be set (owner/repo)}"
  : "${GITHUB_TOKEN:?GITHUB_TOKEN must be set}"

  check_release_absent "$tag"
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  main "$@"
fi
