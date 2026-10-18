@echo off
setlocal EnableExtensions EnableDelayedExpansion

rem ============================================================================
rem  update-osm.bat  —  refresh the OpenStreetMap camera database
rem
rem  Entry point. Owns preflight, orchestration, staging, verification and the
rem  final swap, so a failed update can never leave the previous database broken.
rem  The fetching and digest comparison live in update-osm.ps1, because batch
rem  cannot express them without becoming unreadable.
rem
rem  The previous database is replaced only after a fresh one has been built and
rem  verified, and the old one is kept as .bak. An interrupted run therefore costs
rem  nothing but the download.
rem
rem  Usage:
rem    update-osm.bat                 full check and update
rem    update-osm.bat --force         refetch every district, ignoring the manifest
rem    update-osm.bat --verify        verify the existing database and exit
rem
rem  Overridable via environment: OSM_DIR, DATA_DIR, SPEEDCAM_FILE
rem ============================================================================

title SpeedCam - OSM data update

set "TOOLS=C:\cmdline-tools"
rem This script lives in tools\import, so the Gradle root is two levels up. %~dp0 already
rem ends with a backslash, so ".." has to be appended with a separator; resolving the
rem result canonically also strips the trailing "." that plain string surgery would leave.
for %%I in ("%~dp0..\..") do set "PROJECT=%%~fI"

rem Data lives inside the project so the whole thing can be moved or archived as one unit.
rem Both are overridable from the environment.
set "OSM_DIR=%OSM_DIR%"
if "%OSM_DIR%"=="" set "OSM_DIR=%PROJECT%\osm"
set "DATA_DIR=%DATA_DIR%"
if "%DATA_DIR%"=="" set "DATA_DIR=%PROJECT%\data"
set "SPEEDCAM_FILE=%SPEEDCAM_FILE%"

set "JDK=%TOOLS%\jdk17"
set "SDK=%TOOLS%\android-sdk"
set "SQLITE=%SDK%\platform-tools\sqlite3.exe"
set "GRADLE=%PROJECT%\gradlew.bat"
set "WORKER=%~dp0update-osm.ps1"

set "DB=%DATA_DIR%\russia.db"
set "STAGE=%TEMP%\speedcam-update-stage.db"
set "MANIFEST_IDENTITY=0585e653089d42d2ca2c716b9f1541ac"

set "MODE=check"
if /I "%~1"=="--force"   set "MODE=force"
if /I "%~1"=="--verify"  set "MODE=verify"

rem --- environment -------------------------------------------------------------
set "JAVA_HOME=%JDK%"
set "ANDROID_HOME=%SDK%"
set "ANDROID_SDK_ROOT=%SDK%"
set "PATH=%JDK%\bin;%PATH%"

echo.
echo  SpeedCam - OpenStreetMap camera data
echo  ============================================================
echo    project : %PROJECT%
echo    extracts: %OSM_DIR%
echo    database: %DB%
echo.

call :preflight || goto :fail

if "%MODE%"=="verify" goto :verify_only

rem --- step 1: fetch what changed ----------------------------------------------
echo [1/4] Checking for upstream changes...
echo.
set "FETCH_ARGS=/NoProfile /ExecutionPolicy Bypass -File "%WORKER%" -OsmDir "%OSM_DIR%""
if "%MODE%"=="force" set "FETCH_ARGS=%FETCH_ARGS% -Force"

powershell %FETCH_ARGS% > "%TEMP%\speedcam-fetch.txt" 2>&1
set "FETCH_RC=%ERRORLEVEL%"
type "%TEMP%\speedcam-fetch.txt"

if not "%FETCH_RC%"=="0" (
    rem 2 is the fetch script's own code for "Geofabrik unreachable", distinct from a crash.
    findstr /B /C:"UNREACHABLE" "%TEMP%\speedcam-fetch.txt" >nul
    if not errorlevel 1 (
        echo.
        echo   Geofabrik did not respond. Nothing was changed - try again later.
        exit /b 2
    )
    echo.
    echo   ERROR: the fetch step failed ^(rc=%FETCH_RC%^).
    echo   The existing database was left untouched.
    exit /b 1
)

