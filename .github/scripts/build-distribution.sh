#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

version="$(
  ./mvnw \
    --batch-mode \
    --no-transfer-progress \
    -q \
    -Dstyle.color=never \
    -DforceStdout \
    help:evaluate \
    -Dexpression=project.version
)"
version="$(printf '%s' "$version" | tr -d '\r\n')"

if [[ -z "$version" ]]; then
  echo "Unable to resolve Maven project version." >&2
  exit 1
fi

./mvnw \
  --batch-mode \
  --no-transfer-progress \
  -Pdistribution \
  -DskipTests \
  clean package

jar_file="webagent4j-cli/target/webagent4j-cli-${version}.jar"

bash .github/scripts/audit-cli-jar.sh \
  "$jar_file" \
  "$version"

output_dir="target/distribution"
rm -rf "$output_dir"
mkdir -p "$output_dir"

cp "$jar_file" "$output_dir/"

(
  cd "$output_dir"
  sha256sum \
    "webagent4j-cli-${version}.jar" \
    > "webagent4j-cli-${version}.jar.sha256"
)

echo "Distribution ready:"
echo "  $output_dir/webagent4j-cli-${version}.jar"
echo "  $output_dir/webagent4j-cli-${version}.jar.sha256"
