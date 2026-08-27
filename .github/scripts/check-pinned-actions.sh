#!/usr/bin/env bash
# Fails closed if any GitHub Actions workflow uses a third-party or
# official GitHub Action pinned to anything other than a full 40-character
# immutable commit SHA -- a version tag (@v7), a branch (@main, @master),
# or any other mutable ref is rejected.
#
# Local actions (uses: ./some/path) and Docker-image actions
# (uses: docker://...) are not git refs in the same sense and are
# intentionally out of scope for this check.
#
# Usage: check-pinned-actions.sh [workflow-file ...]
# With no arguments, scans every .github/workflows/*.yml and *.yaml file
# relative to the current working directory.
set -euo pipefail

# extract_uses_refs <workflow_file>
# Prints one "line_number:value" pair per `uses:` value found (value is
# everything after `uses:`, e.g. "actions/checkout@v7"). Pure text
# extraction, isolated so it can be unit tested without needing a YAML
# parser.
extract_uses_refs() {
  local file="$1"
  grep -noE '^[[:space:]]*(-[[:space:]]+)?uses:[[:space:]]*[^[:space:]#]+' "$file" \
    | sed -E 's/^([0-9]+):[[:space:]]*(-[[:space:]]+)?uses:[[:space:]]*/\1:/'
}

# is_pinned_ref <ref>
# Returns 0 if ref is a bare 40-character hexadecimal commit SHA.
is_pinned_ref() {
  local ref="$1"
  [[ "$ref" =~ ^[0-9a-f]{40}$ ]]
}

# is_exempt_ref <ref>
# Returns 0 for references this check intentionally does not enforce
# pinning on: local actions and Docker-image actions.
is_exempt_ref() {
  local ref="$1"
  [[ "$ref" == ./* || "$ref" == docker://* ]]
}

# check_workflow_file <file>
# Prints one violation line per offending `uses:` entry to stderr.
# Returns 0 if the file has no violations, non-zero otherwise.
check_workflow_file() {
  local file="$1"
  local violations=0
  local line_no uses_value action_ref

  while IFS=: read -r line_no uses_value; do
    [[ -n "$line_no" ]] || continue

    if is_exempt_ref "$uses_value"; then
      continue
    fi

    action_ref="${uses_value##*@}"

    if [[ "$action_ref" == "$uses_value" ]]; then
      echo "$file:$line_no: 'uses: $uses_value' has no @ref at all." >&2
      violations=$((violations + 1))
      continue
    fi

    if ! is_pinned_ref "$action_ref"; then
      echo "$file:$line_no: 'uses: $uses_value' is not pinned to a full 40-character commit SHA." >&2
      violations=$((violations + 1))
    fi
  done < <(extract_uses_refs "$file")

  [[ "$violations" -eq 0 ]]
}

main() {
  local -a files=("$@")

  if [[ "${#files[@]}" -eq 0 ]]; then
    while IFS= read -r -d '' f; do
      files+=("$f")
    done < <(find .github/workflows -maxdepth 1 -type f \( -name '*.yml' -o -name '*.yaml' \) -print0 2>/dev/null | sort -z)
  fi

  if [[ "${#files[@]}" -eq 0 ]]; then
    echo "No workflow files found to check." >&2
    exit 1
  fi

  local overall_failures=0
  local file
  for file in "${files[@]}"; do
    if [[ ! -f "$file" ]]; then
      echo "$file: not found." >&2
      overall_failures=$((overall_failures + 1))
      continue
    fi
    if ! check_workflow_file "$file"; then
      overall_failures=$((overall_failures + 1))
    fi
  done

  if [[ "$overall_failures" -ne 0 ]]; then
    echo "Unpinned GitHub Actions found in $overall_failures file(s)." >&2
    exit 1
  fi

  echo "All GitHub Actions in ${#files[@]} workflow file(s) are pinned to immutable commit SHAs."
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  main "$@"
fi
