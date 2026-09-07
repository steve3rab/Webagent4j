#!/usr/bin/env bash
# Resolves the effective Revapi API-compatibility baseline for
# .github/workflows/api-compat.yml.
#
# Normal case: the declared future baseline (.github/api-baseline-version)
# already has a matching git tag (v<version>) in origin -- that tag is
# used, exactly as this workflow always required.
#
# Pre-tag release-PR exception: a release candidate pull request
# targeting main may legitimately declare its own future stable version
# as that baseline (.github/api-baseline-version == the candidate's own
# Maven version) *before* the corresponding tag exists -- the tag can
# only be created after this exact PR merges. In that narrow, strictly
# gated situation, the *effective* Revapi comparison baseline instead
# becomes whatever stable version is currently on `main` -- authoritative,
# protected branch state the candidate branch cannot influence -- fetched
# fresh, since main is a genuinely different ref than the candidate here.
#
# Pre-tag post-merge-push exception: once that same release PR merges,
# GitHub's own merge fires a push event on main itself -- at that point
# "current" already *is* main's new tip, so main's "previous" state can
# no longer come from a separate fetch of "origin main" (that would just
# be this exact same commit again). Instead, since GitHub's "Merge pull
# request" always produces a standard two-parent merge commit whose
# *first* parent is, by git's own definition of a merge (never a
# convention this script invents), the exact previous tip of the branch
# merged into, that first parent is fetched and its Maven version used as
# the effective baseline. The exact corresponding version tag must exist;
# the workflow then checks that tag's Maven version before Revapi runs.
#
# Neither exception is ever used once the declared tag actually exists,
# and both leave every other situation (non-main PR, a push to any other
# branch, a still-SNAPSHOT candidate, a declared baseline that disagrees
# with the candidate, an unresolvable or already-used-up previous stable
# version, or a missing/mismatched previous-stable tag) failing closed
# exactly as before. Revapi itself is never skipped or bypassed in any
# path.
#
# determine_effective_baseline is the pure decision function covering
# every branch of this policy; main() performs the real file/git/Maven
# I/O and hands its results to that function. See
# .github/scripts/tests/resolve-api-baseline.test.sh for the exhaustive,
# network-free regression matrix (API-REL-001..007, API-MAIN-001..008,
# and beyond) against the pure functions alone.
#
# Usage: resolve-api-baseline.sh <repo_root>
# Required env: GITHUB_EVENT_NAME
# Optional env: GITHUB_BASE_REF (set by GitHub Actions for pull_request
#   events; absent/empty for push, workflow_dispatch, and schedule)
#   GITHUB_REF_NAME (set for push events to the pushed branch's short
#   name; also set, differently, for other event types, so it is only
#   ever consulted here when event_name is exactly "push")
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

