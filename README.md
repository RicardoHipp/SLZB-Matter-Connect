# SLZB-Matter Connect

Android-Begleit-App für ioBroker, basierend auf Googles offiziellem Matter-Referenztool
(`CHIPTool` aus [connectedhomeip](https://github.com/project-chip/connectedhomeip)).
Koppelt Matter-over-Thread-Geräte automatisch mit Handy **und** ioBroker (Multi-Admin),
ohne manuelle Eingabe am PC.

Details zur Funktionsweise: siehe `docs/walkthrough.md`.

## Fertige APK herunterladen

Wer die App nur nutzen will (kein eigener Build nötig): fertige APKs liegen unter
[Releases](../../releases). Einfach herunterladen und per `adb install` oder direkt
auf dem Handy installieren.

## Selbst entwickeln / bauen

Dieses Repo enthält **nicht** das komplette Matter-SDK (mehrere GB), sondern nur:

- `patches/CHIPTool/` – die geänderten/neuen Android-Dateien
- `iobroker/` – Server-seitige Patch-Skripte + das Vermittler-Skript für ioBroker
- `scripts/setup.sh` – klont connectedhomeip auf einem gepinnten Commit und wendet den Patch an
- `.devcontainer/` – fertige Docker-Entwicklungsumgebung (VS Code "Reopen in Container")

### Variante A: Devcontainer (empfohlen, plattformunabhängig)

1. Repo klonen, in VS Code öffnen
2. Befehlspalette → "Dev Containers: Reopen in Container"
3. Container lädt automatisch das offizielle `chip-build-android` Image (`ghcr.io/project-chip/chip-build-android:200`) und führt `scripts/setup.sh` aus
4. Danach im Container: `./build_app.sh`

### Variante B: WSL2 / natives Linux (schnellster lokaler Workflow)

```bash
git clone https://github.com/RicardoHipp/SLZB-Matter-Connect.git ~/SLZB-Matter-Connect
cd ~/SLZB-Matter-Connect
bash scripts/setup.sh          # klont connectedhomeip + wendet Patch an
./build_app.sh                 # baut die APK
```

**Wichtig:** Auf Windows/WSL das Projekt im nativen Linux-Dateisystem ablegen
(`/home/<user>/...`), nicht unter `/mnt/c/...` oder `/mnt/d/...` – sonst sind
GN/Ninja-Builds massiv langsamer (DrvFs statt ext4).

## Release bauen (GitHub Actions)

Ein Tag/Release auf GitHub triggert automatisch `.github/workflows/build-release.yml`,
das die APK in der Cloud baut und als Release-Anhang hochlädt. Dauer: ca. 30–60 Min.
beim ersten Mal, mit Cache danach ca. 5–15 Min.

## Lizenz

Basiert auf connectedhomeip (Apache License 2.0). Eigene Dateien in diesem Repo
stehen ebenfalls unter Apache-2.0, siehe `LICENSE`.
