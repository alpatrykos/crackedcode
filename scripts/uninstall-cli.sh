#!/usr/bin/env bash
set -euo pipefail

BIN_DIR="${HOME}/bin"
INSTALL_DIR="${HOME}/.local/share/agent"

usage() {
  cat <<'EOF'
Usage: scripts/uninstall-cli.sh [--bin-dir DIR] [--install-dir DIR]
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --bin-dir)
      [[ $# -ge 2 ]] || { echo "Missing value for --bin-dir" >&2; exit 1; }
      BIN_DIR="$2"
      shift 2
      ;;
    --install-dir)
      [[ $# -ge 2 ]] || { echo "Missing value for --install-dir" >&2; exit 1; }
      INSTALL_DIR="$2"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

rm -f "${BIN_DIR%/}/agent"
rm -rf "${INSTALL_DIR%/}"

echo "Removed agent launcher from ${BIN_DIR%/}/agent"
echo "Removed installation root ${INSTALL_DIR%/}"
