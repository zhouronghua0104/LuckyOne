@echo off
setlocal EnableExtensions EnableDelayedExpansion

rem install_qcguiagent_windows.bat
rem Windows batch script to provision device models, runtime libs, and app via ADB.
rem 
rem Usage:
rem   install_qcguiagent_windows.bat --apk C:\path\to\app.apk [--serial SERIAL] ^
rem       [--model7 .\ark_000007_20250811_8850] [--model8 .\ark_000008_20250725_8850] ^
rem       [--libs .\BibaoLibs\lib]
rem
rem Steps:
rem 1) adb root && adb remount
rem 2) Ensure model ark_000007_20250811_8850 exists on device (push if missing)
rem 4) Ensure model ark_000008_20250725_8850 exists on device (push if missing)
rem 5) Install runtime libs .\BibaoLibs\lib -> /vendor/app/qcguiagent/lib
rem 6) Uninstall old app, remove old apk(s), push new apk
rem 7) Reboot and wait for boot
rem 9) adb root; setenforce 0
rem
rem Additionally:
rem - If local model or libs directories are missing, they will be downloaded from predefined URLs and unzipped.

set "EXPECTED_MODEL7_NAME=ark_000007_20250811_8850"
set "EXPECTED_MODEL8_NAME=ark_000008_20250725_8850"
set "DEVICE_MODEL_DIR=/data/local/qcguiagent"
set "DEVICE_VENDOR_DIR=/vendor/app/qcguiagent"
set "PACKAGE_NAME=com.modelbest.qcguiagent"

rem Download URLs
set "MODEL7_ZIP_URL=https://minicpm.oss-cn-beijing.aliyuncs.com/qualcomm/ark_000007_20250811_8850.zip"
set "MODEL8_ZIP_URL=https://minicpm.oss-cn-beijing.aliyuncs.com/qualcomm/ark_000008_20250725_8850.zip"
set "LIBS_ZIP_URL=https://minicpm.oss-cn-beijing.aliyuncs.com/qualcomm/opencl/lib.zip"

rem Defaults (can be overridden by CLI)
set "LOCAL_MODEL7=.\%EXPECTED_MODEL7_NAME%"
set "LOCAL_MODEL8=.\%EXPECTED_MODEL8_NAME%"
set "LOCAL_LIBS_DIR=.\BibaoLibs\lib"
set "APK_PATH="
set "SERIAL="

if "%~1"=="" goto :usageMaybe
:parse_args
if "%~1"=="" goto :args_done
if /I "%~1"=="--apk"      ( set "APK_PATH=%~2" & shift & shift & goto :parse_args )
if /I "%~1"=="--serial"   ( set "SERIAL=%~2" & shift & shift & goto :parse_args )
if /I "%~1"=="--model7"   ( set "LOCAL_MODEL7=%~2" & shift & shift & goto :parse_args )
if /I "%~1"=="--model8"   ( set "LOCAL_MODEL8=%~2" & shift & shift & goto :parse_args )
if /I "%~1"=="--libs"     ( set "LOCAL_LIBS_DIR=%~2" & shift & shift & goto :parse_args )
if /I "%~1"=="--help"     ( goto :usage )
if /I "%~1"=="-h"         ( goto :usage )
echo [ERROR] Unknown argument: %~1
goto :usage
:args_done

