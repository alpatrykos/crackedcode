#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

VERSION=""
URL=""
SHA256=""
OUTPUT=""

usage() {
  cat <<'EOF'
Usage: scripts/render-homebrew-formula.sh --url URL --sha256 HASH [--version VERSION] [--output PATH]
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version)
      [[ $# -ge 2 ]] || { echo "Missing value for --version" >&2; exit 1; }
      VERSION="$2"
      shift 2
      ;;
    --url)
      [[ $# -ge 2 ]] || { echo "Missing value for --url" >&2; exit 1; }
      URL="$2"
      shift 2
      ;;
    --sha256)
      [[ $# -ge 2 ]] || { echo "Missing value for --sha256" >&2; exit 1; }
      SHA256="$2"
      shift 2
      ;;
    --output)
      [[ $# -ge 2 ]] || { echo "Missing value for --output" >&2; exit 1; }
      OUTPUT="$2"
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

if [[ -z "${VERSION}" ]]; then
  VERSION="$(cd "${REPO_ROOT}" && ./gradlew -q printVersion)"
fi

[[ -n "${URL}" ]] || { echo "--url is required" >&2; exit 1; }
[[ -n "${SHA256}" ]] || { echo "--sha256 is required" >&2; exit 1; }

FORMULA_CONTENT="$(cat <<EOF
class Ccode < Formula
  desc "Kotlin/JVM coding agent CLI"
  homepage "https://github.com/alpatrykos/crackedcode"
  url "${URL}"
  version "${VERSION}"
  sha256 "${SHA256}"
  license "MIT"

  depends_on "openjdk"

  def install
    libexec.install Dir["*"]
    bin.env_script_all_files libexec/"bin", Language::Java.overridable_java_home_env("17+")
  end

  test do
    assert_match "ccode #{version}", shell_output("#{bin}/ccode version")
    assert_match "apply_patch", shell_output("#{bin}/ccode tools")
  end
end
EOF
)"

if [[ -n "${OUTPUT}" ]]; then
  mkdir -p "$(dirname "${OUTPUT}")"
  printf '%s\n' "${FORMULA_CONTENT}" > "${OUTPUT}"
else
  printf '%s\n' "${FORMULA_CONTENT}"
fi
