@echo off
cd /d "%~dp0"
where py >/dev/null 2>&1 && (set PY=py -3) || (set PY=python)
%PY% -u python\tools\xiao_cmd.py "device init lsm6ds3tr-c@6a" "device list" "rec status"
timeout /t 5 >nul
