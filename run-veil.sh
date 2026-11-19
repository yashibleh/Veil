#!/usr/bin/env bash
set -euo pipefail
echo "Launching VEIL — Local-First Privacy Gateway..."
command -v java >/dev/null 2>&1 || { echo "[ERROR] Java 17+ JDK not found on PATH."; exit 1; }
command -v mvn >/dev/null 2>&1 || { echo "[ERROR] Apache Maven not found on PATH."; exit 1; }
mvn javafx:run
