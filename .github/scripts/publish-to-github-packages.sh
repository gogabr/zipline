#!/usr/bin/env bash
# Uploads a local Maven repository (produced by publishAllPublicationsToTestMavenRepository) to
# GitHub Packages as-is, so the published snapshot is exactly what the build job produced.
#
# Usage: publish-to-github-packages.sh <local-maven-repo-dir>
# Env: GITHUB_ACTOR, GITHUB_TOKEN (with packages:write), GITHUB_REPOSITORY.
set -euo pipefail

repo_dir=$1
export REPO_URL="https://maven.pkg.github.com/${GITHUB_REPOSITORY}"

upload() {
  curl --fail-with-body --silent --show-error --retry 3 \
    --user "$GITHUB_ACTOR:$GITHUB_TOKEN" \
    --upload-file "$1" "$REPO_URL/${1#./}"
}
export -f upload

cd "$repo_dir"

# Artifacts and checksums first.
find . -type f ! -name 'maven-metadata.xml*' -print0 \
  | xargs -0 -n 1 -P 8 bash -c 'upload "$0"'

# maven-metadata.xml last, deepest (version-level) first, so consumers never resolve a snapshot
# whose files are not uploaded yet.
find . -type f -name 'maven-metadata.xml*' \
  | awk -F/ '{ print NF "\t" $0 }' | sort -rn | cut -f 2- \
  | while read -r file; do upload "$file"; done

echo "Published $(find . -name '*.pom' | wc -l | tr -d ' ') modules to $REPO_URL"
