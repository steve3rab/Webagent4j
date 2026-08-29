#!/usr/bin/env bash
# Seeds a fresh, isolated Maven local repository from an already-restored
# Maven dependency cache, so the API Compatibility workflow's isolated
# repository (-Dmaven.repo.local=$RUNNER_TEMP/api-compat-m2) can reuse
# trusted THIRD-PARTY artifacts that setup-java's `cache: maven` already
# downloaded, instead of re-hitting Maven Central for every third-party BOM
# and dependency on every Revapi run.
#
# This intentionally does NOT relax isolation for io.webagent4j artifacts:
# the destination is reset to empty, then any io/webagent4j/** content is
# stripped from the copy before this script returns, so the isolated
# repository can never inherit a stale WebAgent4j artifact built by an
# earlier run. The caller (the workflow) still asserts this invariant
# itself immediately before the baseline install -- this script does not
# replace that check, it is the mechanism the check verifies.
#
# *.lastUpdated marker files are also stripped: they record an earlier
# failed/incomplete download attempt against the *source* repository and
# must not carry a stale "this artifact is unavailable" marker into a
# fresh compatibility run.
#
# A missing or empty source repository is a normal Maven cache miss (e.g.
# the very first run, or a cache eviction) and is not an error: the
# destination is simply left present and empty, ready for Maven to
# populate from scratch. Only an actual filesystem/copy failure is
# treated as a hard error.
#
# Usage: seed-api-compat-maven-repo.sh <source_repo> <destination_repo>
set -euo pipefail

if [[ $# -ne 2 || -z "${1:-}" || -z "${2:-}" ]]; then
  echo "Usage: $0 <source_repo> <destination_repo>" >&2
  exit 1
fi

source_repo="$1"
destination_repo="$2"

# Start the destination from a deterministic clean state on every
# invocation -- any pre-existing content (including a stale io/webagent4j
# left over from an earlier run against the same path) must not survive.
rm -rf "$destination_repo"
mkdir -p "$destination_repo"

if [[ ! -d "$source_repo" ]] || [[ -z "$(find "$source_repo" -mindepth 1 -print -quit 2>/dev/null)" ]]; then
  echo "Maven cache source '$source_repo' is missing or empty; treating this as a normal cache miss." >&2
  echo "Destination '$destination_repo' is present and empty; Maven will populate it from scratch." >&2
  exit 0
fi

if ! cp -a "$source_repo/." "$destination_repo/"; then
  echo "Failed to copy Maven cache content from '$source_repo' to '$destination_repo'." >&2
  exit 1
fi

if [[ -e "$destination_repo/io/webagent4j" ]]; then
  if ! rm -rf "$destination_repo/io/webagent4j"; then
    echo "Failed to remove io/webagent4j from the seeded repository at '$destination_repo'." >&2
    exit 1
  fi
fi

if ! find "$destination_repo" -type f -name '*.lastUpdated' -delete; then
  echo "Failed to remove *.lastUpdated marker files from the seeded repository at '$destination_repo'." >&2
  exit 1
fi

echo "Seeded '$destination_repo' from cached third-party Maven artifacts in '$source_repo' (io/webagent4j and *.lastUpdated excluded)." >&2
