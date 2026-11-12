@echo off
cd /d "%~dp0"
if not exist logs mkdir logs
where py >nul 2>&1 && (set PY=py -3) || (set PY=python)
echo Update: flash firmware, save + erase recordings, battery check
call :finddrive
if "%DRV%"=="" (
  %PY% -u python\tools\xiao_cmd.py "rec dfu" >nul 2>&1
  timeout /t 8 /nobreak >nul
  call :finddrive
)
if "%DRV%"=="" (
  echo XIAO drive not found. > logs\flash_log.txt
  echo XIAO drive not found. Double-tap reset and run again.
  pause
  exit /b 1
)
type %DRV%\INFO_UF2.TXT > logs\flash_log.txt
copy /Y firmware\logger\prebuilt\lift_logger_d1.uf2 %DRV%\ >> logs\flash_log.txt 2>&1
echo COPY_DONE >> logs\flash_log.txt
echo Flashed. Waiting for reboot...
timeout /t 10 /nobreak >nul
%PY% -u python\tools\dump_logs.py --erase > logs\dump_erase_last.txt 2>&1
type logs\dump_erase_last.txt
%PY% -u python\tools\xiao_cmd.py "rec batt" "rec status"
timeout /t 8 >nul
exit /b 0

:finddrive
set DRV=
for %%d in (D E F G H I J K) do if exist %%d:\INFO_UF2.TXT set DRV=%%d:
exit /b 0
