@echo off
cd /d "%~dp0"
if not exist logs mkdir logs
where py >nul 2>&1 && (set PY=py -3) || (set PY=python)
echo Saving all recordings to data\raw, then erasing the XIAO...
%PY% -u python\tools\dump_logs.py --erase > logs\dump_erase_last.txt 2>&1
type logs\dump_erase_last.txt
%PY% -u python\tools\xiao_cmd.py "rec status"
timeout /t 8 >nul
