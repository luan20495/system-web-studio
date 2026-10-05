#!/usr/bin/env bash
# Scans the working tree AND git history for committed secrets with gitleaks (free, local). Non-zero exit on any finding.
set -euo pipefail
cd "$(dirname "$0")/.."
command -v gitleaks >/dev/null || { echo "install gitleaks (brew install gitleaks)" >&2; exit 2; }
echo "== history (all commits)";  gitleaks git --no-banner --redact --config .gitleaks.toml .
echo "== working tree (tracked + untracked files that are not gitignored)"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
git ls-files -co --exclude-standard -z | xargs -0 -I{} sh -c 'mkdir -p "$1/$(dirname "$2")" && cp "$2" "$1/$2" 2>/dev/null || true' _ "$T" {}
gitleaks dir --no-banner --redact --config .gitleaks.toml "$T"
echo "SECRET SCAN CLEAN"
