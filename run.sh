#!/usr/bin/env bash
set -euo pipefail

DEFAULT_JAVA_HOME="${HOME}/.sdkman/candidates/java/current"
JAVA_HOME="${JAVA_HOME:-$DEFAULT_JAVA_HOME}"
MVN="${MVN:-mvn}"
DIR="$(cd "$(dirname "$0")" && pwd)"

cd "$DIR"
JAVA_HOME="$JAVA_HOME" "$MVN" -q test
