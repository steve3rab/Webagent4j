#!/usr/bin/env bash
# Resolves the effective Revapi API-compatibility baseline for
# .github/workflows/api-compat.yml.
#
# Normal case: the declared future baseline (.github/api-baseline-version)
# already has a matching git tag (v<version>) in origin -- that tag is
# used, exactly as this workflow always required.
#
# Pre-tag release exception: a release candidate pull request targeting
# main may legitimately declare its own future stable version as that
# baseline (.github/api-baseline-version == the candidate's own Maven
# version) *before* the corresponding tag exists -- the tag can only be
# created after this exact PR merges. Requiring the tag to already exist
# in that one situation is a circular dependency the release process could
# never satisfy. In that narrow, strictly-gated situation only, the
# *effective* Revapi comparison baseline instead becomes whatever stable
# version is currently on `main` -- authoritative, protected branch state
# the candidate branch cannot influence -- so Revapi still genuinely runs,
# against the real current stable release, rather than being skipped or
# bypassed.
#
# determine_effective_baseline is the pure decision function covering
# every branch of this policy; main() performs the real file/git/Maven I/O
# and hands its results to that function. See
# .github/scripts/tests/resolve-api-baseline.test.sh for the exhaustive,
# network-free regression matrix (API-REL-001..007 and beyond) against the
# pure functions alone.
#
# Usage: resolve-api-baseline.sh <repo_root>
# Required env: GITHUB_EVENT_NAME
# Optional env: GITHUB_BASE_REF (set by GitHub Actions for pull_request
#   events; absent/empty for push, workflow_dispatch, and schedule)
# On success, prints exactly two lines to stdout:
#   version=<effective baseline version>
#   tag=<effective baseline tag>
# All other output (diagnostics) goes to stderr.
set -euo pipefail
# Without this, a failing command inside a command substitution nested
# two levels deep (e.g. a failing ./mvnw call inside resolve_maven_version,
# itself invoked as candidate_version="$(resolve_maven_version ...)") does
# NOT trigger -e in bash < 4.4 semantics: the failure is silently
# swallowed and whatever the failing command printed to stdout (Maven
# prints its own [ERROR] lines to stdout, not stderr) is treated as a
# valid result instead of aborting. inherit_errexit (bash 4.4+) closes
# that gap so every failure here genuinely fails closed.
shopt -s inherit_errexit

SEMVER_STABLE_PATTERN='^[0-9]+\.[0-9]+\.[0-9]+$'

# is_stable_version <version>
# Pure. True only for a plain X.Y.Z stable release version -- never a
# -SNAPSHOT or other pre-release qualifier, never empty.
is_stable_version() {
  [[ "$1" =~ $SEMVER_STABLE_PATTERN ]]
}

# determine_effective_baseline <event_name> <base_ref> <candidate_version>
#   <declared_baseline> <declared_tag_exists> <main_version> <main_tag_exists>
#
# Pure decision function: no I/O, no external command. Every argument is
# an already-resolved plain string; <declared_tag_exists> and
# <main_tag_exists> are exactly "true" or "false".
#
# Prints "<effective_version> <effective_tag>" on stdout and returns 0
# when a genuinely comparable baseline is determined; otherwise prints a
# diagnostic on stderr and returns 1. Every failure mode fails closed --
# there is no fallback that silently skips the comparison.
determine_effective_baseline() {
  local event_name="$1" base_ref="$2" candidate_version="$3" \
    declared_baseline="$4" declared_tag_exists="$5" \
    main_version="$6" main_tag_exists="$7"

  echo "Declared API baseline: $declared_baseline" >&2
  echo "Candidate reactor version: $candidate_version" >&2

  if [[ "$declared_tag_exists" == "true" ]]; then
    echo "Declared baseline tag (v$declared_baseline) already exists -- using it as the effective Revapi baseline." >&2
    printf '%s v%s\n' "$declared_baseline" "$declared_baseline"
    return 0
  fi

  echo "Future baseline tag v$declared_baseline does not exist yet." >&2

  if [[ "$event_name" != "pull_request" ]]; then
    echo "Event is '$event_name', not 'pull_request': the pre-tag release exception never applies outside a pull request." >&2
    return 1
  fi

  if [[ "$base_ref" != "main" ]]; then
    echo "Pull request base is '${base_ref:-<unset>}', not 'main': the pre-tag release exception only applies to a release pull request targeting main." >&2
    return 1
  fi

  if ! is_stable_version "$candidate_version"; then
    echo "Candidate reactor version '$candidate_version' is not a stable release version: a release pull request to main must carry a stable version before its future baseline tag exists." >&2
    return 1
  fi

  if [[ "$candidate_version" != "$declared_baseline" ]]; then
    echo "Declared API baseline ($declared_baseline) does not equal the candidate release version ($candidate_version): the pre-tag release exception only applies when a release candidate declares itself as its own future baseline." >&2
    return 1
  fi

  echo "Release PR to main detected: candidate $candidate_version declares itself as the future API baseline, and tag v$candidate_version does not exist yet." >&2
  echo "Resolving the effective Revapi comparison baseline from the current stable version on main instead." >&2

  if ! is_stable_version "$main_version"; then
    echo "main's reactor version ('$main_version') is not a valid stable release version -- cannot use it as the effective Revapi baseline." >&2
    return 1
  fi

  if [[ "$main_version" == "$candidate_version" ]]; then
    echo "main's stable version ($main_version) equals the candidate release version -- refusing to compare a release candidate against itself." >&2
    return 1
  fi

  if [[ "$main_tag_exists" != "true" ]]; then
    echo "Current stable version on main ($main_version) has no matching tag in origin: v$main_version" >&2
    return 1
  fi

  echo "Using current main stable version $main_version as effective Revapi baseline (tag v$main_version)." >&2
  printf '%s v%s\n' "$main_version" "$main_version"
  return 0
}

