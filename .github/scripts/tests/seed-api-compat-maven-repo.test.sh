#!/usr/bin/env bash
# Regression tests for .github/scripts/seed-api-compat-maven-repo.sh.
#
# Every case operates on temporary synthetic Maven repository directories
# under a throwaway work directory. No network access is made and no real
# Maven cache (~/.m2/repository) is ever touched.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
seed_script="$script_dir/../seed-api-compat-maven-repo.sh"

failures=0
cases_run=0

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

# run_seed <source_repo> <destination_repo>
# Invokes the script under test and prints its exit code.
run_seed() {
  local source_repo="$1" destination_repo="$2"
  local exit_code
  set +e
  bash "$seed_script" "$source_repo" "$destination_repo" >/dev/null 2>&1
  exit_code=$?
  set -e
  echo "$exit_code"
}

# seed_file <repo> <repo_relative_path>
# Creates a fake Maven artifact file at the given path inside repo.
seed_file() {
  local repo="$1" rel_path="$2"
  mkdir -p "$(dirname "$repo/$rel_path")"
  printf 'fake artifact content' > "$repo/$rel_path"
}

assert_exit_code() {
  local name="$1" expected="$2" actual="$3"
  cases_run=$((cases_run + 1))
  if [[ "$actual" -eq "$expected" ]]; then
    echo "PASS: $name"
  else
    echo "FAIL: $name -- expected exit $expected, got $actual" >&2
    failures=$((failures + 1))
  fi
}

assert_path_exists() {
  local name="$1" path="$2"
  cases_run=$((cases_run + 1))
  if [[ -e "$path" ]]; then
    echo "PASS: $name"
  else
    echo "FAIL: $name -- expected path to exist: $path" >&2
    failures=$((failures + 1))
  fi
}

assert_path_absent() {
  local name="$1" path="$2"
  cases_run=$((cases_run + 1))
  if [[ ! -e "$path" ]]; then
    echo "PASS: $name"
  else
    echo "FAIL: $name -- expected path to be absent: $path" >&2
    failures=$((failures + 1))
  fi
}

assert_dir_empty() {
  local name="$1" dir="$2"
  cases_run=$((cases_run + 1))
  if [[ -z "$(find "$dir" -mindepth 1 -print -quit 2>/dev/null)" ]]; then
    echo "PASS: $name"
  else
    echo "FAIL: $name -- expected '$dir' to be empty" >&2
    failures=$((failures + 1))
  fi
}

# --- A: third-party cached artifact is copied ------------------------------

source_a="$work_dir/a-source"
dest_a="$work_dir/a-dest"
mkdir -p "$source_a"
seed_file "$source_a" "org/junit/junit-bom/5.13.4/junit-bom-5.13.4.pom"
exit_code="$(run_seed "$source_a" "$dest_a")"
assert_exit_code "A: seed exits 0 for a normal third-party cache" 0 "$exit_code"
assert_path_exists "A: third-party cached artifact is copied" "$dest_a/org/junit/junit-bom/5.13.4/junit-bom-5.13.4.pom"

# --- B: WebAgent4j (current, SNAPSHOT) artifact is excluded ----------------

source_b="$work_dir/b-source"
dest_b="$work_dir/b-dest"
mkdir -p "$source_b"
seed_file "$source_b" "io/webagent4j/webagent4j-common/1.1.0-SNAPSHOT/webagent4j-common-1.1.0-SNAPSHOT.jar"
seed_file "$source_b" "org/junit/junit-bom/5.13.4/junit-bom-5.13.4.pom"
exit_code="$(run_seed "$source_b" "$dest_b")"
assert_exit_code "B: seed exits 0 when source contains a SNAPSHOT WebAgent4j artifact" 0 "$exit_code"
assert_path_absent "B: WebAgent4j artifact (current, SNAPSHOT) is excluded" "$dest_b/io/webagent4j"
assert_path_exists "B: a third-party artifact alongside it is still copied" "$dest_b/org/junit/junit-bom/5.13.4/junit-bom-5.13.4.pom"

# --- C: stable WebAgent4j artifact (baseline, 1.0.0) is also excluded ------

source_c="$work_dir/c-source"
dest_c="$work_dir/c-dest"
mkdir -p "$source_c"
seed_file "$source_c" "io/webagent4j/webagent4j-common/1.0.0/webagent4j-common-1.0.0.jar"
exit_code="$(run_seed "$source_c" "$dest_c")"
assert_exit_code "C: seed exits 0 when source contains a stable WebAgent4j artifact" 0 "$exit_code"
assert_path_absent "C: stable WebAgent4j artifact (1.0.0) is also excluded" "$dest_c/io/webagent4j"

# --- D: .lastUpdated marker files are excluded -----------------------------

source_d="$work_dir/d-source"
dest_d="$work_dir/d-dest"
mkdir -p "$source_d"
seed_file "$source_d" "org/testcontainers/testcontainers-bom/1.21.3/testcontainers-bom-1.21.3.pom"
seed_file "$source_d" "org/testcontainers/testcontainers-bom/1.21.3/testcontainers-bom-1.21.3.pom.lastUpdated"
exit_code="$(run_seed "$source_d" "$dest_d")"
assert_exit_code "D: seed exits 0 when source contains a .lastUpdated marker" 0 "$exit_code"
assert_path_exists "D: the real artifact next to a marker is still copied" "$dest_d/org/testcontainers/testcontainers-bom/1.21.3/testcontainers-bom-1.21.3.pom"
assert_path_absent "D: .lastUpdated marker files are excluded" "$dest_d/org/testcontainers/testcontainers-bom/1.21.3/testcontainers-bom-1.21.3.pom.lastUpdated"