set "UPSTREAM=other"
findstr /B /C:"UP-TO-DATE" "%TEMP%\speedcam-fetch.txt" >nul && set "UPSTREAM=fresh"

if "!UPSTREAM!"=="fresh" if exist "%DB%" (
    echo.
    echo   Nothing changed upstream and a database already exists.
    call :report_db
    echo.
    echo   Done - no update needed.
    exit /b 0
)

rem --- step 2: build a candidate database --------------------------------------
echo.
echo [2/4] Building a candidate database...
del /q "%STAGE%" "%STAGE%-wal" "%STAGE%-shm" >nul 2>&1

rem Inputs go through Gradle properties rather than --args. A long --args string with
rem quoted Windows paths does not survive a batch round-trip; the build script assembles
rem the argument list instead.
set "GRADLE_ARGS=:tools:import:run -PosmDir="%OSM_DIR%" -Poutput="%STAGE%""
if defined SPEEDCAM_FILE if exist "%SPEEDCAM_FILE%" (
    set "GRADLE_ARGS=!GRADLE_ARGS! -PspeedcamFile="%SPEEDCAM_FILE%""
    echo   including SpeedCam corpus: %SPEEDCAM_FILE%
)

call "%GRADLE%" !GRADLE_ARGS! --console=plain
if errorlevel 1 (
    echo.
    echo   ERROR: the import failed. The existing database was left untouched.
    exit /b 1
)

if not exist "%STAGE%" (
    echo.
    echo   ERROR: the import produced no database. The existing one was left untouched.
    exit /b 1
)

rem --- step 3: verify before touching anything ---------------------------------
echo.
echo [3/4] Verifying the candidate...
if not exist "%SQLITE%" (
    echo   WARNING: sqlite3 not found at %SQLITE%
    echo   Skipping structural checks and installing anyway.
    goto :swap
)

call :query "PRAGMA integrity_check;" "%STAGE%"
set "INTEGRITY=!RESULT!"
echo   integrity_check: !INTEGRITY!
if /I not "!INTEGRITY!"=="ok" (
    echo   ERROR: the candidate database is corrupt. The existing one was left untouched.
    exit /b 1
)

call :query "SELECT COUNT(*) FROM camera;" "%STAGE%"
set "CANDIDATE_CAMERAS=!RESULT!"
echo   cameras: !CANDIDATE_CAMERAS!
if "!CANDIDATE_CAMERAS!"=="0" (
    echo   ERROR: the candidate holds no cameras. The existing one was left untouched.
    exit /b 1
)

call :query "SELECT identity_hash FROM room_master_table WHERE id=42;" "%STAGE%"
set "CANDIDATE_HASH=!RESULT!"
echo   schema hash: !CANDIDATE_HASH!
if not "!CANDIDATE_HASH!"=="%MANIFEST_IDENTITY%" (
    echo   ERROR: schema hash mismatch - Room would refuse to open this file.
    echo     expected %MANIFEST_IDENTITY%
    echo     got      !CANDIDATE_HASH!
    exit /b 1
)

rem --- step 4: swap ------------------------------------------------------------
:swap
echo.
echo [4/4] Installing...
if not exist "%DATA_DIR%" mkdir "%DATA_DIR%"

if exist "%DB%" (
    del /q "%DB%.bak" >nul 2>&1
    move /y "%DB%" "%DB%.bak" >nul
    echo   previous database kept as russia.db.bak
)

move /y "%STAGE%" "%DB%" >nul
del /q "%STAGE%-wal" "%STAGE%-shm" >nul 2>&1
echo   installed %DB%

rem Record the schema identity the app will check on open.
"%SQLITE%" "%DB%" "PRAGMA optimize; VACUUM;" >nul 2>&1

echo.
call :report_db
echo.
echo  Done.
exit /b 0

