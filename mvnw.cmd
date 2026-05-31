@REM ----------------------------------------------------------------------------
@REM Apache Maven Wrapper startup batch script
@REM Uses maven-wrapper.jar (committed to .mvn/wrapper/) to bootstrap Maven
@REM ----------------------------------------------------------------------------
@echo off
setlocal enabledelayedexpansion

set "MAVEN_PROJECTBASEDIR=%~dp0"
if "%MAVEN_PROJECTBASEDIR:~-1%"=="\" set "MAVEN_PROJECTBASEDIR=%MAVEN_PROJECTBASEDIR:~0,-1%"

set "WRAPPER_JAR=%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.jar"
set "WRAPPER_PROPS=%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.properties"

if not exist "%WRAPPER_JAR%" (
    echo Error: maven-wrapper.jar not found at %WRAPPER_JAR% 1>&2
    exit /b 1
)
if not exist "%WRAPPER_PROPS%" (
    echo Cannot find %WRAPPER_PROPS% 1>&2
    exit /b 1
)

@REM Find Java: try JAVA_HOME first, then fall back to PATH
set "JAVA_EXE="
if defined JAVA_HOME (
    set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
    if exist "!JAVA_EXE!" goto :run_maven
    echo [WARN] JAVA_HOME is set but java.exe not found at !JAVA_EXE! 1>&2
    set "JAVA_EXE="
)

@REM Try to find java on PATH
for /f "delims=" %%J in ('where java 2^>nul') do (
    set "JAVA_EXE=%%J"
    goto :run_maven
)

@REM Last resort: check common install locations
if exist "C:\Program Files\Eclipse Adoptium\jdk-17*\bin\java.exe" (
    for /f "delims=" %%J in ('dir /b "C:\Program Files\Eclipse Adoptium\jdk-17*\bin\java.exe" 2^>nul') do (
        set "JAVA_EXE=C:\Program Files\Eclipse Adoptium\%%J"
        goto :run_maven
    )
)
if exist "C:\Program Files\Java\jdk-17*\bin\java.exe" (
    for /f "delims=" %%J in ('dir /b "C:\Program Files\Java\jdk-17*\bin\java.exe" 2^>nul') do (
        set "JAVA_EXE=C:\Program Files\Java\%%J"
        goto :run_maven
    )
)

echo Java not found. Please install JDK 17+ and ensure java is on PATH 1>&2
echo JAVA_HOME=%JAVA_HOME% 1>&2
exit /b 1

:run_maven
@REM Run MavenWrapperMain - it handles downloading Maven automatically
"!JAVA_EXE!" -jar "%WRAPPER_JAR%" %*
exit /b %errorlevel%