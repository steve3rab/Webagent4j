#!/usr/bin/env sh
set -eu

git config core.hooksPath .githooks
chmod +x mvnw .githooks/pre-commit .githooks/pre-push scripts/bootstrap.sh
./mvnw --version
echo "WebAgent4J development hooks are enabled."
