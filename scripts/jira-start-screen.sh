#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

echo "Starting Jira in a detached screen session named 'jira'..."

# Stop any previous screen session named jira
if screen -ls 2>/dev/null | grep -q "\\.jira"; then
  screen -S jira -X quit || true
  sleep 1
fi

# Fix common broken state: partially exploded jira webapp (missing JARs)
rm -rf target/container/tomcat9x/cargo-jira-home/webapps/jira 2>/dev/null || true

screen -dmS jira bash -lc "cd \"${ROOT_DIR}\" && atlas-run"

echo "Screen session: jira"
echo "Attach: screen -r jira"
echo "Stop:   screen -S jira -X quit"
echo "URL:    http://localhost:2990/jira"
