@echo off
cd /d "%~dp0"
call "%~dp0mvnw.cmd" -Dtest=ResponseSanitizerTest test
