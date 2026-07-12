# OTBR-Matter Connect

> Matter-over-Thread anlernen über deinen **offenen** Border-Router (OTBR) — **ohne** Google-/Apple-Hub.

Android-Begleit-App für ioBroker, basierend auf Googles offiziellem Matter-Referenztool
(`CHIPTool` aus [connectedhomeip](https://github.com/project-chip/connectedhomeip)).

Damit lernst du **Matter-over-Thread-Geräte ganz einfach in ioBroker an** – **ohne**
ioBroker-Visu-App, **ohne** Cloud-Anbindung und **ohne** installierten Bluetooth-Adapter
am ioBroker-Host. Das Bluetooth-Commissioning übernimmt dein **Handy**; das Gerät geht per
**Thread** über deinen SLZB-Border-Router ins Netz, und die App koppelt es automatisch
sowohl mit dem Handy **als auch** mit ioBroker (Multi-Admin) – ganz ohne manuelle
Code-Eingabe am PC.

Die App spricht ioBroker dabei **direkt über die WebSocket-API** des `web`-Adapters an.

## Benötigte Hardware

Ein **SMLIGHT SLZB-Stick mit OpenThread-Border-Router (OTBR)** als Thread-Grenzrouter.
Geeignet sind vor allem die Multiradio-Modelle, die Zigbee **und** Thread gleichzeitig
können:

- **SLZB-MR1**, **SLZB-MR2**, **SLZB-MR3** (Multiradio – die typische Wahl für Thread/Matter)
- außerdem **SLZB-06**- und **SLZB-07**-Modelle mit entsprechender Thread-/OTBR-Firmware

Dazu ein Android-Handy mit Bluetooth (für das Commissioning) und eine laufende
ioBroker-Instanz mit Matter-Adapter.

## Fertige APK herunterladen

Wer die App nur nutzen will (kein eigener Build nötig): fertige APKs liegen unter
[Releases](../../releases). Einfach herunterladen und per `adb install` oder direkt
auf dem Handy installieren.

## ioBroker vorbereiten

Die App spricht ioBroker **direkt über die WebSocket-API** an. Voraussetzungen:

1. **`web`-Adapter** aktiv (alternativ `ws`-Adapter). Der ist für Admin/Vis meist
   ohnehin schon installiert. Die App verbindet sich per WebSocket – **Standard-Port 8082**.
   Den genauen Port findest du in ioBroker unter **Instanzen → web**. In der App trägst
   du die **IP deines ioBroker-Servers** und diesen Port ein.
2. **Matter-Adapter** (`matter.0`) installiert und der Controller aktiv.

> **Hinweis zur Anmeldung:** Aktuell wird der Zugriff **ohne Login** getestet
> (der `web`-Adapter steht dabei auf „keine Authentifizierung"). Eine Passwort-/
> Token-Anmeldung ist noch nicht implementiert.

## Selbst entwickeln / bauen

Dieses Repo enthält **nicht** das komplette Matter-SDK (mehrere GB), sondern nur:

- `patches/CHIPTool/` – die geänderten/neuen Android-Dateien
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
stehen ebenfalls unter Apache-2.0, siehe [LICENSE](LICENSE).