rem =============================================================================
:verify_only
echo [verify] Checking the existing database...
if not exist "%DB%" (
    echo   ERROR: no database at %DB%
    exit /b 1
)
call :report_db
if "!INTEGRITY!"=="ok" (echo   Result: OK & exit /b 0) || (echo   Result: CORRUPT & exit /b 1)

rem -----------------------------------------------------------------------------
:preflight
if not exist "%JDK%\bin\java.exe" (
    echo   ERROR: JDK 17 not found at %JDK%& exit /b 1
)
if not exist "%SDK%" (
    echo   ERROR: Android SDK not found at %SDK%& exit /b 1
)
if not exist "%GRADLE%" (
    echo   ERROR: gradlew not found at %GRADLE%& exit /b 1
)
if not exist "%WORKER%" (
    echo   ERROR: update-osm.ps1 not found at %WORKER%& exit /b 1
)
where curl.exe >nul 2>&1
if errorlevel 1 (
    echo   ERROR: curl.exe is not on PATH. Geofabrik needs it, and it ships with Windows 10+.
    exit /b 1
)
exit /b 0

rem -----------------------------------------------------------------------------
rem =============================================================================
:query
rem Runs a single-column sqlite query and puts the result in RESULT.
rem   %1 = SQL statement, %2 = database file
rem
rem A temporary file rather than `for /f`: cmd treats a command whose first token is
rem quoted as a window title, so `for /f '`"%SQLITE%" ...`' cannot invoke an executable
rem from a quoted path at all. Redirecting and reading the file sidesteps that entirely.
set "RESULT="
if not exist "%~2" exit /b 1
"%SQLITE%" "%~2" "%~1" > "%TEMP%\speedcam-q.txt" 2>nul
if not exist "%TEMP%\speedcam-q.txt" exit /b 1
set /p RESULT=<"%TEMP%\speedcam-q.txt"
exit /b 0

rem =============================================================================
:report_db
set "INTEGRITY=unknown"
set "CAMERAS=0"
set "LIMITS=0"
set "FACING=0"
set "BUILT=never"
set "DBSIZE=unknown"

if exist "%DB%" if exist "%SQLITE%" (
    for %%A in ("%DB%") do set "DBSIZE=%%~zA"

    rem Delayed expansion is required: %RESULT% would be substituted while this block is
    rem parsed, before the calls below have run.
    call :query "PRAGMA integrity_check;" "%DB%"
    if defined RESULT set "INTEGRITY=!RESULT!"

    call :query "SELECT COUNT(*) FROM camera;" "%DB%"
    if defined RESULT set "CAMERAS=!RESULT!"

    call :query "SELECT COUNT(*) FROM camera WHERE limit_kmh IS NOT NULL;" "%DB%"
    if defined RESULT set "LIMITS=!RESULT!"

    call :query "SELECT COUNT(*) FROM camera WHERE facing_deg IS NOT NULL;" "%DB%"
    if defined RESULT set "FACING=!RESULT!"

    call :query "SELECT value FROM meta WHERE key='built_at';" "%DB%"
    if defined RESULT set "BUILT=!RESULT!"
)

echo.
echo   database : %DB%
if exist "%DB%" (
    call :mbsize
    echo     built  : %BUILT%
    echo     size   : %DBSIZE% ^(%CAMERAS% cameras^)
    echo     limits : %LIMITS% of %CAMERAS% records carry a speed limit
    echo     facing : %FACING% of %CAMERAS% records carry a direction
    echo     health : %INTEGRITY%
) else (
    echo     not present
)
exit /b 0

rem -----------------------------------------------------------------------------
:mbsize
set "DBSIZE=unknown"
for %%A in ("%DB%") do set "BYTES=%%~zA"
set /a KILOBYTES=%BYTES% / 1024
set /a MEGABYTES=%BYTES% / 1048576
if %MEGABYTES% geq 1 (
    set "DBSIZE=%MEGABYTES% MB"
) else (
    set "DBSIZE=%KILOBYTES% KB"
)
exit /b 0

rem -----------------------------------------------------------------------------
:fail
echo.
echo   Update aborted. Nothing was changed.
exit /b 1