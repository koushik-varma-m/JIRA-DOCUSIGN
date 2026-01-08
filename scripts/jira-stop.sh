#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

echo "Stopping screen session 'jira' (if running)"
screen -S jira -X quit 2>/dev/null || true
sleep 1

# Stop Tomcat started via scripts/jira-start-tomcat-screen.sh even if it never bound to port 2990.
CATALINA_BASE="${ROOT_DIR}/target/container/tomcat9x/cargo-jira-home"
TOMCAT_PIDS="$(pgrep -f \"-Dcatalina\\.base=${CATALINA_BASE}\" 2>/dev/null || true)"
if [[ -n "${TOMCAT_PIDS}" ]]; then
  echo "Stopping Tomcat (CATALINA_BASE match): ${TOMCAT_PIDS}"
  kill ${TOMCAT_PIDS} 2>/dev/null || true
  sleep 3
  TOMCAT_PIDS2="$(pgrep -f \"-Dcatalina\\.base=${CATALINA_BASE}\" 2>/dev/null || true)"
  if [[ -n "${TOMCAT_PIDS2}" ]]; then
    echo "Force killing Tomcat: ${TOMCAT_PIDS2}"
    kill -9 ${TOMCAT_PIDS2} 2>/dev/null || true
  fi
fi

if [[ -f jira.pid ]]; then
  PID="$(cat jira.pid 2>/dev/null || true)"
  if [[ -n "${PID}" ]]; then
    if ps -p "${PID}" >/dev/null 2>&1; then
      CMD="$(ps -p "${PID}" -o command= 2>/dev/null || true)"
      if echo "${CMD}" | grep -Eq "(atlas-run|jira-maven-plugin|com\\.atlassian\\.maven\\.plugins:jira-maven-plugin)"; then
        echo "Stopping Jira (PID ${PID})"
        kill "${PID}" 2>/dev/null || true
      else
        echo "Ignoring jira.pid PID ${PID} (unexpected process): ${CMD}" >&2
      fi
    else
      echo "Removing stale jira.pid (PID ${PID} not running)"
    fi
    rm -f jira.pid 2>/dev/null || true
  fi
fi

PIDS="$(lsof -nP -t -iTCP:2990 -sTCP:LISTEN 2>/dev/null || true)"
if [[ -n "${PIDS}" ]]; then
  echo "Stopping listeners on 2990: ${PIDS}"
  kill ${PIDS} 2>/dev/null || true
fi

echo "Done."
