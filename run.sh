#!/usr/bin/env bash
set -euo pipefail

JAVA_HOME="${JAVA_HOME:-/Users/dreamlike/.sdkman/candidates/java/25-amzn}"
MVN="${MVN:-mvn}"
DIR="$(cd "$(dirname "$0")" && pwd)"

cd "$DIR"
JAVA_HOME="$JAVA_HOME" "$MVN" -q test
