@echo off
cd /d "%~dp0"
if not exist logs mkdir logs
echo Lift tracker: XIAO desk test
where py >nul 2>&1 && (set PY=py -3) || (set PY=python)
%PY% --version > logs\python_check.txt 2>&1
if errorlevel 1 (
  echo Python for Windows was not found. Tell Claude.
  echo NO_PYTHON >> logs\python_check.txt
  pause
  exit /b 1
)
%PY% -m pip install --quiet pyserial >> logs\python_check.txt 2>&1
%PY% -u python\tools\xiao_desktest.py
