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
    echo Please run: git submodule update --init 1>&2
    exit /b 1
)
if not exist "%WRAPPER_PROPS%" (
    echo Cannot find %WRAPPER_PROPS% 1>&2
    exit /b 1
)

@REM Find Java
if defined JAVA_HOME (
    set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA_EXE=java"
)

if not exist "%JAVA_EXE%" (
    echo Java not found. JAVA_HOME=%JAVA_HOME% 1>&2
    echo Please install JDK 17+ and set JAVA_HOME 1>&2
    exit /b 1
)

@REM Run MavenWrapperMain using maven-wrapper.jar
@REM The wrapper jar contains MavenWrapperMain which downloads and runs Maven
"%JAVA_EXE%" -jar "%WRAPPER_JAR%" %*
exit /b %errorlevel%