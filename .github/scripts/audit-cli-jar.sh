#!/usr/bin/env bash
set -euo pipefail

jar_file="${1:?Usage: audit-cli-jar.sh <jar> <expected-version>}"
expected_version="${2:?Usage: audit-cli-jar.sh <jar> <expected-version>}"

if [[ ! -s "$jar_file" ]]; then
  echo "CLI JAR does not exist or is empty: $jar_file" >&2
  exit 1
fi

jar_file="$(readlink -f "$jar_file")"
entries="$(jar tf "$jar_file")"

require_entry() {
  local entry="$1"
  if ! grep -Fxq "$entry" <<<"$entries"; then
    echo "Required JAR entry is missing: $entry" >&2
    exit 1
  fi
}

require_entry "META-INF/MANIFEST.MF"
require_entry "META-INF/LICENSE"
require_entry "META-INF/third-party/THIRD-PARTY.txt"
require_entry "META-INF/third-party/licenses/slf4j-2.0.18-LICENSE.txt"
require_entry "META-INF/services/io.webagent4j.browser.IBrowserProvider"

if grep -Eiq '^META-INF/.*\.(SF|RSA|DSA)$' <<<"$entries"; then
  echo "Invalid dependency signature metadata remains in the shaded JAR." >&2
  exit 1
fi

duplicates="$(
  printf '%s\n' "$entries" \
    | grep -v '/$' \
    | sort \
    | uniq -d
)"
if [[ -n "$duplicates" ]]; then
  echo "Duplicate file entries found in shaded JAR:" >&2
  printf '%s\n' "$duplicates" >&2
  exit 1
fi

if grep -Eq '(^/|(^|/)\.\.(/|$))' <<<"$entries"; then
  echo "Unsafe archive path found in shaded JAR." >&2
  exit 1
fi

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

if ! (
  cd "$tmp_dir"
  jar xf "$jar_file"
); then
  echo "Failed to extract CLI JAR for inspection: $jar_file" >&2
  exit 1
fi

manifest_unix="$(tr -d '\r' < "$tmp_dir/META-INF/MANIFEST.MF")"
if ! grep -Eq '^Main-Class: io\.webagent4j\.cli\.WebAgent4jCli$' <<<"$manifest_unix"; then
  echo "Expected CLI Main-Class (io.webagent4j.cli.WebAgent4jCli) is missing from META-INF/MANIFEST.MF." >&2
  exit 1
fi

service_provider_file="$tmp_dir/META-INF/services/io.webagent4j.browser.IBrowserProvider"
if ! grep -Fxq \
  "io.webagent4j.browser.playwright.PlaywrightBrowserProvider" \
  "$service_provider_file"; then
  echo "Expected PlaywrightBrowserProvider entry is missing from META-INF/services/io.webagent4j.browser.IBrowserProvider." >&2
  exit 1
fi

if [[ ! -s "$tmp_dir/META-INF/third-party/THIRD-PARTY.txt" ]]; then
  echo "THIRD-PARTY.txt is empty." >&2
  exit 1
fi

slf4j_license="$tmp_dir/META-INF/third-party/licenses/slf4j-2.0.18-LICENSE.txt"

if [[ ! -s "$slf4j_license" ]]; then
  echo "Bundled SLF4J license is empty." >&2
  exit 1
fi

if ! grep -Fq "Copyright (c) 2004-2025 QOS.CH Sarl (Switzerland)" "$slf4j_license"; then
  echo "Bundled SLF4J license does not match the curated 2.0.18 license text." >&2
  exit 1
fi

if ! grep -Fq "Permission is hereby granted" "$slf4j_license"; then
  echo "Bundled SLF4J license is missing the MIT permission grant." >&2
  exit 1
fi

actual_version="$(java -jar "$jar_file" version | tr -d '\r\n')"
if [[ "$actual_version" != "$expected_version" ]]; then
  echo "CLI version '$actual_version' does not match '$expected_version'." >&2
  exit 1
fi

if ! java -jar "$jar_file" --help >/dev/null; then
  echo "CLI JAR failed to run '--help'." >&2
  exit 1
fi

echo "Shaded CLI JAR audit passed: $jar_file"
