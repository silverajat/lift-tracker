@echo off
cd /d "%~dp0"
where py >nul 2>&1 && (set PY=py -3) || (set PY=python)
%PY% -u python\tools\xiao_cmd.py "device list" "rec status" "kernel uptime"
timeout /t 5 >nul
