#!/usr/bin/env bash
# Signs a local Maven repository (produced by publishAllPublicationsToTestMavenRepository) and
# uploads it to Maven Central through the Central Portal Publisher API. The release is published
# automatically once Central validates it.
#
# Usage: publish-to-maven-central.sh <local-maven-repo-dir> <deployment-name>
# Env: SONATYPE_CENTRAL_USERNAME, SONATYPE_CENTRAL_PASSWORD (Central Portal user token),
#      GPG_SECRET_KEY (ASCII-armored secret key), GPG_SECRET_PASSPHRASE.
set -euo pipefail

repo_dir=$(cd "$1" && pwd)
deployment_name=$2
work_dir=$(mktemp -d)
bundle_dir="$work_dir/bundle"
bundle_zip="$work_dir/bundle.zip"

# Central doesn't need maven-metadata.xml; ship artifacts, checksums and signatures only.
mkdir "$bundle_dir"
rsync -a --exclude 'maven-metadata.xml*' "$repo_dir/" "$bundle_dir/"

export GNUPGHOME="$work_dir/gnupg"
mkdir -m 700 "$GNUPGHOME"
gpg --batch --quiet --import <<<"$GPG_SECRET_KEY"
printf '%s' "${GPG_SECRET_PASSPHRASE:-}" >"$work_dir/passphrase"

find "$bundle_dir" -type f \
  ! -name '*.md5' ! -name '*.sha1' ! -name '*.sha256' ! -name '*.sha512' ! -name '*.asc' -print0 \
  | xargs -0 -n 1 gpg --batch --yes --pinentry-mode loopback \
    --passphrase-file "$work_dir/passphrase" --armor --detach-sign
rm "$work_dir/passphrase"

(cd "$bundle_dir" && zip -qr "$bundle_zip" .)

auth="Authorization: Bearer $(printf '%s:%s' "$SONATYPE_CENTRAL_USERNAME" "$SONATYPE_CENTRAL_PASSWORD" | base64 | tr -d '\n')"
api=https://central.sonatype.com/api/v1/publisher

deployment_id=$(curl --fail-with-body --silent --show-error \
  --header "$auth" \
  --form "bundle=@$bundle_zip" \
  "$api/upload?name=$(jq -rn --arg v "$deployment_name" '$v|@uri')&publishingType=AUTOMATIC")
echo "Uploaded deployment $deployment_id"

# Validation usually takes a few minutes. PUBLISHING means it passed validation and is being
# released; reaching PUBLISHED can take much longer and doesn't need to block the job.
for _ in $(seq 1 90); do
  status=$(curl --fail-with-body --silent --show-error --request POST \
    --header "$auth" "$api/status?id=$deployment_id")
  state=$(jq -r .deploymentState <<<"$status")
  echo "Deployment state: $state"
  case "$state" in
    PUBLISHING | PUBLISHED) exit 0 ;;
    FAILED)
      jq . <<<"$status"
      exit 1
      ;;
  esac
  sleep 20
done

echo "Timed out waiting for deployment $deployment_id; check https://central.sonatype.com/publishing/deployments" >&2
exit 1
