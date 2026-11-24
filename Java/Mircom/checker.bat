@echo off
rem Correlation checker: reads a configurator job (.mdb) and reports correlation mistakes. Never changes the job file;
rem the window's Correlate and LEDs tabs save changes to a NEW copy, loaded in the configurator with Import Job.
rem   checker.bat                       open the checker window
rem   checker.bat "job.mdb"             print the report for an exported job
rem   checker.bat "job.mdb" --csv out.csv --rules FSAE
rem Exit code (command line): 0 = no errors, 1 = errors found, 2 = could not check
setlocal
cd /d "%~dp0"
javac -encoding UTF-8 -cp "./dependencies/*" -d . checker/*.java
if errorlevel 1 (
    echo Could not compile the checker.
    PAUSE
    exit /b 2
)
if "%~1"=="" (
    start "" javaw --enable-native-access=ALL-UNNAMED -cp "./dependencies/*;." checker.CheckerWindow
    exit /b 0
)
java --enable-native-access=ALL-UNNAMED -cp "./dependencies/*;." checker.CorrelationChecker %*
exit /b %ERRORLEVEL%
