#!/usr/bin/env bash
# ========================================================
#   M328V6 Web Host - Local PWA Launcher (Linux / macOS)
# ========================================================

set -e
cd "$(dirname "$0")"

URL="http://localhost:8328/m328v6_web_host.html"

echo "========================================================"
echo "  M328V6 Web Host - Local PWA Launcher (Linux / macOS)"
echo "  Click [Install app] in Chrome/Edge address bar"
echo "========================================================"
echo ""
echo "Opening browser at $URL ..."

# Open URL in background with platform-specific opener
if command -v xdg-open >/dev/null 2>&1; then
    (sleep 1 && xdg-open "$URL" >/dev/null 2>&1) &
elif command -v open >/dev/null 2>&1; then
    (sleep 1 && open "$URL" >/dev/null 2>&1) &
fi

echo "Starting local HTTP server on port 8328..."
echo "Press Ctrl+C to stop the server."
echo "(Installed PWA apps can run offline afterwards.)"
echo ""

if command -v python3 >/dev/null 2>&1; then
    python3 -m http.server 8328
elif command -v python >/dev/null 2>&1; then
    python -m http.server 8328
else
    echo "[ERROR] Python 3 is not found. Please install python3 to run local PWA server."
    echo "You can also open m328v6_web_host.html directly in Chrome or Edge."
    exit 1
fi
