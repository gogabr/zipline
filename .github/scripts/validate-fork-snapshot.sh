#!/usr/bin/env bash
# Checks that a local Maven repository built by a fork pull request holds only a snapshot of our
# own group before it is uploaded with a write token. Everything in it, including the build that
# produced it, is controlled by the fork, so this is a structural check, not a code review.
#
# Usage: validate-fork-snapshot.sh <local-maven-repo-dir> <group-path> <head-sha>
# Prints the snapshot version on success.
set -euo pipefail

repo_dir=$1
group_path=$2
head_sha=$3

fail() {
  echo "Rejected: $*" >&2
  exit 1
}

cd "$repo_dir"

# Names end up in upload URLs: keep them to a conservative character set.
name='[A-Za-z0-9._-]+'

special=$(find . ! -type f ! -type d -print)
[ -z "$special" ] || fail "not regular files: $special"

files=$(find . -type f | sed 's|^\./||' | sort)
[ -n "$files" ] || fail "empty repository"

versions=$(
  while read -r file; do
    if [[ $file =~ ^$group_path/$name/maven-metadata\.xml(\.[a-z0-9]+)?$ ]]; then
      continue
    elif [[ $file =~ ^$group_path/$name/($name)/$name$ ]]; then
      echo "${BASH_REMATCH[1]}"
    else
      fail "unexpected path: $file"
    fi
  done <<< "$files" | sort -u
)

[ "$(wc -l <<< "$versions")" -eq 1 ] || fail "expected one version, found: $(tr '\n' ' ' <<< "$versions")"
version=$versions

# The build names PR snapshots `<version>-<head sha>-SNAPSHOT`; anything else could pass for a
# release or for another PR's snapshot.
[[ $version =~ ^[0-9A-Za-z.]+-${head_sha:0:7}-SNAPSHOT$ ]] \
  || fail "version $version is not a snapshot of ${head_sha:0:7}"

# Metadata must not advertise any other version.
while read -r metadata; do
  others=$(grep -oE '<(version|latest|release)>[^<]*</' "$metadata" \
    | sed -E 's/<[a-z]+>(.*)<\//\1/' | grep -vxF "$version" || true)
  [ -z "$others" ] || fail "$metadata references other versions: $others"
done < <(find . -type f -name 'maven-metadata.xml')

echo "$version"
