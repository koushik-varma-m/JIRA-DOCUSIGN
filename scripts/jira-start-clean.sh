#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

echo "Cleaning partially-exploded Jira webapp (fixes missing JAR issues)..."
rm -rf target/container/tomcat9x/cargo-jira-home/webapps/jira 2>/dev/null || true

echo "Starting Jira (foreground). Stop with Ctrl+C."
exec atlas-run

