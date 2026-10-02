@echo off
rem Runs one JobRadar crawl and appends its output to a dated log file.
rem Called by the scheduled task that install-daily-crawl.ps1 creates; can also be run by hand.
rem Arguments: %1 = path to jobradar.jar, %2 = data directory (database, logs, optional profile.yml)

setlocal
set "JAR=%~1"
set "DATA=%~2"
if "%JAR%"=="" goto usage
if "%DATA%"=="" goto usage

if not exist "%DATA%\logs" mkdir "%DATA%\logs"
for /f %%d in ('powershell -NoProfile -Command "Get-Date -Format yyyy-MM-dd"') do set "TODAY=%%d"

rem Working directory = data dir, so a profile.yml placed there overrides the example profile.
cd /d "%DATA%"
java -jar "%JAR%" --spring.profiles.active=crawl ^
  "--spring.datasource.url=jdbc:h2:file:%DATA%\jobradar;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE" ^
  >> "%DATA%\logs\crawl-%TODAY%.log" 2>&1
exit /b %ERRORLEVEL%

:usage
echo Usage: run-crawl.cmd ^<path\to\jobradar.jar^> ^<data directory^>
exit /b 2
