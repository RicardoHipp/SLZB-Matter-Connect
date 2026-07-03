#!/usr/bin/env bash
# Klont connectedhomeip auf einem festen Commit und spielt die SLZB-Matter-Connect-
# Patch-Dateien darüber. Läuft lokal (WSL/Linux), im Devcontainer und in CI identisch.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHIP_DIR="$REPO_ROOT/matter-sdk"

# TODO: hier den Commit-Hash eintragen, mit dem deine App aktuell funktioniert.
# Ermitteln mit: cd <dein-bestehendes-matter-sdk-in-WSL> && git rev-parse HEAD
CHIP_COMMIT="823523521f9ff8dd25375d798428d603195265c1"

if [ "$CHIP_COMMIT" = "REPLACE_ME_WITH_PINNED_COMMIT_SHA" ]; then
    echo "FEHLER: Bitte CHIP_COMMIT in scripts/setup.sh setzen (siehe Kommentar)." >&2
    exit 1
fi

if [ ! -d "$CHIP_DIR/.git" ]; then
    echo "==> Klone connectedhomeip nach $CHIP_DIR ..."
    git clone https://github.com/project-chip/connectedhomeip.git "$CHIP_DIR"
fi

echo "==> Checke Commit $CHIP_COMMIT aus ..."
cd "$CHIP_DIR"
git fetch origin --depth=1 "$CHIP_COMMIT" || git fetch origin
git checkout "$CHIP_COMMIT"
git submodule update --init --recursive --depth=1

echo "==> Bootstrap der Build-Umgebung (kann beim ersten Mal dauern) ..."
set +u
source scripts/bootstrap.sh
set -u

cd "$REPO_ROOT"

echo "==> Wende SLZB-Matter-Connect-Patch auf CHIPTool an ..."
cp -rv patches/CHIPTool/. "$CHIP_DIR/examples/android/CHIPTool/"

echo "==> Fertig. Build starten mit: ./build_app.sh"
