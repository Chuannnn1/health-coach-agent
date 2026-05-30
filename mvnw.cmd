@REM ----------------------------------------------------------------------------
@REM Apache Maven Wrapper startup batch script (script-only mode)
@REM ----------------------------------------------------------------------------
@echo off
setlocal enabledelayedexpansion

set "MAVEN_PROJECTBASEDIR=%~dp0"
if "%MAVEN_PROJECTBASEDIR:~-1%"=="\" set "MAVEN_PROJECTBASEDIR=%MAVEN_PROJECTBASEDIR:~0,-1%"

set "WRAPPER_PROPS=%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.properties"
if not exist "%WRAPPER_PROPS%" (
  echo Cannot find %WRAPPER_PROPS% 1>&2
  exit /b 1
)

set "DISTRIBUTION_URL="
for /f "usebackq tokens=1,* delims==" %%A in ("%WRAPPER_PROPS%") do (
  if /i "%%A"=="distributionUrl" set "DISTRIBUTION_URL=%%B"
)
if not defined DISTRIBUTION_URL (
  echo distributionUrl missing in %WRAPPER_PROPS% 1>&2
  exit /b 1
)

@REM Filename in URL ends with apache-maven-X.Y.Z-bin.zip. Strip .zip then -bin
@REM to derive the inner directory created by unzip (apache-maven-X.Y.Z).
for %%F in ("%DISTRIBUTION_URL%") do set "ZIP_NAME=%%~nF"
set "DIST_BASE=%ZIP_NAME:-bin=%"

set "INSTALL_DIR=%USERPROFILE%\.m2\wrapper\dists\%ZIP_NAME%"
set "MAVEN_HOME=%INSTALL_DIR%\%DIST_BASE%"

if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
  echo Downloading Maven from %DISTRIBUTION_URL% ...
  if not exist "%INSTALL_DIR%" mkdir "%INSTALL_DIR%" 2>nul
  set "ZIPFILE=%INSTALL_DIR%\maven.zip"

  if exist "%SystemRoot%\System32\curl.exe" (
    "%SystemRoot%\System32\curl.exe" -fSL "%DISTRIBUTION_URL%" -o "!ZIPFILE!"
  ) else (
    powershell.exe -NoProfile -Command "Invoke-WebRequest -Uri '%DISTRIBUTION_URL%' -OutFile '!ZIPFILE!' -UseBasicParsing"
  )
  if not exist "!ZIPFILE!" (
    echo Download failed. 1>&2
    exit /b 1
  )

  powershell.exe -NoProfile -Command "Expand-Archive -Force -LiteralPath '!ZIPFILE!' -DestinationPath '%INSTALL_DIR%'"
  del "!ZIPFILE!" 2>nul

  @REM Wait for filesystem to flush after extraction
  set "BOOT_OK="
  for /L %%W in (1,1,5) do (
    for %%J in ("%MAVEN_HOME%\boot\plexus-classworlds-*") do set "BOOT_OK=1"
    if defined BOOT_OK goto :bootReady
    echo Waiting for extraction to complete...
    timeout /t 2 /nobreak >nul 2>nul
  )
  :bootReady
)

if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
  echo Extraction did not produce %MAVEN_HOME%\bin\mvn.cmd 1>&2
  dir "%INSTALL_DIR%" 1>&2
  exit /b 1
)

call "%MAVEN_HOME%\bin\mvn.cmd" %*
exit /b %errorlevel%
