#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat >&2 <<'EOF'
Usage:
  publish-pages-state.sh latest <javadoc-dir>
  publish-pages-state.sh release <javadoc-dir> <version>
EOF
  exit 2
}

MODE="${1:-}"
JAVADOC_INPUT="${2:-}"
VERSION="${3:-}"

if [[ "$MODE" != "latest" && "$MODE" != "release" ]]; then
  usage
fi

if [[ -z "$JAVADOC_INPUT" ]]; then
  usage
fi

if [[ "$MODE" == "release" && -z "$VERSION" ]]; then
  usage
fi

if [[ "$MODE" == "release" && ! "$VERSION" =~ ^[0-9A-Za-z][0-9A-Za-z._-]*$ ]]; then
  echo "Invalid release version for Pages path: $VERSION" >&2
  exit 1
fi

ROOT="$(git rev-parse --show-toplevel)"

if [[ "$JAVADOC_INPUT" = /* ]]; then
  JAVADOC_DIR="$JAVADOC_INPUT"
else
  JAVADOC_DIR="$ROOT/$JAVADOC_INPUT"
fi

if [[ ! -s "$JAVADOC_DIR/index.html" ]]; then
  echo "Missing aggregate Javadoc index: $JAVADOC_DIR/index.html" >&2
  exit 1
fi

if [[ ! -s "$ROOT/docs-site/index.html" || ! -s "$ROOT/docs-site/styles.css" ]]; then
  echo "Missing docs-site root files." >&2
  exit 1
fi

TMP_PARENT="$(mktemp -d "${RUNNER_TEMP:-/tmp}/webagent4j-pages.XXXXXX")"
STATE_DIR="$TMP_PARENT/state"
DEPLOY_DIR="$ROOT/target/pages-deploy"
WORKTREE_ADDED=false

cleanup() {
  if [[ "$WORKTREE_ADDED" == "true" ]]; then
    git -C "$ROOT" worktree remove --force "$STATE_DIR" >/dev/null 2>&1 || true
  fi
  rm -rf "$TMP_PARENT"
}
trap cleanup EXIT

if git ls-remote --exit-code --heads origin gh-pages >/dev/null 2>&1; then
  git fetch --no-tags origin \
    +refs/heads/gh-pages:refs/remotes/origin/gh-pages
  git -C "$ROOT" worktree add --detach "$STATE_DIR" origin/gh-pages
else
  git -C "$ROOT" worktree add --detach "$STATE_DIR" HEAD
  (
    cd "$STATE_DIR"
    git rm -rf . >/dev/null
  )
fi
WORKTREE_ADDED=true

git -C "$STATE_DIR" config user.name "github-actions[bot]"
git -C "$STATE_DIR" config user.email \
  "41898282+github-actions[bot]@users.noreply.github.com"

mkdir -p "$STATE_DIR/api"
cp "$ROOT/docs-site/index.html" "$STATE_DIR/index.html"
cp "$ROOT/docs-site/styles.css" "$STATE_DIR/styles.css"
touch "$STATE_DIR/.nojekyll"

replace_tree() {
  local source="$1"
  local destination="$2"

  rm -rf "$destination"
  mkdir -p "$destination"
  cp -a "$source/." "$destination/"
}

if [[ "$MODE" == "latest" ]]; then
  replace_tree "$JAVADOC_DIR" "$STATE_DIR/api/latest"
  COMMIT_MESSAGE="docs(pages): update latest API"
else
  RELEASE_DIR="$STATE_DIR/api/$VERSION"

  if [[ -e "$RELEASE_DIR" ]]; then
    if ! diff -qr "$RELEASE_DIR" "$JAVADOC_DIR" >/dev/null; then
      echo "Refusing to replace immutable API documentation for $VERSION." >&2
      echo "Existing gh-pages content differs from the release Javadoc." >&2
      exit 1
    fi
  else
    mkdir -p "$RELEASE_DIR"
    cp -a "$JAVADOC_DIR/." "$RELEASE_DIR/"
  fi

  replace_tree "$RELEASE_DIR" "$STATE_DIR/api/latest"
  COMMIT_MESSAGE="docs(pages): publish API $VERSION"
fi

git -C "$STATE_DIR" add -A

if ! git -C "$STATE_DIR" diff --cached --quiet; then
  git -C "$STATE_DIR" commit -m "$COMMIT_MESSAGE"
  git -C "$STATE_DIR" push origin HEAD:refs/heads/gh-pages
else
  echo "Pages state is already up to date; no gh-pages commit required."
fi

rm -rf "$DEPLOY_DIR"
mkdir -p "$DEPLOY_DIR"
cp -a "$STATE_DIR/." "$DEPLOY_DIR/"
rm -rf "$DEPLOY_DIR/.git"

test -s "$DEPLOY_DIR/index.html"
test -s "$DEPLOY_DIR/styles.css"
test -s "$DEPLOY_DIR/api/latest/index.html"

if [[ "$MODE" == "release" ]]; then
  test -s "$DEPLOY_DIR/api/$VERSION/index.html"
fi
