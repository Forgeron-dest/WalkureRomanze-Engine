@echo off
setlocal EnableExtensions DisableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "PROJECT_ROOT=%%~fI"
set "GAME_DIR=%WALKURE_GAME_DIR%"
if not defined GAME_DIR set "GAME_DIR=%PROJECT_ROOT%\.."
for %%I in ("%GAME_DIR%") do set "GAME_DIR=%%~fI"
set "OPTIONAL=false"
set "EXIT_CODE=1"

:parse_args
if "%~1"=="" goto args_done
if /I "%~1"=="--game-dir" (
    if "%~2"=="" (
        echo Error: --game-dir requires a path. 1>&2
        goto finish
    )
    for %%I in ("%~2") do set "GAME_DIR=%%~fI"
    shift
    shift
    goto parse_args
)
if /I "%~1"=="--optional" (
    set "OPTIONAL=true"
    shift
    goto parse_args
)
if /I "%~1"=="--help" goto usage
if /I "%~1"=="-h" goto usage
echo Error: unknown argument: %~1 1>&2
goto finish

:usage
echo Usage: scripts\restore-game-assets.bat [--game-dir PATH] [--optional]
echo Restores ignored Android assets from a local Walkure Romanze installation.
set "EXIT_CODE=0"
goto finish

:args_done
if not exist "%GAME_DIR%\GameData" goto no_gamedata
if not exist "%GAME_DIR%\GameData\data*.pack" goto no_packs

:new_temp_name
set "TEMP_NAME=.walkure-restore-assets-%RANDOM%-%RANDOM%"
set "TEMP_DIR=%GAME_DIR%\%TEMP_NAME%"
if exist "%TEMP_DIR%" goto new_temp_name
for /f "tokens=2 delims=:" %%C in ('chcp') do set "OLD_CODEPAGE=%%C"
set "OLD_CODEPAGE=%OLD_CODEPAGE: =%"
chcp 65001 >nul
mkdir "%TEMP_DIR%" 2>nul
if errorlevel 1 (
    echo Error: could not create temporary directory "%TEMP_DIR%". 1>&2
    goto finish
)

set "ASSETS_DIR=%PROJECT_ROOT%\android\src\main\assets"
if not exist "%ASSETS_DIR%" mkdir "%ASSETS_DIR%"
if errorlevel 1 (
    echo Error: could not create Android assets directory "%ASSETS_DIR%". 1>&2
    goto finish
)

set "KEY_FILE=%GAME_DIR%\GameData\walkure.keys"
set "KEY_SIZE=0"
if exist "%KEY_FILE%" for %%I in ("%KEY_FILE%") do set "KEY_SIZE=%%~zI"
if "%KEY_SIZE%"=="512" goto key_ready
if not exist "%GAME_DIR%\DLL\key.fkey" goto key_unavailable
if not exist "%GAME_DIR%\WALKURE_ROMANZE.exe" goto key_unavailable

echo Generating GameData\walkure.keys from the local game installation...
call "%PROJECT_ROOT%\gradlew.bat" -p "%PROJECT_ROOT%" -PwalkureGameDir="%GAME_DIR%" :run --args=export-keys
if errorlevel 1 goto failed
set "KEY_SIZE=0"
if exist "%KEY_FILE%" for %%I in ("%KEY_FILE%") do set "KEY_SIZE=%%~zI"
if "%KEY_SIZE%"=="512" goto key_ready

:key_unavailable
if /I "%OPTIONAL%"=="true" (
    echo Warning: portable keys unavailable; Android users will need to provide them separately.
) else (
    echo Warning: portable keys unavailable. Supply GameData\walkure.keys or the original DLL\key.fkey and WALKURE_ROMANZE.exe. 1>&2
)
goto restore_icon_without_key

:key_ready
copy /y "%KEY_FILE%" "%ASSETS_DIR%\walkure.keys" >nul
if errorlevel 1 (
    echo Error: could not copy portable keys into Android assets. 1>&2
    goto finish
)
echo Restored portable decryption keys.

echo Extracting Mio's launcher portrait from the local game archive...
call "%PROJECT_ROOT%\gradlew.bat" -p "%PROJECT_ROOT%" -PwalkureGameDir="%GAME_DIR%" :run --args="extract chara_美桜.png %TEMP_NAME%\icons"
if errorlevel 1 goto failed
set "ICON_SOURCE=%TEMP_DIR%\icons\saveload\chara_美桜.png"
if not exist "%ICON_SOURCE%" goto icon_missing
call "%PROJECT_ROOT%\gradlew.bat" -p "%PROJECT_ROOT%" "-PlauncherIconSource=%ICON_SOURCE%" restoreLauncherIcons
if errorlevel 1 goto failed
goto restore_font

