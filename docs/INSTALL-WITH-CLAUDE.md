# Installing Haka POS with Claude

Instructions for Claude (Claude Code / Claude Desktop) running on a shop computer that should get Haka POS.
The person at the computer asks for one of:

- **Part A – main PC:** this computer runs the program and the database. Every shop has exactly one.
- **Part B – second till:** another computer in the same shop that only opens the main PC's till in a window.
  Nothing is installed on it except a shortcut.

Talk to the person in Albanian unless they write in English. Explain each step in one short sentence before you
do it, and say plainly what worked and what did not.

## Ground rules

- **Never handle the MySQL root password.** The person types it themselves (step A3). Do not ask for it, read it,
  or put it in a file or a command line.
- **Generate the program's own database password yourself** (random, 24+ characters). Write it only into
  `C:\HakaPOS\config\application.properties`. Do not show it in the chat; tell the person where it is stored.
- **Administrator steps** (installing MySQL, the firewall rule) show a Windows prompt. Tell the person a prompt
  is coming and that they must click *Yes*. Never try to get around it.
- **Before overwriting or deleting anything** in an existing `C:\HakaPOS`, stop and ask. An existing
  installation is updated, never reinstalled (see "Updating" at the end).
- Use `powershell` for commands. Paths contain spaces: quote them.
- If a step fails, stop, show the error in one sentence, and fix the cause before going on.

## What the person must have ready

| | Part A (main PC) | Part B (second till) |
|---|---|---|
| Windows 10 or 11 | yes | yes |
| The package `HakaPOS-<version>.zip` (USB stick, or GitHub access, see A1) | yes | no |
| Receipt printer and barcode scanner plugged in, printer driver installed | yes | scanner (printer: see B5) |
| The main PC's address in the shop network (you find it in A7) | – | yes |

---

## Part A – main PC

### A1. Get the package

Check, in this order:

1. A zip on a USB stick or in `Downloads`: `Get-ChildItem -Recurse -Filter "HakaPOS-*.zip"` on the stick and in
   `$env:USERPROFILE\Downloads`. Use the newest version.
2. Otherwise from GitHub (the repository is private): if `gh` (GitHub CLI) is installed and signed in
   (`gh auth status`), run
   `gh release download --repo fhaka/CashierSystem --pattern "HakaPOS-*.zip" --dir "$env:USERPROFILE\Downloads"`
   (latest release). If `gh` is missing or not signed in, ask the person to copy the zip onto a USB stick instead,
   or to sign in (`gh auth login --web`, which they complete in the browser).

If the release notes give a SHA-256, compare it: `(Get-FileHash <zip> -Algorithm SHA256).Hash`.

### A2. MySQL

Check whether MySQL is already installed and running:

```powershell
Get-Service | Where-Object { $_.Name -like '*mysql*' } | Select-Object Name, Status, StartType
Get-ChildItem "C:\Program Files\MySQL" -Recurse -Filter mysql.exe -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName
```

- **Running service found:** use it (MySQL 8.0 or newer works; 9.x works too). Note the path of `mysql.exe`.
- **Not installed:** ask the person to install it with the official installer, because the database setup
  (root password, Windows service) is a guided screen they must fill in themselves:
  1. Download "MySQL Installer for Windows" (or "MySQL Community Server" MSI) from https://dev.mysql.com/downloads/
     (the "No thanks, just start my download" link needs no account).
  2. Choose **Server only**, keep port **3306**, **Configure MySQL Server as a Windows Service**, **Start the
     service at system startup**: on.
  3. Set a **root password** and keep it somewhere safe (it is not needed every day, only for setup and repairs).
  4. Finish, then tell you. Check again with the commands above that the service is *Running* and *Automatic*.

### A3. Database and account for the program

Create the password and a ready-to-run setup file; the person runs it with the root password.

```powershell
$bytes = New-Object byte[] 24; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$appPass = [Convert]::ToBase64String($bytes) -replace '[+/=]', 'x'
```

Write `$env:TEMP\HakaPOS-setup.sql` (ASCII) with:

```sql
CREATE DATABASE IF NOT EXISTS cashier_system CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'haka_pos'@'localhost' IDENTIFIED BY '<appPass>';
ALTER USER 'haka_pos'@'localhost' IDENTIFIED BY '<appPass>';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES, DROP ON cashier_system.* TO 'haka_pos'@'localhost';
FLUSH PRIVILEGES;
```

