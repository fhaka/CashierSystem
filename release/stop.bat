@echo off
chcp 65001 >nul
rem Stops the cash register program. Finish the sale in progress first.
rem Ndalon programin e arkës. Përfundoni më parë shitjen në vazhdim.
taskkill /im HakaPOS.exe /f >nul 2>nul
if errorlevel 1 (
    echo Programi nuk ishte i hapur. / The program was not running.
) else (
    echo Programi u ndal. / The program was stopped.
)
