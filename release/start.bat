@echo off
chcp 65001 >nul
rem Starts the cash register program in the background and opens the till when it is ready.
rem (Ready = its port accepts connections; the server opens it only after starting completely.)
rem Nis programin e arkës dhe hap arkën kur të jetë gati.
cd /d "%~dp0"

if not exist "config\application.properties" (
    copy "config\application.properties.example" "config\application.properties" >nul
    echo Plotësoni cilësimet e databazës në config\application.properties, ruajeni dhe hapni përsëri start.bat.
    echo Fill in the database settings in config\application.properties, save it and run start.bat again.
    notepad "config\application.properties"
    exit /b 1
)

tasklist /fi "imagename eq HakaPOS.exe" | find /i "HakaPOS.exe" >nul
if not errorlevel 1 (
    echo Programi është tashmë i hapur. / The program is already running.
    call "%~dp0open-till.bat"
    exit /b 0
)

start "" "%~dp0HakaPOS\HakaPOS.exe"

echo Duke nisur programin... / Starting...
powershell -NoProfile -Command ^
  "$port = (Select-String -Path 'config\application.properties' -Pattern '^\s*server.port\s*=\s*(\d+)' | Select-Object -First 1).Matches.Groups[1].Value; if (-not $port) { $port = 8081 };" ^
  "for ($i = 0; $i -lt 90; $i++) { $c = New-Object Net.Sockets.TcpClient; try { if ($c.ConnectAsync('127.0.0.1', [int]$port).Wait(2000) -and $c.Connected) { exit 0 } } catch {} finally { $c.Dispose() }; Start-Sleep -Seconds 2 }; exit 1"
if errorlevel 1 (
    echo.
    echo Programi nuk u nis. Shikoni logs\haka-pos.log ose kontrolloni nëse MySQL është ndezur.
    echo The program did not start. See logs\haka-pos.log, and check that MySQL is running.
    pause
    exit /b 1
)
call "%~dp0open-till.bat"
