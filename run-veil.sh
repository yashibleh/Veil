#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
echo "Launching VEIL..."
command -v java >/dev/null 2>&1 || { echo "[ERROR] Java 17+ JDK not found on PATH."; exit 1; }
command -v mvn  >/dev/null 2>&1 || { echo "[ERROR] Apache Maven not found on PATH."; exit 1; }
mvn -q -DskipTests package
exec java -jar target/veil-privacy-gateway-1.0.0.jar "$@"
