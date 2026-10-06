@echo off
chcp 65001 >nul
rem Replaces the whole database with a backup. The current data is backed up first (backups\before-restore-...).
rem Usage: drag a backup file (.sql) from the backups folder onto this file, or: restore-backup.bat backups\file.sql
rem Zëvendëson gjithë databazën me një kopje rezervë. Të dhënat e tanishme ruhen më parë.
rem Përdorimi: tërhiqni një skedar .sql nga dosja backups mbi këtë skedar.
cd /d "%~dp0"

if "%~1"=="" (
    echo Tërhiqni një skedar kopjeje ^(.sql^) mbi restore-backup.bat.
    echo Drag a backup file ^(.sql^) onto restore-backup.bat.
    pause
    exit /b 1
)

echo.
echo KUJDES: të gjitha të dhënat do të zëvendësohen me kopjen:
echo WARNING: all data will be replaced by the backup:
echo    %~f1
echo.
set /p ANSWER=Shkruani PO për të vazhduar / Type PO to continue:
if /i not "%ANSWER%"=="PO" (
    echo U anulua. / Cancelled.
    pause
    exit /b 1
)

call "%~dp0stop.bat"
timeout /t 3 /nobreak >nul

echo Duke rikthyer... / Restoring...
start "" /wait "%~dp0HakaPOS\HakaPOS.exe" "--pos.restore.file=%~f1" --pos.restore.only=true --spring.main.web-application-type=none
if errorlevel 1 (
    echo.
    echo Rikthimi dështoi. Të dhënat e mëparshme janë ruajtur në backups\before-restore-... Shikoni logs\haka-pos.log.
    echo The restore failed. The data from before is saved in backups\before-restore-... See logs\haka-pos.log.
    pause
    exit /b 1
)

echo.
echo Kopja u rikthye. Programi po niset. / The backup was restored. Starting the program.
call "%~dp0start.bat"
