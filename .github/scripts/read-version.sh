#!/usr/bin/env bash
# Prints the version of the zipline module in a local Maven repository built by
# publishAllPublicationsToTestMavenRepository. build.gradle.kts assembles the version, so CI reads
# it back from what was built.
#
# Usage: read-version.sh <local-maven-repo-dir>
set -euo pipefail

basename "$(dirname "$(ls "$1"/io/github/tret9/zipline/*/*.pom)")"
