#!/usr/bin/env bash
# Regression tests for .github/scripts/audit-cli-jar.sh.
#
# These tests build small, real JAR files with the `jar` and `javac` tools
# (never mocked or hand-crafted byte strings) and run the actual audit
# script against them, asserting both the exit code and the diagnostic
# message. This exists because the Release workflow failure this suite
# guards against was caused by a manifest CRLF line ending defeating a
# `grep -E '...\r?$'` pattern silently under `set -e`: Maven's own build
# never exercises audit-cli-jar.sh, so nothing caught it before the tag
# was pushed.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
audit_script="$script_dir/../audit-cli-jar.sh"
repo_root="$(cd "$script_dir/../../.." && pwd)"

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

failures=0
cases_run=0

# Compile one fixture Main class shared by every synthetic JAR. It is
# named and packaged as io.webagent4j.cli.WebAgent4jCli -- the exact class
# audit-cli-jar.sh requires -- and answers `version` / `--help` for real,
# so the audit script's `java -jar ... version` / `--help` invocations
# exercise a genuinely runnable JAR rather than a stub.
fixture_pkg_dir="$work_dir/src/io/webagent4j/cli"
fixture_classes="$work_dir/classes"
mkdir -p "$fixture_pkg_dir" "$fixture_classes"
cat > "$fixture_pkg_dir/WebAgent4jCli.java" <<'EOF'
package io.webagent4j.cli;

public final class WebAgent4jCli {
    private WebAgent4jCli() {
    }

    public static void main(String[] args) {
        if (args.length > 0 && "version".equals(args[0])) {
            System.out.println("9.9.9");
            return;
        }
        System.out.println("Usage: fixture [--help]");
    }
}
EOF
javac -d "$fixture_classes" "$fixture_pkg_dir/WebAgent4jCli.java"
fixture_class_rel="io/webagent4j/cli/WebAgent4jCli.class"

# stage_valid_fixture <stage_dir>
# Populates a staging directory with every resource audit-cli-jar.sh
# requires, so callers only need to remove or corrupt the one thing their
# test case is about.
stage_valid_fixture() {
  local stage="$1"
  rm -rf "$stage"
  mkdir -p \
    "$stage/META-INF/third-party/licenses" \
    "$stage/META-INF/services" \
    "$stage/$(dirname "$fixture_class_rel")"

  cp "$fixture_classes/$fixture_class_rel" "$stage/$fixture_class_rel"

  printf 'WebAgent4J fixture license text.\n' > "$stage/META-INF/LICENSE"
  printf 'fixture-dep 1.0.0 -- MIT\n' > "$stage/META-INF/third-party/THIRD-PARTY.txt"
  cat > "$stage/META-INF/third-party/licenses/slf4j-2.0.18-LICENSE.txt" <<'EOF'
MIT License

Copyright (c) 2004-2025 QOS.CH Sarl (Switzerland)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software.
EOF
  printf 'io.webagent4j.browser.playwright.PlaywrightBrowserProvider\n' \
    > "$stage/META-INF/services/io.webagent4j.browser.IBrowserProvider"
}

# build_jar <stage_dir> <manifest_main_class_line> <out_jar>
# Packages the stage directory into a real JAR using the `jar` tool, which
# writes MANIFEST.MF with CRLF line endings exactly as Maven's
# maven-jar-plugin does -- this is what makes the fixture faithful to the
# real bug instead of an artificial stand-in for it.
build_jar() {
  local stage="$1" main_class_line="$2" out_jar="$3"
  local manifest_in="$work_dir/manifest-input.mf"
  if [[ -n "$main_class_line" ]]; then
    printf '%s\n' "$main_class_line" > "$manifest_in"
  else
    : > "$manifest_in"
  fi
  (cd "$stage" && jar cfm "$out_jar" "$manifest_in" .)
}

# assert_case <name> <expected_exit> <expected_message_substring_or_empty> -- <audit args...>
assert_case() {
  local name="$1" expected_exit="$2" expected_message="$3"
  shift 3
  [[ "$1" == "--" ]]
  shift
  cases_run=$((cases_run + 1))

  local output actual_exit
  set +e
  output="$(bash "$audit_script" "$@" 2>&1)"
  actual_exit=$?
  set -e

  local ok=1
  if [[ "$actual_exit" -ne "$expected_exit" ]]; then
    echo "FAIL: $name -- expected exit $expected_exit, got $actual_exit" >&2
    ok=0
  fi
  if [[ -n "$expected_message" ]] && [[ "$output" != *"$expected_message"* ]]; then
    echo "FAIL: $name -- expected diagnostic containing: $expected_message" >&2
    echo "  actual output:" >&2
    printf '%s\n' "$output" | sed 's/^/    /' >&2
    ok=0
  fi

  if [[ "$ok" -eq 1 ]]; then
    echo "PASS: $name"
  else
    failures=$((failures + 1))
  fi
}

