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

@REM Extract filename from URL: take everything after last slash, strip .zip, strip -bin
set "URL_TAIL=%DISTRIBUTION_URL%"
:strip_slash
  set "PREV=%URL_TAIL%"
  for /f "tokens=1* delims=/" %%A in ("%URL_TAIL%") do set "URL_TAIL=%%B"
  if defined URL_TAIL goto :strip_slash
@REM PREV now holds the last path component, e.g. apache-maven-3.9.9-bin.zip
set "ZIP_FILENAME=%PREV%"
set "ZIP_NAME=%ZIP_FILENAME:.zip=%"
set "DIST_BASE=%ZIP_NAME:-bin=%"

set "INSTALL_DIR=%USERPROFILE%\.m2\wrapper\dists\%ZIP_NAME%"
set "MAVEN_HOME=%INSTALL_DIR%\%DIST_BASE%"

@REM Check whether a valid install exists (mvn.cmd + boot jar both present)
set "BOOT_JAR="
for %%J in ("%MAVEN_HOME%\boot\plexus-classworlds-*.jar") do set "BOOT_JAR=%%J"

if exist "%MAVEN_HOME%\bin\mvn.cmd" if defined BOOT_JAR goto :run

@REM Install (or re-install if boot jar was missing)
if exist "%INSTALL_DIR%" (
  echo Re-downloading Maven (previous install incomplete)...
  rmdir /s /q "%INSTALL_DIR%" 2>nul
)
echo Downloading Maven from %DISTRIBUTION_URL% ...
mkdir "%INSTALL_DIR%" 2>nul
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

powershell.exe -NoProfile -Command "Expand-Archive -Force -LiteralPath '!ZIPFILE!' -DestinationPath '!INSTALL_DIR!'"
del "!ZIPFILE!" 2>nul

@REM Verify boot jar after extraction
set "BOOT_JAR="
for %%J in ("%MAVEN_HOME%\boot\plexus-classworlds-*.jar") do set "BOOT_JAR=%%J"
if not defined BOOT_JAR (
  echo Waiting for filesystem flush...
  timeout /t 5 /nobreak >nul 2>nul
  for %%J in ("%MAVEN_HOME%\boot\plexus-classworlds-*.jar") do set "BOOT_JAR=%%J"
)
if not defined BOOT_JAR (
  echo Extraction failed: boot jar not found in %MAVEN_HOME%\boot 1>&2
  dir "%MAVEN_HOME%\boot" 1>&2
  exit /b 1
)

:run
call "%MAVEN_HOME%\bin\mvn.cmd" %*
exit /b %errorlevel%