:usageMaybe
if defined APK_PATH goto :validate
:usage
  echo.
  echo install_qcguiagent_windows.bat - Provision device models, libs and app via ADB
  echo.
  echo Required:
  echo   --apk PATH                 Path to the APK file to push to %DEVICE_VENDOR_DIR%
  echo Optional:
  echo   --serial SERIAL            Target device serial (when multiple devices connected)
  echo   --model7 PATH              Local path to %EXPECTED_MODEL7_NAME% ^(default: %LOCAL_MODEL7%^
  echo   --model8 PATH              Local path to %EXPECTED_MODEL8_NAME% ^(default: %LOCAL_MODEL8%^
  echo   --libs PATH                Local path to BibaoLibs\lib ^(default: %LOCAL_LIBS_DIR%^
  echo Notes:
  echo   - Missing model or libs directories will be downloaded and unzipped automatically.
  echo Examples:
  echo   install_qcguiagent_windows.bat --apk .\qcguiagent-release.apk
  echo   install_qcguiagent_windows.bat --apk .\qcguiagent.apk --serial ABC123 --model7 .\%EXPECTED_MODEL7_NAME% --model8 .\%EXPECTED_MODEL8_NAME% --libs .\BibaoLibs\lib
  exit /b 1

:validate
where adb >nul 2>&1
if errorlevel 1 (
  echo [ERROR] adb not found in PATH. Please install Android platform-tools.
  exit /b 1
)
if not defined APK_PATH (
  echo [ERROR] --apk is required
  exit /b 1
)
if not exist "%APK_PATH%" (
  echo [ERROR] APK not found: %APK_PATH%
  exit /b 1
)

set "ADB=adb"
if not "%SERIAL%"=="" set "ADB=adb -s %SERIAL%"

call :ensure_local_assets || goto :fail
call :ensure_root_and_remount
call :install_model_if_missing "%LOCAL_MODEL7%" "%EXPECTED_MODEL7_NAME%" || goto :fail
call :install_model_if_missing "%LOCAL_MODEL8%" "%EXPECTED_MODEL8_NAME%" || goto :fail
call :install_runtime_libs "%LOCAL_LIBS_DIR%" || goto :fail
call :install_app_apk "%APK_PATH%" || goto :fail

echo [INFO] Rebooting device
%ADB% reboot

call :wait_for_device_and_boot

echo [INFO] Re-acquiring root and disabling SELinux
%ADB% root >nul 2>&1
%ADB% shell setenforce 0
if errorlevel 1 echo [WARN] Failed to setenforce 0; SELinux may remain enforcing.

echo [INFO] All steps completed successfully.
exit /b 0

:ensure_root_and_remount
  echo [INFO] Ensuring adb root and remount
  %ADB% root >nul 2>&1
  rem small delay
  ping -n 2 127.0.0.1 >nul
  %ADB% remount | cmd /c more
  goto :eof

:device_path_exists
  set "_DPE_PATH=%~1"
  %ADB% shell "test -e %_DPE_PATH%" >nul 2>&1
  if errorlevel 1 (
    set "_DPE_EXISTS=0"
  ) else (
    set "_DPE_EXISTS=1"
  )
  exit /b 0

:push_to_device_as_name
  set "_LOCAL_PATH=%~1"
  set "_DEVICE_PARENT=%~2"
  set "_EXPECTED_NAME=%~3"
  if not exist "%_LOCAL_PATH%" (
    echo [ERROR] Local path does not exist: %_LOCAL_PATH%
    exit /b 1
  )
  for %%F in ("%_LOCAL_PATH%") do set "_LOCAL_NAME=%%~nxF"
  echo [INFO] Pushing "%_LOCAL_PATH%" to "%_DEVICE_PARENT%"
  %ADB% push "%_LOCAL_PATH%" "%_DEVICE_PARENT%" | cmd /c more
  if /I not "%_LOCAL_NAME%"=="%_EXPECTED_NAME%" (
    echo [INFO] Renaming on device: %_LOCAL_NAME% -^> %_EXPECTED_NAME%
    %ADB% shell "mv -f '%_DEVICE_PARENT%/%_LOCAL_NAME%' '%_DEVICE_PARENT%/%_EXPECTED_NAME%'"
  )
  exit /b 0

:install_model_if_missing
  set "_LOCAL_PATH=%~1"
  set "_EXPECTED_NAME=%~2"
  set "_DEVICE_DIR=%DEVICE_MODEL_DIR%"
  set "_DEVICE_FULL_PATH=%_DEVICE_DIR%/%_EXPECTED_NAME%"

  call :device_path_exists "%_DEVICE_FULL_PATH%"
  if "%_DPE_EXISTS%"=="1" (
    echo [INFO] Model exists on device: %_DEVICE_FULL_PATH%
    exit /b 0
  )

  echo [INFO] Model missing; creating dir and installing: %_DEVICE_FULL_PATH%
  %ADB% shell "mkdir -p '%_DEVICE_DIR%'"
  call :push_to_device_as_name "%_LOCAL_PATH%" "%_DEVICE_DIR%" "%_EXPECTED_NAME%" || exit /b 1

  echo [INFO] Setting owner and permissions: %_DEVICE_FULL_PATH%
  %ADB% shell "chown -R system:system '%_DEVICE_FULL_PATH%'"
  %ADB% shell "chmod -R 777 '%_DEVICE_FULL_PATH%'"
  exit /b 0

:install_runtime_libs
  set "_LOCAL_LIB_DIR=%~1"
  if not exist "%_LOCAL_LIB_DIR%" (
    echo [ERROR] Local libs directory not found: %_LOCAL_LIB_DIR%
    exit /b 1
  )
  echo [INFO] Creating vendor app directory: %DEVICE_VENDOR_DIR%
  %ADB% shell "mkdir -p '%DEVICE_VENDOR_DIR%'"
  echo [INFO] Pushing runtime libs to device: %DEVICE_VENDOR_DIR%
  %ADB% push "%_LOCAL_LIB_DIR%" "%DEVICE_VENDOR_DIR%" | cmd /c more
  echo [INFO] Setting permissions for runtime libs
  %ADB% shell "chmod -R 777 '%DEVICE_VENDOR_DIR%/lib'" >nul 2>&1
  exit /b 0

:install_app_apk
  set "_APK=%~1"
  echo [INFO] Uninstalling old app: %PACKAGE_NAME% (ignore errors if not installed)
  %ADB% uninstall %PACKAGE_NAME% | cmd /c more
  echo [INFO] Removing old APK files from %DEVICE_VENDOR_DIR%
  %ADB% shell "mkdir -p '%DEVICE_VENDOR_DIR%'; rm -f '%DEVICE_VENDOR_DIR%'/*.apk" >nul 2>&1
  echo [INFO] Pushing APK to device: %_APK% -^> %DEVICE_VENDOR_DIR%/
  %ADB% push "%_APK%" "%DEVICE_VENDOR_DIR%/" | cmd /c more
  exit /b 0

:wait_for_device_and_boot
  echo [INFO] Waiting for device to be online...
  %ADB% wait-for-device
  echo [INFO] Waiting for Android boot completion...
  set "_BOOT_OK="
  for /L %%I in (1,1,180) do (
    set "_SYS="
    set "_DEV="
    for /f "usebackq tokens=*" %%A in (`%ADB% shell getprop sys.boot_completed 2^>nul`) do set "_SYS=%%A"
    for /f "usebackq tokens=*" %%B in (`%ADB% shell getprop dev.bootcomplete 2^>nul`) do set "_DEV=%%B"
    if "!_SYS!"=="1" (
      set "_BOOT_OK=1"
      goto :_boot_done
    )
    if "!_DEV!"=="1" (
      set "_BOOT_OK=1"
      goto :_boot_done
    )
    ping -n 2 127.0.0.1 >nul
  )
:_boot_done
  if not defined _BOOT_OK echo [WARN] Boot completion not confirmed within timeout.
  exit /b 0

:ensure_local_assets
  echo [INFO] Pre-download: Ensuring local models and libs exist
  rem model7
  if not exist "%LOCAL_MODEL7%" (
    echo [INFO] Local model missing: %LOCAL_MODEL7%. Downloading...
    call :get_parent_dir "%LOCAL_MODEL7%" _PARENT7
    call :download_and_unzip "%MODEL7_ZIP_URL%" "%_PARENT7%" || exit /b 1
    if not exist "%LOCAL_MODEL7%" (
      echo [ERROR] After download, directory still missing: %LOCAL_MODEL7%
      exit /b 1
    )
  ) else (
    echo [INFO] Local model exists: %LOCAL_MODEL7%
  )

  rem model8
  if not exist "%LOCAL_MODEL8%" (
    echo [INFO] Local model missing: %LOCAL_MODEL8%. Downloading...
    call :get_parent_dir "%LOCAL_MODEL8%" _PARENT8
    call :download_and_unzip "%MODEL8_ZIP_URL%" "%_PARENT8%" || exit /b 1
    if not exist "%LOCAL_MODEL8%" (
      echo [ERROR] After download, directory still missing: %LOCAL_MODEL8%
      exit /b 1
    )
  ) else (
    echo [INFO] Local model exists: %LOCAL_MODEL8%
  )

  rem libs
  if not exist "%LOCAL_LIBS_DIR%" (
    echo [INFO] Local libs missing: %LOCAL_LIBS_DIR%. Downloading...
    call :get_parent_dir "%LOCAL_LIBS_DIR%" _PARENTLIB
    call :download_and_unzip "%LIBS_ZIP_URL%" "%_PARENTLIB%" || exit /b 1
    if not exist "%LOCAL_LIBS_DIR%" (
      echo [ERROR] After download, directory still missing: %LOCAL_LIBS_DIR%
      exit /b 1
    )
  ) else (
    echo [INFO] Local libs exist: %LOCAL_LIBS_DIR%
  )
  exit /b 0

:get_parent_dir
  set "_IN=%~1"
  set "_DIR=%~dp1"
  if "!_DIR!"=="" set "_DIR=."
  if "!_DIR:~-1!"=="\" set "_DIR=!_DIR:~0,-1!"
  set "%~2=!_DIR!"
  exit /b 0

:download_and_unzip
  set "_URL=%~1"
  set "_DEST_DIR=%~2"
  if not exist "%_DEST_DIR%" mkdir "%_DEST_DIR%"
  set "_TMPZIP=%TEMP%\dl_%RANDOM%_%RANDOM%.zip"
  echo [INFO] Downloading: %_URL%
  powershell -NoProfile -Command "try { Invoke-WebRequest -UseBasicParsing -Uri '%_URL%' -OutFile '%_TMPZIP%' -ErrorAction Stop } catch { exit 1 }"
  if errorlevel 1 (
    echo [ERROR] Download failed: %_URL%
    if exist "%_TMPZIP%" del /f /q "%_TMPZIP%" >nul 2>&1
    exit /b 1
  )
  echo [INFO] Extracting to: %_DEST_DIR%
  powershell -NoProfile -Command "try { Expand-Archive -Force -Path '%_TMPZIP%' -DestinationPath '%_DEST_DIR%' -ErrorAction Stop } catch { exit 1 }"
  if errorlevel 1 (
    echo [ERROR] Extract failed to: %_DEST_DIR%
    if exist "%_TMPZIP%" del /f /q "%_TMPZIP%" >nul 2>&1
    exit /b 1
  )
  if exist "%_TMPZIP%" del /f /q "%_TMPZIP%" >nul 2>&1
  exit /b 0

:fail
  echo [ERROR] Script failed.
  exit /b 1