@REM ----------------------------------------------------------------------------
@REM Apache Maven Wrapper startup batch script
@REM
@REM How it works:
@REM   1. Check prerequisites (java, javac)
@REM   2. Check if Maven is cached in %USERPROFILE%\.m2\wrapper\dists\
@REM   3. If not, download Maven from the URL in maven-wrapper.properties
@REM   4. Run %MAVEN_HOME%\bin\mvn.cmd with all arguments
@REM
@REM Environment:
@REM   JAVA_HOME      - JDK install path (optional, uses PATH if not set)
@REM   MAVEN_OPTS     - JVM options passed to Maven
@REM ----------------------------------------------------------------------------
@echo off
chcp 65001 >nul 2>&1

setlocal enabledelayedexpansion

@REM ---- resolve project root ----
set "PROJECT_DIR=%~dp0"
if "%PROJECT_DIR:~-1%"=="\" set "PROJECT_DIR=%PROJECT_DIR:~0,-1%"

set "WRAPPER_PROPS=%PROJECT_DIR%\.mvn\wrapper\maven-wrapper.properties"
if not exist "%WRAPPER_PROPS%" (
    echo [ERROR] maven-wrapper.properties not found. 1>&2
    echo Expected: %WRAPPER_PROPS% 1>&2
    exit /b 1
)

@REM ---- read distributionUrl ----
set "DIST_URL="
for /f "usebackq tokens=1,* delims==" %%A in ("%WRAPPER_PROPS%") do (
    if /i "%%A"=="distributionUrl" set "DIST_URL=%%B"
)
if not defined DIST_URL (
    echo [ERROR] distributionUrl not found in %WRAPPER_PROPS% 1>&2
    exit /b 1
)

@REM Extract zip filename from URL (handle any URL format)
for /f "delims=" %%F in ('powershell -NoProfile -Command "([uri]'%DIST_URL%').Segments[-1]"') do set "ZIP_FILE=%%F"
set "ZIP_NAME=!ZIP_FILE:.zip=!"
set "DIST_BASE=!ZIP_NAME:-bin=!"
set "CACHE_ROOT=%USERPROFILE%\.m2\wrapper\dists\!ZIP_NAME!"
set "MAVEN_HOME=!CACHE_ROOT!\!DIST_BASE!"

@REM ---- find java ----
set "JAVA_EXE="
if defined JAVA_HOME (
    set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
    for /f "delims=" %%J in ('where java 2^>nul') do set "JAVA_EXE=%%J"
    if not defined JAVA_EXE (
        for /f "delims=" %%J in ('dir /b "C:\Program Files\Eclipse Adoptium\jdk-17*\bin\java.exe" 2^>nul') do set "JAVA_EXE=C:\Program Files\Eclipse Adoptium\%%~nxJ"
        if not defined JAVA_EXE (
            for /f "delims=" %%J in ('dir /b "C:\Program Files\Java\jdk-17*\bin\java.exe" 2^>nul') do set "JAVA_EXE=C:\Program Files\Java\%%~nxJ"
        )
    )
)

if not defined JAVA_EXE (
    echo [ERROR] Java not found. Please install JDK 17+ 1>&2
    echo   Set JAVA_HOME, or add java to PATH 1>&2
    exit /b 1
)

@REM ---- check Maven installation ----
set "BOOT_JAR="
for /f "delims=" %%J in ('dir /b "!MAVEN_HOME!\boot\plexus-classworlds-*.jar" 2^>nul') do set "BOOT_JAR=%%J"

if not exist "!MAVEN_HOME!\bin\mvn.cmd" set "BOOT_JAR="

@REM ---- download Maven if not cached ----
if not defined BOOT_JAR (
    echo [INFO] Downloading Maven... 1>&2
    echo [INFO] This is a one-time download. 1>&2
    if exist "!CACHE_ROOT!" (
        echo [INFO] Removing incomplete cache... 1>&2
        rmdir /s /q "!CACHE_ROOT!" 2>nul
    )
    mkdir "!CACHE_ROOT!" 2>nul

    set "ZIP_PATH=!CACHE_ROOT!\maven.zip"

    @REM Try curl first (Windows 10+ has it built-in)
    if exist "%SystemRoot%\System32\curl.exe" (
        curl -fSL "%DIST_URL%" -o "!ZIP_PATH!" 2>&1
        if errorlevel 1 (
            echo [WARN] curl failed, trying PowerShell... 1>&2
            del "!ZIP_PATH!" 2>nul
            powershell.exe -NoProfile -Command "Invoke-WebRequest -Uri '%DIST_URL%' -OutFile '!ZIP_PATH!' -UseBasicParsing"
        )
    ) else (
        powershell.exe -NoProfile -Command "Invoke-WebRequest -Uri '%DIST_URL%' -OutFile '!ZIP_PATH!' -UseBasicParsing"
    )

    if not exist "!ZIP_PATH!" (
        echo [ERROR] Maven download failed. 1>&2
        echo [ERROR] Check your internet connection. 1>&2
        exit /b 1
    )

    echo [INFO] Extracting Maven... 1>&2
    powershell.exe -NoProfile -Command "Expand-Archive -Force -LiteralPath '!ZIP_PATH!' -DestinationPath '!CACHE_ROOT!'"
    del "!ZIP_PATH!" 2>nul

    @REM Verify extraction
    for /f "delims=" %%J in ('dir /b "!MAVEN_HOME!\boot\plexus-classworlds-*.jar" 2^>nul') do set "BOOT_JAR=%%J"
    if not defined BOOT_JAR (
        echo [ERROR] Maven extraction failed. 1>&2
        echo [DEBUG] MAVEN_HOME=!MAVEN_HOME! 1>&2
        dir "!CACHE_ROOT!" 1>&2
        exit /b 1
    )
    echo [INFO] Maven ready. 1>&2
)

@REM ---- run Maven ----
set "MVN_CMD=!MAVEN_HOME!\bin\mvn.cmd"
if not exist "!MVN_CMD!" (
    echo [ERROR] mvn.cmd not found: !MVN_CMD! 1>&2
    dir "!MAVEN_HOME!\bin\" 1>&2
    exit /b 1
)

call "!MVN_CMD!" %*
exit /b %errorlevel%