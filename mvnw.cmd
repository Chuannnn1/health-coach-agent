@REM ----------------------------------------------------------------------------
@REM Apache Maven Wrapper startup batch script (script-only mode), version 3.3.2
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

for /f "usebackq tokens=1,* delims==" %%A in ("%WRAPPER_PROPS%") do (
  if /i "%%A"=="distributionUrl" set "DISTRIBUTION_URL=%%B"
)
if not defined DISTRIBUTION_URL (
  echo distributionUrl missing in %WRAPPER_PROPS% 1>&2
  exit /b 1
)

for %%F in ("%DISTRIBUTION_URL%") do set "DIST_FILE=%%~nF"
set "INSTALL_DIR=%USERPROFILE%\.m2\wrapper\dists\%DIST_FILE%"
set "MAVEN_HOME=%INSTALL_DIR%\%DIST_FILE%"

if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
  echo Downloading Maven from %DISTRIBUTION_URL% ...
  if not exist "%INSTALL_DIR%" mkdir "%INSTALL_DIR%"
  powershell -NoProfile -Command "Invoke-WebRequest -Uri '%DISTRIBUTION_URL%' -OutFile '%INSTALL_DIR%\maven.zip' -UseBasicParsing"
  if errorlevel 1 (
    echo Download failed. 1>&2
    exit /b 1
  )
  powershell -NoProfile -Command "Expand-Archive -Force '%INSTALL_DIR%\maven.zip' '%INSTALL_DIR%'"
  del "%INSTALL_DIR%\maven.zip"
)

call "%MAVEN_HOME%\bin\mvn.cmd" %*
exit /b %errorlevel%
