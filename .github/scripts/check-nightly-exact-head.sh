#!/usr/bin/env bash
# Fails closed unless every schedule/manual-dispatch checkout ref
# expression in nightly.yml uses the immutable github.sha for the manual
# dispatch path, never the mutable github.ref (or github.head_ref /
# github.ref_name).
#
# A branch/tag ref can move between the moment a workflow_dispatch run
# starts and the moment actions/checkout resolves it; the exact-head
# qualification guarantee this workflow depends on requires the exact
# commit SHA GitHub recorded for the run itself, not a ref that could
# point somewhere else by the time checkout runs.
#
# Usage: check-nightly-exact-head.sh [workflow-file]
# Defaults to .github/workflows/nightly.yml relative to the repository
# root inferred from this script's own location.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
workflow_file="${1:-$repo_root/.github/workflows/nightly.yml}"

if [[ ! -f "$workflow_file" ]]; then
  echo "Workflow file not found: $workflow_file" >&2
  exit 1
fi

failures=0

for mutable_pattern in '|| github.ref }}' '|| github.head_ref' '|| github.ref_name'; do
  if grep -F -- "$mutable_pattern" "$workflow_file" > /dev/null; then
    echo "FAIL: '$workflow_file' uses mutable ref expression '$mutable_pattern' where an exact-head checkout must use github.sha." >&2
    failures=$((failures + 1))
  fi
done

# Every checkout step that distinguishes a scheduled run from a manual
# dispatch (identified by the literal "github.event_name == 'schedule'"
# condition) must resolve its manual-dispatch branch to '|| github.sha
# }}' -- not just one such step, all of them.
schedule_dispatch_lines="$(grep -cF -- "github.event_name == 'schedule'" "$workflow_file" || true)"
sha_lines="$(grep -cF -- '|| github.sha }}' "$workflow_file" || true)"

if [[ "$schedule_dispatch_lines" -eq 0 ]]; then
  echo "No schedule/dispatch checkout ref expression found in '$workflow_file'." >&2
  echo "Refusing to pass: this check expects at least one such expression to exist." >&2
  exit 1
fi

if [[ "$sha_lines" -ne "$schedule_dispatch_lines" ]]; then
  echo "FAIL: '$workflow_file' has $schedule_dispatch_lines schedule/dispatch checkout ref expression(s) but only $sha_lines use '|| github.sha }}' for the manual-dispatch path." >&2
  failures=$((failures + 1))
fi

if [[ "$failures" -ne 0 ]]; then
  echo "Refusing to pass: $failures exact-head checkout violation(s) found in '$workflow_file'." >&2
  exit 1
fi

echo "All $schedule_dispatch_lines schedule/dispatch checkout ref expression(s) in '$workflow_file' use the immutable github.sha for manual dispatch."