# determine_effective_baseline <event_name> <base_ref> <ref_name>
#   <candidate_version> <declared_baseline> <declared_tag_exists>
#   <fallback_version> <fallback_tag_exists>
#
# Pure decision function: no I/O, no external command. Every argument is
# an already-resolved plain string; <declared_tag_exists> and
# <fallback_tag_exists> are exactly "true" or "false". <fallback_version>
# is whichever previous/current stable version main() already resolved
# for the situation at hand (main's own live state for a release PR, or
# main's merge-commit first parent when it already carries the candidate;
# main's merge-commit first parent for a post-merge push) -- this
# function does not care which mechanism produced it, only whether it is
# usable.
#
# Prints "<effective_version> <effective_tag>" on stdout and returns 0
# when a genuinely comparable baseline is determined; otherwise prints a
# diagnostic on stderr and returns 1. Every failure mode fails closed --
# there is no fallback that silently skips the comparison.
determine_effective_baseline() {
  local event_name="$1" base_ref="$2" ref_name="$3" candidate_version="$4" \
    declared_baseline="$5" declared_tag_exists="$6" \
    fallback_version="$7" fallback_tag_exists="$8"

  echo "Declared API baseline: $declared_baseline" >&2
  echo "Candidate reactor version: $candidate_version" >&2

  if [[ "$declared_tag_exists" == "true" ]]; then
    echo "Declared baseline tag (v$declared_baseline) already exists -- using it as the effective Revapi baseline." >&2
    printf '%s v%s\n' "$declared_baseline" "$declared_baseline"
    return 0
  fi

  echo "Future baseline tag v$declared_baseline does not exist yet." >&2

  local exception_kind=""
  if [[ "$event_name" == "pull_request" && "$base_ref" == "main" ]]; then
    exception_kind="release-pr"
  elif [[ "$event_name" == "push" && "$ref_name" == "main" ]]; then
    exception_kind="push-main"
  else
    echo "Event is '$event_name' (base='${base_ref:-<unset>}', ref='${ref_name:-<unset>}'): the pre-tag exception only applies to a pull request targeting main, or a push directly on main." >&2
    return 1
  fi

  if ! is_stable_version "$candidate_version"; then
    echo "Candidate reactor version '$candidate_version' is not a stable release version: the pre-tag exception requires a stable candidate before its future baseline tag exists." >&2
    return 1
  fi

  if [[ "$candidate_version" != "$declared_baseline" ]]; then
    echo "Declared API baseline ($declared_baseline) does not equal the candidate release version ($candidate_version): the pre-tag exception only applies when a release candidate declares itself as its own future baseline." >&2
    return 1
  fi

  if [[ "$exception_kind" == "release-pr" ]]; then
    echo "Release PR to main detected: candidate $candidate_version declares itself as the future API baseline, and tag v$candidate_version does not exist yet." >&2
    echo "Resolving the effective Revapi comparison baseline from the current stable version on main instead." >&2
  else
    echo "Post-merge push to main detected: candidate $candidate_version declares itself as the future API baseline, and tag v$candidate_version does not exist yet." >&2
    echo "Resolving the effective Revapi comparison baseline from main's own previous state (its merge commit's first parent) instead." >&2
  fi

  if ! is_stable_version "$fallback_version"; then
    echo "The resolved fallback reactor version ('$fallback_version') is not a valid stable release version -- cannot use it as the effective Revapi baseline." >&2
    return 1
  fi

  if [[ "$fallback_version" == "$candidate_version" ]]; then
    echo "The resolved fallback version ($fallback_version) equals the candidate release version -- refusing to compare a release candidate against itself." >&2
    return 1
  fi

  if [[ "$fallback_tag_exists" != "true" ]]; then
    echo "Resolved previous stable version ($fallback_version) has no matching, verified tag in origin: v$fallback_version" >&2
    return 1
  fi

  echo "Using previous stable version $fallback_version as effective Revapi baseline (tag v$fallback_version)." >&2
  printf '%s v%s\n' "$fallback_version" "$fallback_version"
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

# tag_exists_in_origin <repo_dir> <tag>
# Always scoped to an explicit checked-out repository directory -- never
# the caller's own cwd, which need not be a git repository at all (the
# job's default workspace root, for one, is not: only the "current" and
# throwaway worktree checkouts under it are).
tag_exists_in_origin() {
  local repo_dir="$1" tag="$2"
  git -C "$repo_dir" ls-remote --exit-code --tags origin "refs/tags/$tag" > /dev/null
}

# resolve_main_stable_state <repo_root> <candidate_version>
# Fetches main's exact current tip and resolves its Maven version via a
# throwaway worktree checkout -- used only when a *different* branch (a
# release candidate pull request) needs to know main's own live state.
# If protected main already carries the candidate version (as it does for
# a governance-only PR opened during the post-release/pre-tag window),
# resolve main's own previous state with the same strict topology rule as
# a post-merge push. Prints "<version> <sha>".
resolve_main_stable_state() {
  local repo_root="$1" candidate_version="$2"
  local main_ref_dir
  main_ref_dir="$(mktemp -d)"

  # Two generations are required: a depth-1 fetch marks main's tip as a
  # shallow root and hides the very parents this policy must validate.
  git -C "$repo_root" fetch --depth 2 origin main
  git -C "$repo_root" worktree add --detach "$main_ref_dir" FETCH_HEAD > /dev/null

  local main_sha
  main_sha="$(git -C "$main_ref_dir" rev-parse HEAD)"
  echo "Resolving main's Maven version from its exact fetched HEAD: $main_sha" >&2

  local main_version
  main_version="$(resolve_maven_version "$main_ref_dir")"

  local result
  if [[ "$main_version" == "$candidate_version" ]]; then
    echo "Protected main already carries candidate version $candidate_version; resolving its previous stable state." >&2
    result="$(resolve_previous_stable_topology "$main_ref_dir")"
  else
    result="$main_version $main_sha"
  fi

  git -C "$repo_root" worktree remove --force "$main_ref_dir"
  rm -rf "$main_ref_dir"

  printf '%s\n' "$result"
}

# resolve_previous_stable_topology <repo_root>
# Only meaningful when repo_root's checked-out HEAD is a genuine
# two-parent merge commit -- exactly what GitHub's "Merge pull request"
# button always produces, with the *first* parent always being the exact
# previous tip of the branch the PR was merged into. That is a guarantee
# of how "git merge" itself defines a merge commit's parents, never a
# convention this script invents or a heuristic like "latest tag" or
# "git describe". Used for a push directly on main: at that point
# "current" already *is* main's new tip, so main's own *previous* state
# can only come from its own history.
#
# Prints "<version> <sha>" for that first parent on stdout when the
# topology is a genuine two-parent merge; fails closed with a diagnostic
# on stderr for anything else (a fast-forward push, a squash merge, an
# octopus merge, or a root commit) -- never guessed at.
resolve_previous_stable_topology() {
  local repo_root="$1"

  local parent_shas
  parent_shas="$(git -C "$repo_root" log -1 --format='%P' HEAD)"

  local parent_count=0
  if [[ -n "$parent_shas" ]]; then
    parent_count="$(wc -w <<< "$parent_shas")"
  fi

  if [[ "$parent_count" -ne 2 ]]; then
    echo "HEAD is not a standard two-parent merge commit (found $parent_count parent(s)) -- cannot deterministically identify the previous main state from its own topology." >&2
    return 1
  fi

  local old_main_sha
  old_main_sha="$(awk '{print $1}' <<< "$parent_shas")"
  echo "Merge commit detected; treating its first parent ($old_main_sha) as the exact previous tip of main." >&2

  git -C "$repo_root" fetch --depth 1 origin "$old_main_sha"

  local old_main_dir
  old_main_dir="$(mktemp -d)"
  git -C "$repo_root" worktree add --detach "$old_main_dir" "$old_main_sha" > /dev/null

  local old_main_version
  old_main_version="$(resolve_maven_version "$old_main_dir")"

  git -C "$repo_root" worktree remove --force "$old_main_dir"
  rm -rf "$old_main_dir"

  printf '%s %s\n' "$old_main_version" "$old_main_sha"
}

main() {
  if [[ $# -ne 1 ]]; then
    echo "Usage: $0 <repo_root>" >&2
    exit 1
  fi

  local repo_root="$1"
  local event_name="${GITHUB_EVENT_NAME:-}"
  local base_ref="${GITHUB_BASE_REF:-}"
  local ref_name="${GITHUB_REF_NAME:-}"

  local declared_baseline
  declared_baseline="$(read_declared_baseline "$repo_root/.github/api-baseline-version")"

  local candidate_version
  candidate_version="$(resolve_maven_version "$repo_root")"
  if [[ -z "$candidate_version" ]]; then
    echo "Could not resolve the candidate reactor version from the root POM at $repo_root." >&2
    exit 1
  fi

  local declared_tag_exists="false"
  if tag_exists_in_origin "$repo_root" "v$declared_baseline"; then
    declared_tag_exists="true"
  fi

  local fallback_version="" fallback_tag_exists="false"

  # Only pay for any of this when the declared tag is actually missing
  # AND every cheap, purely-informational precondition shared by both
  # pre-tag exceptions is already met -- an ordinary PR/push (or a
  # workflow_dispatch) with a missing tag must fail fast, exactly as
  # before, without ever touching main or the commit graph.
  if [[ "$declared_tag_exists" != "true" ]] \
    && is_stable_version "$candidate_version" \
    && [[ "$candidate_version" == "$declared_baseline" ]]; then

    if [[ "$event_name" == "pull_request" && "$base_ref" == "main" ]]; then
      local main_state
      if main_state="$(resolve_main_stable_state "$repo_root" "$candidate_version")"; then
        fallback_version="${main_state%% *}"
      fi
      if is_stable_version "$fallback_version" \
        && tag_exists_in_origin "$repo_root" "v$fallback_version"; then
        fallback_tag_exists="true"
      fi

    elif [[ "$event_name" == "push" && "$ref_name" == "main" ]]; then
      local topo_result
      if topo_result="$(resolve_previous_stable_topology "$repo_root")"; then
        fallback_version="${topo_result%% *}"

        if is_stable_version "$fallback_version" \
          && tag_exists_in_origin "$repo_root" "v$fallback_version"; then
          fallback_tag_exists="true"
        fi
      fi
    fi
  fi

  local result
  if ! result="$(determine_effective_baseline \
    "$event_name" "$base_ref" "$ref_name" "$candidate_version" "$declared_baseline" \
    "$declared_tag_exists" "$fallback_version" "$fallback_tag_exists")"; then
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
