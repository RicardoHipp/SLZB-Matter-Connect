@echo off
REM Speichert ALLE Aenderungen als Commit (lokal) UND laedt sie zu GitHub hoch.
REM Ablauf: git add (alles vormerken) -> git commit (Schnappschuss + Nachricht) -> git push (hochladen)

set /p MSG=Commit-Nachricht eingeben:

wsl bash -lc "cd ~/SLZB-Matter-Connect && git add -A && git commit -m '%MSG%' && git push"

pause
