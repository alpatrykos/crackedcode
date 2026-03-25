#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

BIN_DIR="${HOME}/bin"
INSTALL_DIR="${HOME}/.local/share/agent"
FORCE=0

usage() {
  cat <<'EOF'
Usage: scripts/install-cli.sh [--bin-dir DIR] [--install-dir DIR] [--force]
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
    --force)
      FORCE=1
      shift
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

VERSION="${AGENT_VERSION:-}"
if [[ -z "${VERSION}" ]]; then
  VERSION="$(cd "${REPO_ROOT}" && ./gradlew -q printVersion)"
fi

DIST_DIR="${AGENT_DIST_DIR:-${REPO_ROOT}/agent-cli/build/install/agent}"
if [[ "${AGENT_SKIP_BUILD:-0}" != "1" ]]; then
  (cd "${REPO_ROOT}" && ./gradlew installAgent)
fi

if [[ ! -x "${DIST_DIR}/bin/agent" ]]; then
  echo "Built distribution not found at ${DIST_DIR}/bin/agent" >&2
  exit 1
fi

TARGET_DIR="${INSTALL_DIR%/}/${VERSION}"
CURRENT_LINK="${INSTALL_DIR%/}/current"
LAUNCHER_LINK="${BIN_DIR%/}/agent"

mkdir -p "${BIN_DIR}" "${INSTALL_DIR}"

if [[ -e "${TARGET_DIR}" ]]; then
  if [[ "${FORCE}" != "1" ]]; then
    echo "Install target already exists: ${TARGET_DIR}. Re-run with --force to replace it." >&2
    exit 1
  fi
  rm -rf "${TARGET_DIR}"
fi

mkdir -p "${TARGET_DIR}"
cp -R "${DIST_DIR}/." "${TARGET_DIR}/"
ln -sfn "${TARGET_DIR}" "${CURRENT_LINK}"
ln -sfn "${CURRENT_LINK}/bin/agent" "${LAUNCHER_LINK}"

echo "Installed agent ${VERSION}"
echo "  distribution: ${TARGET_DIR}"
echo "  launcher: ${LAUNCHER_LINK}"

case ":${PATH}:" in
  *":${BIN_DIR}:"*) ;;
  *)
    echo
    echo "Add the launcher directory to PATH:"
    echo "export PATH=\"${BIN_DIR}:\$PATH\""
    ;;
esac