# read_declared_baseline <baseline_file>
# Reads and validates .github/api-baseline-version. Prints the declared
# baseline version on stdout, or fails closed with a diagnostic on stderr.
read_declared_baseline() {
  local baseline_file="$1"

  if [[ ! -f "$baseline_file" ]]; then
    echo "API baseline file is missing: $baseline_file" >&2
    return 1
  fi

  local declared
  declared="$(tr -d '[:space:]' < "$baseline_file")"

  if [[ -z "$declared" ]]; then
    echo "API baseline file is empty: $baseline_file" >&2
    return 1
  fi

  if ! is_stable_version "$declared"; then
    echo "API baseline version is not a valid stable release version: '$declared'" >&2
    return 1
  fi

  printf '%s' "$declared"
}

# resolve_maven_version <working_dir>
# Resolves the root Maven project version at <working_dir> via the
# project's own Maven wrapper -- never a fragile XML/grep extraction.
resolve_maven_version() {
  local working_dir="$1"
  local version
  version="$(
    cd "$working_dir" && ./mvnw \
      --batch-mode \
      --no-transfer-progress \
      -q \
      -Dstyle.color=never \
      -DforceStdout \
      help:evaluate \
      -Dexpression=project.version
  )"
  printf '%s' "$version" | tr -d '\r\n'
}

# tag_exists_in_origin <tag>
tag_exists_in_origin() {
  local tag="$1"
  git ls-remote --exit-code --tags origin "refs/tags/$tag" > /dev/null
}

main() {
  if [[ $# -ne 1 ]]; then
    echo "Usage: $0 <repo_root>" >&2
    exit 1
  fi

  local repo_root="$1"
  local event_name="${GITHUB_EVENT_NAME:-}"
  local base_ref="${GITHUB_BASE_REF:-}"

  local declared_baseline
  declared_baseline="$(read_declared_baseline "$repo_root/.github/api-baseline-version")"

  local candidate_version
  candidate_version="$(resolve_maven_version "$repo_root")"
  if [[ -z "$candidate_version" ]]; then
    echo "Could not resolve the candidate reactor version from the root POM at $repo_root." >&2
    exit 1
  fi

  local declared_tag_exists="false"
  if tag_exists_in_origin "v$declared_baseline"; then
    declared_tag_exists="true"
  fi

  local main_version="" main_tag_exists="false"

  if [[ "$declared_tag_exists" != "true" ]]; then
    # Only pay for a main checkout when the declared tag is actually
    # missing AND every cheap, purely-informational precondition for the
    # exception is already met -- a non-release PR (or an ordinary
    # push/workflow_dispatch) with a missing tag must fail fast, exactly
    # as before, without ever touching main.
    if [[ "$event_name" == "pull_request" && "$base_ref" == "main" ]] \
      && is_stable_version "$candidate_version" \
      && [[ "$candidate_version" == "$declared_baseline" ]]; then
      local main_ref_dir
      main_ref_dir="$(mktemp -d)"

      git -C "$repo_root" fetch --depth 1 origin main
      git -C "$repo_root" worktree add --detach "$main_ref_dir" FETCH_HEAD > /dev/null

      local main_sha
      main_sha="$(git -C "$main_ref_dir" rev-parse HEAD)"
      echo "Resolving main's Maven version from its exact fetched HEAD: $main_sha" >&2

      main_version="$(resolve_maven_version "$main_ref_dir")"

      git -C "$repo_root" worktree remove --force "$main_ref_dir"
      rm -rf "$main_ref_dir"

      if is_stable_version "$main_version" && tag_exists_in_origin "v$main_version"; then
        main_tag_exists="true"
      fi
    fi
  fi

  local result
  if ! result="$(determine_effective_baseline \
    "$event_name" "$base_ref" "$candidate_version" "$declared_baseline" \
    "$declared_tag_exists" "$main_version" "$main_tag_exists")"; then
    exit 1
  fi

  local effective_version effective_tag
  read -r effective_version effective_tag <<< "$result"

  echo "version=$effective_version"
  echo "tag=$effective_tag"
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  main "$@"
fi
