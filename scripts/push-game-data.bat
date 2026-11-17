@echo off
setlocal EnableExtensions DisableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "PROJECT_ROOT=%%~fI"
set "ADB_BIN="
set "DEVICE_ID="
set "GAMEDATA_DIR="
set "MOVIE_DIR="
set "REMOTE_DIR=/sdcard/Android/data/org.walkure.android/files/GameData"
set "REMOTE_FILES_DIR=/sdcard/Android/data/org.walkure.android/files"

for /f "delims=" %%A in ('where adb.exe 2^>nul') do if not defined ADB_BIN set "ADB_BIN=%%A"
if not defined ADB_BIN if defined ANDROID_HOME if exist "%ANDROID_HOME%\platform-tools\adb.exe" set "ADB_BIN=%ANDROID_HOME%\platform-tools\adb.exe"
if not defined ADB_BIN if defined ANDROID_SDK_ROOT if exist "%ANDROID_SDK_ROOT%\platform-tools\adb.exe" set "ADB_BIN=%ANDROID_SDK_ROOT%\platform-tools\adb.exe"
if not defined ADB_BIN if defined LOCALAPPDATA if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ADB_BIN=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not defined ADB_BIN if exist "%USERPROFILE%\.android-sdk\platform-tools\adb.exe" set "ADB_BIN=%USERPROFILE%\.android-sdk\platform-tools\adb.exe"
if not defined ADB_BIN if exist "%USERPROFILE%\Android\Sdk\platform-tools\adb.exe" set "ADB_BIN=%USERPROFILE%\Android\Sdk\platform-tools\adb.exe"
if not defined ADB_BIN (
    echo Error: adb.exe not found. Install Android platform-tools or add adb to PATH. 1>&2
    exit /b 1
)

echo Checking connected Android devices via "%ADB_BIN%"...
for /f "skip=1 tokens=1,2" %%A in ('"%ADB_BIN%" devices 2^>nul') do if /I "%%B"=="device" if not defined DEVICE_ID set "DEVICE_ID=%%A"
if not defined DEVICE_ID (
    echo No Android device found via ADB.
    echo Connect your phone with USB debugging enabled, or copy files via MTP.
    exit /b 1
)
echo Found connected device: %DEVICE_ID%

call :check_gamedata "%PROJECT_ROOT%\..\GameData"
call :check_gamedata "%PROJECT_ROOT%\GameData"
call :check_gamedata "%PROJECT_ROOT%\..\..\GameData"
if not defined GAMEDATA_DIR (
    echo Error: could not find a directory containing data*.pack files. 1>&2
    exit /b 1
)
echo Source GameData: "%GAMEDATA_DIR%"

echo Creating remote directory: %REMOTE_DIR%...
"%ADB_BIN%" -s "%DEVICE_ID%" shell mkdir -p "%REMOTE_DIR%"
if errorlevel 1 goto failed

echo Pushing data*.pack files (this may take a few minutes for approximately 3.9 GB)...
for %%F in ("%GAMEDATA_DIR%\data*.pack") do if exist "%%~fF" (
    echo Pushing %%~nxF...
    call :push_file "%%~fF" "%REMOTE_DIR%/"
    if errorlevel 1 goto failed
)

if not exist "%GAMEDATA_DIR%\walkure.keys" goto find_movie
echo Pushing walkure.keys...
call :push_file "%GAMEDATA_DIR%\walkure.keys" "%REMOTE_FILES_DIR%/walkure.keys"
if errorlevel 1 goto failed

:find_movie
call :check_movie "%GAMEDATA_DIR%\movie"
call :check_movie "%GAMEDATA_DIR%\..\movie"
call :check_movie "%GAMEDATA_DIR%\..\GameData\movie"
if not defined MOVIE_DIR goto success

echo Found movie cutscenes in "%MOVIE_DIR%"
"%ADB_BIN%" -s "%DEVICE_ID%" shell mkdir -p "%REMOTE_DIR%/movie"
if errorlevel 1 goto failed

where ffmpeg.exe >nul 2>nul
if errorlevel 1 goto push_movies
echo Checking/transcoding .ogv cutscenes to .mp4 for Android...
for %%F in ("%MOVIE_DIR%\*.ogv") do if exist "%%~fF" (
    if not exist "%%~dpnF.mp4" (
        echo Transcoding %%~nxF...
        ffmpeg -loglevel error -y -i "%%~fF" -map 0:v:0 -map "0:a:0?" -c:v libx264 -preset fast -crf 22 -c:a aac -b:a 192k -movflags +faststart "%%~dpnF.mp4"
        if errorlevel 1 echo Warning: ffmpeg could not transcode %%~nxF; the original OGV will still be copied.
    )
)

:push_movies
echo Pushing movie cutscenes...
for %%F in ("%MOVIE_DIR%\*.mp4" "%MOVIE_DIR%\*.ogv") do if exist "%%~fF" (
    echo Pushing %%~nxF...
    call :push_file "%%~fF" "%REMOTE_DIR%/movie/"
    if errorlevel 1 goto failed
)

:success
echo ============================================================
echo  GameData transfer complete!
echo  You can now install and launch Walkure Romanze on your device.
echo ============================================================
exit /b 0

:check_gamedata
if defined GAMEDATA_DIR exit /b 0
if not exist "%~1\data*.pack" exit /b 0
for %%I in ("%~1") do set "GAMEDATA_DIR=%%~fI"
exit /b 0

:check_movie
if defined MOVIE_DIR exit /b 0
if not exist "%~1\" exit /b 0
for %%I in ("%~1") do set "MOVIE_DIR=%%~fI"
exit /b 0

:push_file
"%ADB_BIN%" -s "%DEVICE_ID%" push "%~1" "%~2"
exit /b %ERRORLEVEL%

:failed
echo Error: ADB transfer failed. 1>&2
exit /b 1
