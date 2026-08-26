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
require_entry "META-INF/third-party/licenses.xml"
require_entry "META-INF/services/io.webagent4j.browser.IBrowserProvider"

if ! grep -Eq '^META-INF/third-party/licenses/[^/]+$' <<<"$entries"; then
  echo "No downloaded third-party license text was bundled." >&2
  exit 1
fi

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

(
  cd "$tmp_dir"
  jar xf "$jar_file"
)

grep -Eq '^Main-Class: io\.webagent4j\.cli\.WebAgent4jCli\r?$' \
  "$tmp_dir/META-INF/MANIFEST.MF"

grep -Fxq \
  "io.webagent4j.browser.playwright.PlaywrightBrowserProvider" \
  "$tmp_dir/META-INF/services/io.webagent4j.browser.IBrowserProvider"

if [[ ! -s "$tmp_dir/META-INF/third-party/THIRD-PARTY.txt" ]]; then
  echo "THIRD-PARTY.txt is empty." >&2
  exit 1
fi

actual_version="$(java -jar "$jar_file" version | tr -d '\r\n')"
if [[ "$actual_version" != "$expected_version" ]]; then
  echo "CLI version '$actual_version' does not match '$expected_version'." >&2
  exit 1
fi

java -jar "$jar_file" --help >/dev/null

echo "Shaded CLI JAR audit passed: $jar_file"
