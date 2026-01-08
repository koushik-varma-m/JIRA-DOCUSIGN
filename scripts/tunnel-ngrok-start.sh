#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

source ./setenv.sh >/dev/null 2>&1 || true

if ! curl -fsS -o /dev/null "http://localhost:2990/jira/status" 2>/dev/null; then
  echo "Warning: Jira is not responding at http://localhost:2990/jira (ngrok will still start)." >&2
fi

if ! command -v ngrok >/dev/null 2>&1; then
  echo "ngrok not found. Install with: brew install ngrok/ngrok/ngrok" >&2
  exit 1
fi

if [[ -z "${NGROK_AUTHTOKEN:-}" ]]; then
  echo "Missing NGROK_AUTHTOKEN env var." >&2
  echo "Set it in ./setenv.local.sh, then re-run this script." >&2
  exit 1
fi

# Kill any old ngrok from this repo
if [[ -f ngrok.pid ]]; then
  old_pid="$(cat ngrok.pid 2>/dev/null || true)"
  if [[ -n "${old_pid}" ]]; then
    kill "${old_pid}" 2>/dev/null || true
  fi
  rm -f ngrok.pid
fi

rm -f ngrok.log

nohup ngrok http 2990 \
  --authtoken "${NGROK_AUTHTOKEN}" \
  --log=stdout \
  --log-level=info \
  > ngrok.log 2>&1 &

echo $! > ngrok.pid

echo "ngrok started (pid $(cat ngrok.pid)), waiting for public URL..."

PUBLIC_URL=""
for _ in $(seq 1 100); do
  RAW="$(curl -fsS http://127.0.0.1:4040/api/tunnels 2>/dev/null || true)"
  if [[ -z "${RAW}" ]]; then
    sleep 0.2
    continue
  fi
  PUBLIC_URL="$(
    printf "%s" "${RAW}" | python3 - <<'PY' || true
import json, sys
try:
  data = json.load(sys.stdin)
except Exception:
  sys.exit(1)
for t in data.get("tunnels", []):
  pub = t.get("public_url") or ""
  if pub.startswith("https://"):
    print(pub)
    sys.exit(0)
sys.exit(1)
PY
  )"
  if [[ -n "${PUBLIC_URL}" ]]; then
    break
  fi
  sleep 0.2
done

if [[ -z "${PUBLIC_URL}" ]]; then
  echo "Failed to discover ngrok public URL from http://127.0.0.1:4040/api/tunnels" >&2
  echo "Check ngrok.log for details." >&2
  exit 1
fi

WEBHOOK_URL="${PUBLIC_URL}/jira/rest/docusign/1.0/send/webhook"
echo "Public URL: ${PUBLIC_URL}"
echo "Webhook URL: ${WEBHOOK_URL}"

perl -0777 -i -pe 's|^export DOCUSIGN_WEBHOOK_URL=\"[^\"]*\"|export DOCUSIGN_WEBHOOK_URL=\"'"${WEBHOOK_URL}"'\"|m' setenv.sh

echo "Updated setenv.sh DOCUSIGN_WEBHOOK_URL."