:restore_icon_without_key
echo Warning: launcher portrait not restored because portable game keys are unavailable.
goto restore_font

:icon_missing
echo Warning: Mio's save/load portrait was not found; keeping the generic launcher icon.

:restore_font
set "FONT_FILE="
if exist "%GAME_DIR%\WalkureCustomV4.ttf" set "FONT_FILE=%GAME_DIR%\WalkureCustomV4.ttf"
if not defined FONT_FILE if exist "%GAME_DIR%\GameData\WalkureCustomV4.ttf" set "FONT_FILE=%GAME_DIR%\GameData\WalkureCustomV4.ttf"
if not defined FONT_FILE goto no_font
copy /y "%FONT_FILE%" "%ASSETS_DIR%\WalkureCustomV4.ttf" >nul
if errorlevel 1 (
    echo Error: could not copy the custom font into Android assets. 1>&2
    goto finish
)
echo Restored the custom font.
goto restore_logo

:no_font
echo Warning: WalkureCustomV4.ttf not found; the APK will use its fallback font.

:restore_logo
set "LOGO_FILE="
if exist "%GAME_DIR%\ricotta_logo.mp4" set "LOGO_FILE=%GAME_DIR%\ricotta_logo.mp4"
if not defined LOGO_FILE if exist "%GAME_DIR%\GameData\movie\ricotta_logo.mp4" set "LOGO_FILE=%GAME_DIR%\GameData\movie\ricotta_logo.mp4"
if defined LOGO_FILE goto copy_logo
if exist "%ASSETS_DIR%\ricotta_logo.mp4" goto success
where ffmpeg.exe >nul 2>nul
if errorlevel 1 goto no_logo

echo Extracting and converting the startup logo from the game archive...
call "%PROJECT_ROOT%\gradlew.bat" -p "%PROJECT_ROOT%" -PwalkureGameDir="%GAME_DIR%" :run --args="extract ricotta_logo.ogv %TEMP_NAME%\logo"
if errorlevel 1 goto failed
if not exist "%TEMP_DIR%\logo\ricotta_logo.ogv" goto logo_not_in_archive
ffmpeg -loglevel error -y -i "%TEMP_DIR%\logo\ricotta_logo.ogv" -map 0:v:0 -map "0:a:0?" -c:v libx264 -preset fast -crf 22 -c:a aac -b:a 192k -movflags +faststart "%ASSETS_DIR%\ricotta_logo.mp4"
if errorlevel 1 (
    echo Warning: ffmpeg could not convert the startup logo.
    goto success
)
echo Restored the startup logo video.
goto success

:copy_logo
copy /y "%LOGO_FILE%" "%ASSETS_DIR%\ricotta_logo.mp4" >nul
if errorlevel 1 (
    echo Error: could not copy the startup logo into Android assets. 1>&2
    goto finish
)
echo Restored the startup logo video.
goto success

:logo_not_in_archive
echo Warning: ricotta_logo.ogv was not found in the game archives.
goto success

:no_logo
echo Warning: startup logo not restored; install ffmpeg to extract it from the game archive.
goto success

:no_gamedata
if /I "%OPTIONAL%"=="true" (
    echo No local GameData folder found; skipping owner-provided assets.
    set "EXIT_CODE=0"
    goto finish
)
echo Error: GameData folder not found under "%GAME_DIR%". Clone this repository into your game folder or pass --game-dir. 1>&2
goto finish

:no_packs
if /I "%OPTIONAL%"=="true" (
    echo No data*.pack files found in "%GAME_DIR%\GameData"; skipping owner-provided assets.
    set "EXIT_CODE=0"
    goto finish
)
echo Error: no data*.pack files found in "%GAME_DIR%\GameData". 1>&2
goto finish

:failed
echo Error: Gradle failed while restoring game assets. 1>&2
goto finish

:success
echo Asset restoration complete.
set "EXIT_CODE=0"

:finish
if defined TEMP_DIR if exist "%TEMP_DIR%\" rmdir /s /q "%TEMP_DIR%"
if defined OLD_CODEPAGE chcp %OLD_CODEPAGE% >nul
exit /b %EXIT_CODE%
