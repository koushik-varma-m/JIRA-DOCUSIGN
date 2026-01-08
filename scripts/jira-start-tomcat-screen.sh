#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

TOMCAT_HOME="$(ls -d target/container/tomcat9x/apache-tomcat-* 2>/dev/null | head -n 1 || true)"
CATALINA_BASE_DIR="target/container/tomcat9x/cargo-jira-home"
JIRA_HOME_DIR="target/jira/home"

if [[ -z "${TOMCAT_HOME}" ]]; then
  echo "Tomcat not found under target/container/tomcat9x. Run atlas-run once to provision it." >&2
  exit 1
fi
if [[ ! -d "${CATALINA_BASE_DIR}" ]]; then
  echo "CATALINA_BASE not found at ${CATALINA_BASE_DIR}. Run atlas-run once to provision it." >&2
  exit 1
fi
if [[ ! -d "${JIRA_HOME_DIR}" ]]; then
  echo "Jira home not found at ${JIRA_HOME_DIR}. Run atlas-run once to provision it." >&2
  exit 1
fi

# Stop any previous session
screen -S jira -X quit 2>/dev/null || true
sleep 1

# Ensure port is free
PIDS="$(lsof -nP -t -iTCP:2990 -sTCP:LISTEN 2>/dev/null || true)"
if [[ -n "${PIDS}" ]]; then
  kill ${PIDS} 2>/dev/null || true
  sleep 2
fi
PIDS="$(lsof -nP -t -iTCP:2990 -sTCP:LISTEN 2>/dev/null || true)"
if [[ -n "${PIDS}" ]]; then
  kill -9 ${PIDS} 2>/dev/null || true
  sleep 1
fi

export CATALINA_HOME="${ROOT_DIR}/${TOMCAT_HOME}"
export CATALINA_BASE="${ROOT_DIR}/${CATALINA_BASE_DIR}"

# Avoid rare startup failures when Tomcat's shutdown port is already in use.
# Disabling the shutdown socket is fine here since we manage the process via screen.
SERVER_XML="${CATALINA_BASE}/conf/server.xml"
if [[ -f "${SERVER_XML}" ]]; then
  perl -pi -e 's/<Server\\b[^>]*\\bport=\"[^\"]+\"[^>]*\\bshutdown=\"SHUTDOWN\"[^>]*>/<Server port=\"-1\" shutdown=\"SHUTDOWN\">/g' "${SERVER_XML}" || true
fi

# Ensure scripts are executable (some environments lose +x bit)
chmod +x "${CATALINA_HOME}/bin/"*.sh 2>/dev/null || true

# Warn if disk is almost full (Jira may crash, e.g., JFR no-space errors)
FREE_KB="$(df -k "${ROOT_DIR}" | tail -n 1 | awk '{print $4}' || echo 0)"
if [[ "${FREE_KB}" -lt 2097152 ]]; then
  echo "Warning: low free disk space (~$((FREE_KB/1024)) MiB). Jira may fail/crash." >&2
fi

# These are the same opens Atlassian SDK uses; needed on Java 17 for Jira internals.
OPENS_ARGS="--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.io=ALL-UNNAMED --add-opens java.rmi/sun.rmi.transport=ALL-UNNAMED --add-opens java.base/java.net=ALL-UNNAMED --add-opens java.base/sun.net.www.protocol.jar=ALL-UNNAMED --add-opens java.base/sun.net.www.protocol.file=ALL-UNNAMED --add-opens java.base/sun.net.www.protocol.http=ALL-UNNAMED --add-opens java.base/sun.net.www.protocol.https=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/java.time=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.util.concurrent=ALL-UNNAMED --add-opens java.base/java.util.concurrent.atomic=ALL-UNNAMED --add-opens java.base/java.nio.file=ALL-UNNAMED --add-opens java.base/javax.crypto=ALL-UNNAMED --add-opens java.base/sun.reflect.generics.parser=ALL-UNNAMED --add-opens java.base/sun.security.action=ALL-UNNAMED --add-opens java.base/sun.util.calendar=ALL-UNNAMED --add-opens java.desktop/sun.font=ALL-UNNAMED --add-opens java.management/javax.management=ALL-UNNAMED --add-opens java.xml/jdk.xml.internal=ALL-UNNAMED --add-opens jdk.management/com.sun.management.internal=ALL-UNNAMED"

export CATALINA_OPTS="${CATALINA_OPTS:-} -Xms1g -Xmx2g ${OPENS_ARGS} -Djira.home=${ROOT_DIR}/${JIRA_HOME_DIR} -Dbaseurl=http://localhost:2990/jira -Datlassian.dev.mode=true -Datlassian.allow.insecure.url.parameter.login=true -Dplugin.root.directories=${ROOT_DIR} -Dplugin.resource.directories=${ROOT_DIR}/src/main/resources,${ROOT_DIR}/src/test/resources"

echo "Starting Tomcat directly in screen (this keeps Jira running even when atlas-run exits)..."
rm -f tomcat-run.log || true
screen -dmS jira bash -lc "${CATALINA_HOME}/bin/catalina.sh run > \"${ROOT_DIR}/tomcat-run.log\" 2>&1"

echo "Screen session: jira"
echo "Attach: screen -r jira"
echo "Stop:   screen -S jira -X quit"
echo "URL:    http://localhost:2990/jira"
echo "Log:    ${ROOT_DIR}/tomcat-run.log"
