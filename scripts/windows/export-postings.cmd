@echo off
rem Writes every open, non-duplicate posting (with score and matched skills) to exports\postings.json in
rem the data directory, as one JSON array. Safe to run while the API or a crawl uses the database.
rem Arguments: 1 = data directory set up by install-daily-crawl.ps1, 2 = optional minimum score (0-1)
rem Uses JOBRADAR_JAVA and JOBRADAR_JAR from jobradar-env.cmd in the data directory.
rem Note: cmd expands percent signs even inside rem lines, so these comments must not contain any.

setlocal
if "%~1"=="" goto usage
set "DATA=%~f1"
if not exist "%DATA%\jobradar-env.cmd" (
  echo %DATA%\jobradar-env.cmd not found. Run install-daily-crawl.ps1 first.
  exit /b 2
)
call "%DATA%\jobradar-env.cmd"

set "MINSCORE="
if not "%~2"=="" set "MINSCORE=--jobradar.export.min-score=%~2"

cd /d "%DATA%"
"%JOBRADAR_JAVA%" -jar "%JOBRADAR_JAR%" --spring.profiles.active=export ^
  "--spring.datasource.url=jdbc:h2:file:%DATA%\jobradar;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE" ^
  "--jobradar.export.file=%DATA%\exports\postings.json" %MINSCORE% ^
  >> "%DATA%\logs\export.log" 2>&1
if errorlevel 1 (
  echo Export failed; see %DATA%\logs\export.log
  exit /b 1
)
echo Wrote %DATA%\exports\postings.json
exit /b 0

:usage
echo Usage: export-postings.cmd ^<data directory^> [minimum score 0-1]
exit /b 2
