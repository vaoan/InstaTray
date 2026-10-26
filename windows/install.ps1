# InstaTray para Windows: instala lo que hace falta y crea el acceso directo del menú Inicio.
# Lo abre "Instalar InstaTray.cmd" (doble clic). Se puede volver a abrir: actualiza lo que ya está.
#   Java 21+ (Temurin), Python 3, ffmpeg y deno con winget; yt-dlp y gallery-dl en un entorno de Python propio
#   (%LOCALAPPDATA%\InstaTray\tools), que es donde la app los busca.
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$app = Join-Path $env:LOCALAPPDATA 'InstaTray'
$venv = Join-Path $app 'tools'

function Say($text) { Write-Host "InstaTray: $text" }
function Quit($code) { Read-Host 'Pulsa Enter para cerrar' | Out-Null; exit $code }

function Winget-Install($id) {
    winget install --id $id -e --silent --disable-interactivity --accept-source-agreements --accept-package-agreements | Out-Host
}

function Refresh-Path {
    $env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' + [Environment]::GetEnvironmentVariable('Path', 'User')
}

if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
    Say 'Falta winget ("Instalador de aplicación" de Microsoft Store). Instálalo y vuelve a abrir este instalador.'
    Start-Process 'ms-windows-store://pdp/?productid=9NBLGGH4NNS1'
    Quit 1
}

# ---- Java 21 o más nuevo (javaw.exe: sin ventana de consola)
function Java-Major($javaw) {
    $java = Join-Path (Split-Path $javaw) 'java.exe'
    if (-not (Test-Path $java)) { return 0 }
    $out = (& $java -version 2>&1 | Out-String)
    if ($out -match 'version "(\d+)') { return [int]$Matches[1] }
    return 0
}
function Find-Javaw {
    $found = @()
    $cmd = Get-Command javaw -ErrorAction SilentlyContinue
    if ($cmd) { $found += $cmd.Source }
    foreach ($root in @("$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Microsoft", "$env:ProgramFiles\Java")) {
        if (Test-Path $root) {
            $found += Get-ChildItem $root -Recurse -Filter javaw.exe -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | ForEach-Object { $_.FullName }
        }
    }
    foreach ($j in $found) { if ((Java-Major $j) -ge 21) { return $j } }
    return $null
}
$javaw = Find-Javaw
if (-not $javaw) {
    Say 'Instalando Java…'
    Winget-Install 'EclipseAdoptium.Temurin.21.JRE'
    Refresh-Path
    $javaw = Find-Javaw
}
if (-not $javaw) { Say 'No se pudo instalar Java 21. Instálalo a mano (Temurin 21) y vuelve a abrir este instalador.'; Quit 1 }

# ---- Python (solo para crear el entorno de yt-dlp y gallery-dl; nunca el atajo de Microsoft Store)
function Find-Python {
    foreach ($base in @("$env:LOCALAPPDATA\Programs\Python", "$env:ProgramFiles")) {
        foreach ($minor in 14..9) {
            $p = Join-Path $base "Python3$minor\python.exe"
            if (Test-Path $p) { return $p }
        }
    }
    return $null
}
$venvPython = Join-Path $venv 'Scripts\python.exe'
if (-not (Test-Path $venvPython)) {
    $python = Find-Python
    if (-not $python) {
        Say 'Instalando Python…'
        Winget-Install 'Python.Python.3.12'
        $python = Find-Python
    }
    if (-not $python) { Say 'No se pudo instalar Python 3. Instálalo a mano y vuelve a abrir este instalador.'; Quit 1 }
    Say 'Preparando yt-dlp y gallery-dl…'
    New-Item -ItemType Directory -Force -Path $app | Out-Null
    & $python -m venv $venv
}
Say 'Instalando o actualizando yt-dlp y gallery-dl…'
& $venvPython -m pip install --disable-pip-version-check --quiet --upgrade yt-dlp gallery-dl

# ---- ffmpeg y deno
$links = Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Links'
foreach ($tool in @(@{ name = 'ffmpeg'; id = 'Gyan.FFmpeg' }, @{ name = 'deno'; id = 'DenoLand.Deno' })) {
    $there = (Get-Command $tool.name -ErrorAction SilentlyContinue) -or (Test-Path (Join-Path $links "$($tool.name).exe"))
    if (-not $there) { Say "Instalando $($tool.name)…"; Winget-Install $tool.id }
}

# ---- la app y su acceso directo
Say 'Copiando InstaTray…'
New-Item -ItemType Directory -Force -Path $app | Out-Null
Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like '*InstaTray.jar*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
Copy-Item (Join-Path $here 'InstaTray.jar') $app -Force
Copy-Item (Join-Path $here 'InstaTray.ico') $app -Force
$jar = Join-Path $app 'InstaTray.jar'

$shell = New-Object -ComObject WScript.Shell
function Make-Shortcut($path) {
    $s = $shell.CreateShortcut($path)
    $s.TargetPath = $javaw
    $s.Arguments = "-jar `"$jar`""
    $s.WorkingDirectory = $app
    $s.IconLocation = Join-Path $app 'InstaTray.ico'
    $s.Description = 'InstaTray: descarga los videos que copias'
    $s.Save()
}
$programs = [Environment]::GetFolderPath('Programs')
Make-Shortcut (Join-Path $programs 'InstaTray.lnk')
$startup = Join-Path ([Environment]::GetFolderPath('Startup')) 'InstaTray.lnk'
$answer = Read-Host '¿Abrir InstaTray al iniciar Windows? (s/n)'
if ($answer -match '^[sSyY]') { Make-Shortcut $startup } elseif (Test-Path $startup) { Remove-Item $startup }

Say 'Listo. InstaTray queda en el área de notificación (junto al reloj; si no lo ves, mira en la flecha ^).'
Start-Process $javaw -ArgumentList "-jar `"$jar`"" -WorkingDirectory $app
Start-Sleep -Seconds 2
