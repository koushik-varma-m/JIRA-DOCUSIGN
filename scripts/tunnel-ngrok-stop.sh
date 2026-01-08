#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [[ -f ngrok.pid ]]; then
  pid="$(cat ngrok.pid 2>/dev/null || true)"
  if [[ -n "${pid}" ]]; then
    kill "${pid}" 2>/dev/null || true
  fi
  rm -f ngrok.pid
  echo "ngrok stopped"
else
  echo "ngrok.pid not found"
fi
