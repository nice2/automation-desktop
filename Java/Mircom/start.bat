@echo off
rem Run from this folder, even when started with "Run as administrator" (which starts in C:\Windows\System32)
cd /d "%~dp0"

rem start-admin.bat passes the normal user's Python packages folder, so Convert still finds pandas when elevated
if not "%~1"=="" set "PYTHONPATH=%~1"

javac -cp  ./dependencies/* Automation.java BotWindow.java ConfigBot.java FX400.java ZoneList.java Zone.java ./DataEntryBot.java FX2000.java Flexnet.java FX4000.java FsaePlan.java FsaeBot.java FsaePanel.java -d .
java --enable-native-access=ALL-UNNAMED -cp "./dependencies/*;." Automation
PAUSE
