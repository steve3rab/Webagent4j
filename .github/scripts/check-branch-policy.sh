#!/usr/bin/env bash
# Enforces which head branch name patterns may open a pull request against
# which base branch. Uses only the PR's actual base and head ref names --
# never PR title/body text -- and fails closed for any base branch this
# policy does not explicitly recognize.
#
# Policy:
#   base = develop -> head must match: feat/*, fix/*, refactor/*, version/*,
#                                       task/*, docs/*, test/*
#   base = main    -> head must match: version/*, fix/* (hotfixes)
#   any other base -> refused
#
# Usage: check-branch-policy.sh <base_ref> <head_ref>
set -euo pipefail

# is_allowed_head_for_base <base_ref> <head_ref>
# Pure decision function, isolated for unit testing. Returns 0 if the
# combination is allowed, 1 otherwise (including for any base_ref this
# policy does not recognize -- fail closed by default).
is_allowed_head_for_base() {
  local base="$1" head="$2"

  case "$base" in
    develop)
      case "$head" in
        feat/* | fix/* | refactor/* | version/* | task/* | docs/* | test/*)
          return 0
          ;;
        *)
          return 1
          ;;
      esac
      ;;
    main)
      case "$head" in
        version/* | fix/*)
          return 0
          ;;
        *)
          return 1
          ;;
      esac
      ;;
    *)
      return 1
      ;;
  esac
}

# describe_policy_for_base <base_ref>
# Prints the human-readable rule for a base ref, for diagnostics only.
describe_policy_for_base() {
  local base="$1"
  case "$base" in
    develop)
      echo "PRs into 'develop' must come from one of: feat/*, fix/*, refactor/*, version/*, task/*, docs/*, test/*."
      ;;
    main)
      echo "PRs into 'main' must come from one of: version/*, fix/* (hotfixes)."
      ;;
    *)
      echo "'$base' is not a base branch this policy recognizes; refusing by default (fail-closed)."
      ;;
  esac
}

main() {
  if [[ $# -ne 2 ]]; then
    echo "Usage: $0 <base_ref> <head_ref>" >&2
    exit 1
  fi

  local base="$1" head="$2"

  if [[ -z "$base" ]]; then
    echo "Base ref must not be empty." >&2
    exit 1
  fi

  if [[ -z "$head" ]]; then
    echo "Head ref must not be empty." >&2
    exit 1
  fi

  if is_allowed_head_for_base "$base" "$head"; then
    echo "Branch policy satisfied: '$head' -> '$base' is allowed."
    exit 0
  fi

  echo "Branch policy violation: '$head' -> '$base' is not an allowed combination." >&2
  describe_policy_for_base "$base" >&2
  exit 1
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  main "$@"
fi