# --- E: missing source repository -> empty destination, exit 0 ------------

source_e="$work_dir/e-source-does-not-exist"
dest_e="$work_dir/e-dest"
exit_code="$(run_seed "$source_e" "$dest_e")"
assert_exit_code "E: missing source repository still exits 0" 0 "$exit_code"
assert_path_exists "E: destination is created" "$dest_e"
assert_dir_empty "E: destination is empty" "$dest_e"

# --- F: empty source repository -> empty destination, exit 0 --------------

source_f="$work_dir/f-source-empty"
dest_f="$work_dir/f-dest"
mkdir -p "$source_f"
exit_code="$(run_seed "$source_f" "$dest_f")"
assert_exit_code "F: empty source repository still exits 0" 0 "$exit_code"
assert_path_exists "F: destination is created" "$dest_f"
assert_dir_empty "F: destination is empty" "$dest_f"

# --- G: pre-existing stale destination content is reset -------------------

source_g="$work_dir/g-source"
dest_g="$work_dir/g-dest"
mkdir -p "$source_g"
seed_file "$source_g" "org/junit/junit-bom/5.13.4/junit-bom-5.13.4.pom"
# Simulate a destination left over from an earlier invocation against this
# same path, containing exactly the kind of stale WebAgent4j artifact this
# script must never let survive into a fresh run.
seed_file "$dest_g" "io/webagent4j/webagent4j-common/1.0.0/webagent4j-common-1.0.0.jar"
seed_file "$dest_g" "io/webagent4j/webagent4j-common/1.1.0-SNAPSHOT/webagent4j-common-1.1.0-SNAPSHOT.jar"
exit_code="$(run_seed "$source_g" "$dest_g")"
assert_exit_code "G: seed exits 0 despite pre-existing stale destination content" 0 "$exit_code"
assert_path_absent "G: pre-existing stale WebAgent4j content does not survive the reset" "$dest_g/io/webagent4j"
assert_path_exists "G: the destination is repopulated from the source afterward" "$dest_g/org/junit/junit-bom/5.13.4/junit-bom-5.13.4.pom"

# --- H: normal third-party nested Maven metadata/artifacts remain usable --

source_h="$work_dir/h-source"
dest_h="$work_dir/h-dest"
mkdir -p "$source_h"
seed_file "$source_h" "org/junit/junit-bom/5.13.4/junit-bom-5.13.4.pom"
seed_file "$source_h" "org/junit/junit-bom/5.13.4/junit-bom-5.13.4.pom.sha1"
seed_file "$source_h" "org/junit/junit-bom/maven-metadata.xml"
seed_file "$source_h" "org/junit/junit-bom/maven-metadata-central.xml"
exit_code="$(run_seed "$source_h" "$dest_h")"
assert_exit_code "H: seed exits 0 for normal nested third-party metadata" 0 "$exit_code"
assert_path_exists "H: nested artifact pom is copied" "$dest_h/org/junit/junit-bom/5.13.4/junit-bom-5.13.4.pom"
assert_path_exists "H: nested artifact checksum is copied" "$dest_h/org/junit/junit-bom/5.13.4/junit-bom-5.13.4.pom.sha1"
assert_path_exists "H: repository-level maven-metadata.xml is copied" "$dest_h/org/junit/junit-bom/maven-metadata.xml"
assert_path_exists "H: mirror-specific maven-metadata-central.xml is copied" "$dest_h/org/junit/junit-bom/maven-metadata-central.xml"

# --- Argument validation ----------------------------------------------------

cases_run=$((cases_run + 1))
set +e
bash "$seed_script" >/dev/null 2>&1
missing_args_exit=$?
set -e
if [[ "$missing_args_exit" -ne 0 ]]; then
  echo "PASS: missing arguments are rejected"
else
  echo "FAIL: missing arguments are rejected -- expected non-zero exit" >&2
  failures=$((failures + 1))
fi

cases_run=$((cases_run + 1))
set +e
bash "$seed_script" "$work_dir/only-one-arg" >/dev/null 2>&1
one_arg_exit=$?
set -e
if [[ "$one_arg_exit" -ne 0 ]]; then
  echo "PASS: a single argument is rejected"
else
  echo "FAIL: a single argument is rejected -- expected non-zero exit" >&2
  failures=$((failures + 1))
fi

# --- Actual filesystem error is a hard failure, not a silent continue -----
# A destination whose parent path is itself a plain file (not a directory)
# can never be created; the script must propagate that failure rather than
# treating it like a benign empty-cache scenario.

source_err="$work_dir/err-source"
mkdir -p "$source_err"
seed_file "$source_err" "org/junit/junit-bom/5.13.4/junit-bom-5.13.4.pom"
blocked_parent="$work_dir/blocked-parent-is-a-file"
printf 'not a directory' > "$blocked_parent"
exit_code="$(run_seed "$source_err" "$blocked_parent/dest")"
assert_exit_code "a genuine filesystem error (destination parent is not a directory) fails, not silently" 1 "$exit_code"

echo
echo "$cases_run assertion case(s) run, $failures failure(s)."
if [[ "$failures" -ne 0 ]]; then
  exit 1
fi