(The program's own copy of this script is `docs\mysql-setup.sql` in the package.) Then ask the person to open a
**new** PowerShell window and run (with the real path of `mysql.exe` from A2):

```powershell
& "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe" -u root -p -e "source $($env:TEMP -replace '\\','/')/HakaPOS-setup.sql"
```

They type the root password when asked. Then check the account works (no root needed), and delete the setup file:

```powershell
& "<mysql.exe>" -u haka_pos "-p$appPass" -e "SELECT CURRENT_USER(); SHOW DATABASES LIKE 'cashier_system';"
Remove-Item "$env:TEMP\HakaPOS-setup.sql"
```

(If `ALTER USER` failed because an old `haka_pos` exists with other rights, show the error and ask.)

### A4. Unpack the program

```powershell
Test-Path C:\HakaPOS      # must be False for a new installation; if True, stop and ask (see "Updating")
tar -x -f "<zip>" -C C:\
Rename-Item "C:\HakaPOS-<version>" "C:\HakaPOS"
```

Use `tar` (built into Windows), not `Expand-Archive`.

### A5. Settings

Copy `C:\HakaPOS\config\application.properties.example` to `C:\HakaPOS\config\application.properties` and set:

- `spring.datasource.password=` the generated `$appPass`
- `server.port=8081` (leave as is unless the port is taken: `Get-NetTCPConnection -State Listen -LocalPort 8081`)
- `server.address=0.0.0.0` if there will be a second till, `127.0.0.1` if this is the only till
- `receipt.printer.name=` the exact Windows printer name of the receipt printer, if known
  (`Get-Printer | Select-Object Name`); it can also be chosen later in the program's Settings screen.

Write the file as UTF-8 without BOM:
`[IO.File]::WriteAllText($path, $text, (New-Object Text.UTF8Encoding($false)))`.

### A6. Start and check

Start it the way the person will (it waits until the program is ready, then opens the till window):

```powershell
Start-Process -FilePath "C:\HakaPOS\start.bat" -WorkingDirectory "C:\HakaPOS" -WindowStyle Minimized
```

Do not run `start.bat` with its output piped into your tool: the program inherits the pipe and the tool waits
until the program stops. Instead wait for the port (up to 3 minutes):

```powershell
for ($i = 0; $i -lt 90; $i++) { $c = New-Object Net.Sockets.TcpClient; try { if ($c.ConnectAsync('127.0.0.1', 8081).Wait(2000) -and $c.Connected) { 'ready'; break } } catch {} finally { $c.Dispose() }; Start-Sleep 2 }
Get-Content C:\HakaPOS\logs\haka-pos.log -Tail 20
```

The log must show `Started SupermarketCashierApplication`. If not, the cause is almost always in the last
lines: MySQL not running, wrong password in `application.properties`, or the port in use.

Then the person, in the till window:
1. Creates the **administrator account** (the first account, only possible once).
2. In **Cilësimet** (Settings): shop name, address, NIPT, phone, receipt printer, paper width (58 mm = 32
   characters, 80 mm = 42 or 48). The preview shows the receipt.
3. Prints a test receipt (sell any product, or reprint from the sales log) and checks the paper cut.

### A7. Second till ready (only if there will be one)

1. Find this PC's address in the shop network:
   `Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway } | Select-Object InterfaceAlias, @{n='IPv4';e={$_.IPv4Address.IPAddress}}`
   (the adapter with a router: Wi-Fi or Ethernet, usually `192.168.x.y`; ignore `vEthernet` / WSL / Docker adapters). Tell the person to **reserve this address for this PC in the router** (router menu
   "DHCP reservation" / "Static lease", using this PC's MAC address from `Get-NetAdapter`), so it never changes.
   You cannot do this part; they or the shop's technician do it.
2. Allow the port through the Windows firewall (administrator prompt: the person clicks *Yes*):

```powershell
Start-Process powershell -Verb RunAs -Wait -ArgumentList '-NoProfile', '-Command', 'netsh advfirewall firewall add rule name="Haka POS" dir=in action=allow protocol=TCP localport=8081 profile=private,domain'
netsh advfirewall firewall show rule name="Haka POS"
```

   The network must be set to **Private** in Windows (Settings → Network → the connection → Private), otherwise
   the rule does not apply. Check: `Get-NetConnectionProfile`.

### A8. Start with Windows, desktop icon

```powershell
Start-Process -FilePath "C:\HakaPOS\install-autostart.bat" -WorkingDirectory "C:\HakaPOS" -Wait
```

It creates the startup shortcut and the **Paneli i Shitjeve** icon on the desktop. Its window ends with "press any key": tell
the person to press a key there.

### A9. Backups

Tell the person: the program backs up the whole database every night at 23:00 and before every update, into
`C:\HakaPOS\backups` (the newest 30 are kept). They should **copy that folder to a USB stick or another computer
every week**: a backup on the same disk is lost if the disk breaks. Restore = drag a `.sql` file onto
`restore-backup.bat` and type `PO`.

### A10. Done – tell the person

- Daily use: the **Paneli i Shitjeve** icon (or `start.bat`); `stop.bat` stops the program.
- The till for a second PC: `http://<this PC's address>:8081` (Part B).
- Where the database password is stored (`C:\HakaPOS\config\application.properties`) and that the root
  password they chose is only for repairs.

---

## Part B – second till

Nothing is installed: the second PC opens the main PC's till in an app window. The main PC must be on.

### B1. The main PC's address

Ask for it (from A7, e.g. `192.168.1.10`), or ask the person to read it on the main PC.

### B2. Check the connection

```powershell
Test-NetConnection 192.168.1.10 -Port 8081 | Select-Object TcpTestSucceeded
```

If `False`: is the main PC on and the program running (Paneli i Shitjeve window open there)? Firewall rule added (A7) and the
main PC's network set to *Private*? Both PCs in the same network (same router / Wi-Fi)? `server.address=0.0.0.0`
in the main PC's settings?

### B3. Till shortcut on the desktop

```powershell
$shell = New-Object -ComObject WScript.Shell
$link = $shell.CreateShortcut([Environment]::GetFolderPath('Desktop') + '\Paneli i Shitjeve.lnk')
$link.TargetPath = (Get-Command msedge -ErrorAction SilentlyContinue).Source
if (-not $link.TargetPath) { $link.TargetPath = "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe" }
$link.Arguments = '--app=http://192.168.1.10:8081/ --start-maximized'
$link.Save()
```

Optionally the same shortcut in the Startup folder (`[Environment]::GetFolderPath('Startup')`) so the till opens
when Windows starts.

### B4. First use

Open the shortcut. Each cashier logs in with **their own account** (created by the administrator under
*Përdoruesit* on any till). Each cashier has their own 3 tabs (*Klienti 1, 2, 3*), their own shift and their own
cash count. Plug in the barcode scanner (it types like a keyboard; it must send Enter after the code).

### B5. Printing on this till's printer

Receipts are printed by the **main PC**. To print at the second till's own printer:

1. On the second PC: share the printer (Settings → Printers → the printer → Printer properties → Sharing →
   *Share this printer*, short name e.g. `ARKA2`).
2. On the main PC: add it (`Add-Printer -ConnectionName "\\<second PC name>\ARKA2"`, or Settings → Printers →
   Add a printer).
3. At the second till, choose that printer in the receipt window's printer list. The choice stays until the till
   window is reloaded; after that the printer from Cilësimet (shared by all tills) is offered again, so the
   cashier picks it again after each restart.

Test with a receipt.

### B6. Customer display (optional)

At the till: **Ekrani i klientit** opens a second window; drag it to the screen facing the customer and press F11.

---

## Updating an existing installation (main PC)

Never reinstall over an existing `C:\HakaPOS`. Update like this:

1. `C:\HakaPOS\stop.bat` (ask the person to finish any sale first).
2. Unpack the new zip to a temporary folder.
3. Rename `C:\HakaPOS\HakaPOS` to `HakaPOS.old-<old version>`, move the new `HakaPOS` folder in, and copy the new
   `*.bat`, `LEXO-MUA.txt`, `docs\*` and `config\application.properties.example` over the old ones.
   **Keep** `config\application.properties`, `backups`, `logs`.
4. Start as in A6. The log shows `Database backup written to ...\before-update-...` before any database change,
   and the version in the sidebar changes. Second tills need nothing: they get the new version on their next
   refresh.

## Troubleshooting

| Symptom | Look at |
|---|---|
| "Programi nuk u nis" | `C:\HakaPOS\logs\haka-pos.log`, last lines; MySQL service running? password in `config\application.properties`? |
| Port 8081 in use | `Get-NetTCPConnection -State Listen -LocalPort 8081` → change `server.port` and the shortcuts |
| Second till cannot connect | B2 checks |
| Receipt prints on the wrong printer | printer chosen in the receipt window / Cilësimet; Windows printer name |
| Windows "unknown publisher" warning | the program is not signed yet: *More info → Run anyway* |
