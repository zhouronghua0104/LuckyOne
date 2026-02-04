@echo off
setlocal EnableExtensions

rem Usage: rollback_apk.bat [device_serial]
rem If multiple devices are connected, pass a serial.

set "ADB=adb"
if not "%~1"=="" set "ADB=adb -s %~1"

%ADB% get-state 1>nul 2>nul
if errorlevel 1 (
  echo [ERROR] adb device not ready.
  echo         Connect a device or pass a serial: %~nx0 ^<serial^>
  exit /b 1
)

rem If /vendor is read-only, uncomment these lines:
rem %ADB% root
rem %ADB% remount

for %%A in (AppStore Avatar AvatarLand Eshop ExhibitionHall Pay DigitalManual CarplayX) do (
  call :rollback_one %%A
)

echo Done.
exit /b 0

:rollback_one
set "APP=%~1"
set "DIR=/vendor/app/%APP%"
set "APK=%DIR%/%APP%.apk"
set "APK_BAK=%APK%_bak"
set "APK_RAM=%APK%_ram"

echo Rolling back %APP%...
%ADB% shell "if [ -f '%APK%' ]; then mv '%APK%' '%APK_RAM%'; fi; if [ -f '%APK_BAK%' ]; then mv '%APK_BAK%' '%APK%'; fi"
if errorlevel 1 (
  echo [WARN] adb shell failed for %APP%.
)
exit /b 0
