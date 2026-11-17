@echo off
rem ==============================================================================
rem Minimal Headless Android SDK Bootstrapper for Walkure Romanze (Windows)
rem Installs only the essential command-line tools (~140MB) required to compile
rem the Android .apk without downloading Android Studio or the heavy IDE (~3GB).
rem ==============================================================================

setlocal enabledelayedexpansion

set SCRIPT_DIR=%~dp0
set PROJECT_ROOT=%SCRIPT_DIR%..
if "%ANDROID_HOME%"=="" (
    set TARGET_SDK_DIR=%USERPROFILE%\.android-sdk
) else (
    set TARGET_SDK_DIR=%ANDROID_HOME%
)

echo ============================================================
echo  Walkure Romanze -- Minimal Headless Android SDK Setup
echo ============================================================
echo Target SDK Directory: %TARGET_SDK_DIR%

set LOCAL_PROPS=%PROJECT_ROOT%\local.properties
set LOCAL_SDK_DIR=
if exist "%LOCAL_PROPS%" (
    for /f "tokens=1,* delims==" %%A in ('findstr /B /C:"sdk.dir=" "%LOCAL_PROPS%"') do set LOCAL_SDK_DIR=%%B
    if not "!LOCAL_SDK_DIR!"=="" (
        set LOCAL_SDK_DIR=!LOCAL_SDK_DIR:\\=\!
        if exist "!LOCAL_SDK_DIR!\platforms\android-36\android.jar" if exist "!LOCAL_SDK_DIR!\build-tools\36.0.0\aapt.exe" (
            set TARGET_SDK_DIR=!LOCAL_SDK_DIR!
            echo Found required Android SDK packages in local.properties.
            echo You are ready to build! Run:
            echo   gradlew.bat buildApk
            exit /b 0
        )
    )
)

if not exist "%TARGET_SDK_DIR%\cmdline-tools\latest\bin\sdkmanager.bat" (
    echo Downloading official Google command-line tools (~140 MB)...
    mkdir "%TARGET_SDK_DIR%\cmdline-tools" 2>nul
    set TOOLS_URL=https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip
    set TEMP_ZIP=%TARGET_SDK_DIR%\cmdline-tools-temp.zip

    powershell -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; (New-Object Net.WebClient).DownloadFile('%TOOLS_URL%', '%TEMP_ZIP%')"
    if errorlevel 1 (
        echo Failed to download command line tools.
        exit /b 1
    )

    echo Extracting tools...
    powershell -Command "Expand-Archive -Path '%TEMP_ZIP%' -DestinationPath '%TARGET_SDK_DIR%\cmdline-tools' -Force"
    move "%TARGET_SDK_DIR%\cmdline-tools\cmdline-tools" "%TARGET_SDK_DIR%\cmdline-tools\latest" >nul 2>&1
    del /q "%TEMP_ZIP%" 2>nul
)

set SDKMANAGER=%TARGET_SDK_DIR%\cmdline-tools\latest\bin\sdkmanager.bat

echo Accepting Android SDK licenses...
(echo y & echo y & echo y & echo y & echo y & echo y & echo y & echo y) | "%SDKMANAGER%" --sdk_root="%TARGET_SDK_DIR%" --licenses >nul 2>&1

echo Installing required platform-tools, platform 36 and build-tools...
call "%SDKMANAGER%" --sdk_root="%TARGET_SDK_DIR%" "platform-tools" "platforms;android-36" "build-tools;36.0.0"

rem Format path with escaped backslashes for local.properties
set ESCAPED_SDK=%TARGET_SDK_DIR:\=\\%
echo sdk.dir=%ESCAPED_SDK% > "%LOCAL_PROPS%"

echo ============================================================
echo  Android SDK successfully installed and configured!
echo  Location: %TARGET_SDK_DIR%
echo.
echo  You can now compile the APK by simply running:
echo    gradlew.bat buildApk
echo  Output will be in: build\dist\walkure-romanze.apk
echo ============================================================
