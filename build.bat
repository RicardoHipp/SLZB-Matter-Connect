@echo off
wsl bash -lc "cd ~/SLZB-Matter-Connect && bash scripts/setup.sh && ./build_app.sh"
pause
