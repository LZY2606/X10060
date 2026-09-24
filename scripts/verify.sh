#!/bin/sh
# Single verification entry point shared by local runs and CI.
# Any failing subcommand aborts the script with a non-zero exit code (set -e).
set -eu

cd "$(dirname "$0")/.."

mvn -q -DskipTests package
mvn -q test
