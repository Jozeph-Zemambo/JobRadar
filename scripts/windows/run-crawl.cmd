@echo off
rem Runs one JobRadar crawl and appends its output to a dated log file.
rem Called by the scheduled task that install-daily-crawl.ps1 creates; can also be run by hand.
rem Arguments: 1 = path to jobradar.jar, 2 = data directory (database, logs, optional profile.yml)
rem Java: JOBRADAR_JAVA from jobradar-env.cmd in the data directory (written by the installer), else "java".
rem Note: cmd expands percent signs even inside rem lines, so these comments must not contain any.

setlocal
rem Make both paths absolute now, before the "cd" below would change what a relative path means.
set "JAR=%~f1"
set "DATA=%~f2"
if "%~1"=="" goto usage
if "%~2"=="" goto usage

set "JOBRADAR_JAVA=java"
if exist "%DATA%\jobradar-env.cmd" call "%DATA%\jobradar-env.cmd"

if not exist "%DATA%\logs" mkdir "%DATA%\logs"
for /f %%d in ('powershell -NoProfile -Command "Get-Date -Format yyyy-MM-dd"') do set "TODAY=%%d"

rem Working directory = data dir, so a profile.yml placed there overrides the example profile.
cd /d "%DATA%"
"%JOBRADAR_JAVA%" -jar "%JAR%" --spring.profiles.active=crawl ^
  "--spring.datasource.url=jdbc:h2:file:%DATA%\jobradar;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE" ^
  >> "%DATA%\logs\crawl-%TODAY%.log" 2>&1
exit /b %ERRORLEVEL%

:usage
echo Usage: run-crawl.cmd ^<path\to\jobradar.jar^> ^<data directory^>
exit /b 2
