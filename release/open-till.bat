@echo off
rem Opens the till in its own window (Microsoft Edge app mode, no address bar). Hap arkën në dritaren e vet.
cd /d "%~dp0"
set PORT=8081
for /f "tokens=2 delims==" %%p in ('findstr /r /c:"^ *server.port *=" "config\application.properties" 2^>nul') do set PORT=%%p
set PORT=%PORT: =%
start "" msedge --app=http://localhost:%PORT%/ --start-maximized
