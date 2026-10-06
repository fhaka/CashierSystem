# Builds the package to install in a shop: target\HakaPOS-<version>.zip
#   HakaPOS\                       the program with its own Java (no Java needed on the shop computer)
#   config\application.properties.example
#   start.bat, stop.bat, open-till.bat, restore-backup.bat, install-autostart.bat
#   LEXO-MUA.txt, docs\mysql-setup.sql
#
# Needs a JDK 17+ with jpackage (set JAVA_HOME, or it looks in C:\Program Files\Java) and Maven.
# Run from the project folder:  powershell -ExecutionPolicy Bypass -File build-release.ps1 [-SkipTests]
param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

# --- Tools ---------------------------------------------------------------------------------------------------
$jdk = if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\jpackage.exe")) { $env:JAVA_HOME } else {
    Get-ChildItem 'C:\Program Files\Java' -Directory -Filter 'jdk*' -ErrorAction SilentlyContinue |
        Where-Object { Test-Path "$($_.FullName)\bin\jpackage.exe" } | Sort-Object Name -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $jdk) { throw 'No JDK with jpackage found. Install JDK 17 or newer, or set JAVA_HOME.' }

$mvn = (Get-Command mvn -ErrorAction SilentlyContinue).Source
if (-not $mvn) {
    $mvn = Get-ChildItem "$env:USERPROFILE\.m2\wrapper\dists" -Recurse -Filter 'mvn.cmd' -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $mvn) { throw 'Maven not found.' }

# --- Build the jar -------------------------------------------------------------------------------------------
$version = ([xml](Get-Content pom.xml)).project.version
Write-Host "Building Haka POS $version with $jdk"
$env:JAVA_HOME = $jdk
$mvnArgs = @('-q', 'clean', 'package')
if ($SkipTests) { $mvnArgs += '-DskipTests' }
& $mvn @mvnArgs
if ($LASTEXITCODE -ne 0) { throw 'Maven build failed.' }

$jar = "cashier-system-$version.jar"
# Safety: the program must not contain this computer's own settings or passwords.
$inside = & "$jdk\bin\jar.exe" tf "target\$jar"
if ($inside -match 'application-local\.properties|^config/') { throw 'The jar contains local settings; remove them first.' }

# --- Program with its own Java runtime -----------------------------------------------------------------------
$out = "target\release"
$package = "$out\HakaPOS-$version"
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$out\input", $package | Out-Null
Copy-Item "target\$jar" "$out\input\"

& "$jdk\bin\jpackage.exe" --type app-image --name HakaPOS --app-version $version `
    --input "$out\input" --main-jar $jar --dest $package `
    --java-options '-Dfile.encoding=UTF-8' --java-options '-Xmx768m' --java-options '-Duser.timezone=Europe/Tirane' `
    --vendor 'Haka Market' --description 'Supermarket cash register'
if ($LASTEXITCODE -ne 0) { throw 'jpackage failed.' }

# --- Scripts, settings template and guide --------------------------------------------------------------------
Copy-Item release\*.bat, release\LEXO-MUA.txt $package
New-Item -ItemType Directory -Force "$package\config", "$package\docs" | Out-Null
Copy-Item release\config\application.properties.example "$package\config\"
Copy-Item docs\mysql-setup.sql "$package\docs\"
# Windows batch files need CRLF line endings.
Get-ChildItem "$package\*.bat" | ForEach-Object {
    $text = [IO.File]::ReadAllText($_.FullName) -replace "`r?`n", "`r`n"
    [IO.File]::WriteAllText($_.FullName, $text, (New-Object Text.UTF8Encoding($false)))
}

$zip = "target\HakaPOS-$version.zip"
Remove-Item $zip -ErrorAction SilentlyContinue
# Windows' tar writes a standard zip (Compress-Archive in PowerShell 5 writes backslashes in the paths).
& "$env:SystemRoot\System32\tar.exe" -a -c -f $zip -C $out "HakaPOS-$version"
if ($LASTEXITCODE -ne 0) { throw 'Creating the zip failed.' }
Remove-Item "$out\input" -Recurse -Force
Write-Host "Done: $zip ($([math]::Round((Get-Item $zip).Length / 1MB)) MB)"
