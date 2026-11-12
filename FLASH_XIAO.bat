@echo off
cd /d "%~dp0"
if not exist logs mkdir logs
echo Lift tracker: flash the D1 logger onto the XIAO
where py >nul 2>&1 && (set PY=py -3) || (set PY=python)
call :finddrive
if "%DRV%"=="" (
  echo Asking the running firmware to switch to flashing mode...
  %PY% -u python\tools\xiao_cmd.py "rec dfu" >nul 2>&1
  timeout /t 6 /nobreak >nul
  call :finddrive
)
if "%DRV%"=="" (
  echo XIAO drive not found. Double-tap the reset button and run this again.
  echo NO_UF2_DRIVE > logs\flash_log.txt
  pause
  exit /b 1
)
echo Found the XIAO bootloader drive at %DRV%
type %DRV%\INFO_UF2.TXT > logs\flash_log.txt
copy /Y firmware\logger\prebuilt\lift_logger_d1.uf2 %DRV%\ >> logs\flash_log.txt 2>&1
echo COPY_DONE >> logs\flash_log.txt
echo Firmware copied. The board reboots now (LED should go blue).
timeout /t 8 /nobreak >nul
call XIAO_desk_test.bat
exit /b 0

:finddrive
set DRV=
for %%d in (D E F G H I J K) do if exist %%d:\INFO_UF2.TXT set DRV=%%d:
exit /b 0
