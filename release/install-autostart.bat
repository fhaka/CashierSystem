@echo off
chcp 65001 >nul
rem Starts the program automatically when this Windows user signs in (a shortcut in the Startup folder),
rem and puts an "Arka" shortcut on the desktop. Run it once. To undo, delete the two shortcuts.
rem Nis programin automatikisht kur hyn ky përdorues i Windows dhe vendos një ikonë "Arka" në desktop.
cd /d "%~dp0"
powershell -NoProfile -Command ^
  "$shell = New-Object -ComObject WScript.Shell;" ^
  "$startup = $shell.CreateShortcut([Environment]::GetFolderPath('Startup') + '\Haka POS.lnk');" ^
  "$startup.TargetPath = '%~dp0start.bat'; $startup.WorkingDirectory = '%~dp0'; $startup.WindowStyle = 7;" ^
  "$startup.IconLocation = '%~dp0HakaPOS\HakaPOS.exe'; $startup.Save();" ^
  "$desk = $shell.CreateShortcut([Environment]::GetFolderPath('Desktop') + '\Arka.lnk');" ^
  "$desk.TargetPath = '%~dp0start.bat'; $desk.WorkingDirectory = '%~dp0'; $desk.WindowStyle = 7;" ^
  "$desk.IconLocation = '%~dp0HakaPOS\HakaPOS.exe'; $desk.Save()"
echo Gati: programi niset vetë pas hyrjes në Windows; ikona "Arka" është në desktop.
echo Done: the program starts after signing in to Windows; the "Arka" shortcut is on the desktop.
pause