# --- Case 1: a genuinely valid JAR (real CRLF manifest) is accepted -------
valid_stage="$work_dir/stage-valid"
valid_jar="$work_dir/valid.jar"
stage_valid_fixture "$valid_stage"
build_jar "$valid_stage" "Main-Class: io.webagent4j.cli.WebAgent4jCli" "$valid_jar"
assert_case \
  "valid JAR with expected manifest, Main-Class and service provider is accepted" \
  0 "Shaded CLI JAR audit passed" \
  -- "$valid_jar" "9.9.9"

# --- Case 2: missing Main-Class (the exact CRLF regression) ---------------
# A manifest with no Main-Class at all still has real CRLF line endings
# from the `jar` tool, so this pins the exact failure mode: a manifest
# that legitimately uses CRLF must still be diagnosed clearly, never
# silently swallowed by `set -e`.
no_main_class_stage="$work_dir/stage-no-main-class"
no_main_class_jar="$work_dir/no-main-class.jar"
stage_valid_fixture "$no_main_class_stage"
build_jar "$no_main_class_stage" "" "$no_main_class_jar"
assert_case \
  "manifest without the expected Main-Class fails with an explicit diagnostic" \
  1 "Expected CLI Main-Class" \
  -- "$no_main_class_jar" "9.9.9"

# --- Case 3: wrong Main-Class value ----------------------------------------
wrong_main_class_stage="$work_dir/stage-wrong-main-class"
wrong_main_class_jar="$work_dir/wrong-main-class.jar"
stage_valid_fixture "$wrong_main_class_stage"
build_jar "$wrong_main_class_stage" "Main-Class: SomeOtherClass" "$wrong_main_class_jar"
assert_case \
  "manifest with an unexpected Main-Class value fails with an explicit diagnostic" \
  1 "Expected CLI Main-Class" \
  -- "$wrong_main_class_jar" "9.9.9"

# --- Case 4: missing Playwright service provider ---------------------------
no_provider_stage="$work_dir/stage-no-provider"
no_provider_jar="$work_dir/no-provider.jar"
stage_valid_fixture "$no_provider_stage"
printf 'not.the.expected.Provider\n' \
  > "$no_provider_stage/META-INF/services/io.webagent4j.browser.IBrowserProvider"
build_jar "$no_provider_stage" "Main-Class: io.webagent4j.cli.WebAgent4jCli" "$no_provider_jar"
assert_case \
  "missing PlaywrightBrowserProvider service entry fails with an explicit diagnostic" \
  1 "PlaywrightBrowserProvider" \
  -- "$no_provider_jar" "9.9.9"

# --- Case 5: a required top-level entry is missing entirely ----------------
no_license_stage="$work_dir/stage-no-license"
no_license_jar="$work_dir/no-license.jar"
stage_valid_fixture "$no_license_stage"
rm "$no_license_stage/META-INF/LICENSE"
build_jar "$no_license_stage" "Main-Class: io.webagent4j.cli.WebAgent4jCli" "$no_license_jar"
assert_case \
  "missing META-INF/LICENSE entry fails with an explicit diagnostic" \
  1 "Required JAR entry is missing: META-INF/LICENSE" \
  -- "$no_license_jar" "9.9.9"

# --- Case 6: leftover dependency signature metadata is rejected ------------
signed_stage="$work_dir/stage-signed"
signed_jar="$work_dir/signed.jar"
stage_valid_fixture "$signed_stage"
mkdir -p "$signed_stage/META-INF"
printf 'not a real signature\n' > "$signed_stage/META-INF/DEPENDENCY.SF"
build_jar "$signed_stage" "Main-Class: io.webagent4j.cli.WebAgent4jCli" "$signed_jar"
assert_case \
  "leftover .SF signature metadata fails with an explicit diagnostic" \
  1 "Invalid dependency signature metadata" \
  -- "$signed_jar" "9.9.9"

# --- Case 7: version mismatch is reported ----------------------------------
assert_case \
  "CLI version mismatch fails with an explicit diagnostic" \
  1 "does not match" \
  -- "$valid_jar" "1.2.3"

# --- Case 8: the real distribution JAR, when present, passes audit --------
# When run after .github/scripts/build-distribution.sh has produced the
# real shaded CLI JAR, audit it directly instead of only auditing
# synthetic fixtures. Skipped (not failed) when no build has been run,
# so this file stays runnable standalone during development.
distribution_jar="$(find "$repo_root/webagent4j-cli/target" -maxdepth 1 -name 'webagent4j-cli-*.jar' -print -quit 2>/dev/null || true)"
if [[ -n "$distribution_jar" ]]; then
  version="$(basename "$distribution_jar" .jar)"
  version="${version#webagent4j-cli-}"
  assert_case \
    "real distribution CLI JAR (webagent4j-cli profile) passes audit" \
    0 "Shaded CLI JAR audit passed" \
    -- "$distribution_jar" "$version"
else
  echo "SKIP: real distribution CLI JAR audit -- no built JAR found under webagent4j-cli/target (run .github/scripts/build-distribution.sh first)"
fi

echo
echo "$cases_run assertion case(s) run, $failures failure(s)."
if [[ "$failures" -ne 0 ]]; then
  exit 1
fi
