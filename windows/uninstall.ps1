# Quita InstaTray de Windows: cierra la app y borra el programa, sus herramientas y los accesos directos.
# No toca los videos ni tus cookies. La configuración (%APPDATA%\InstaTray) también se queda, por si vuelves.
# Java, Python, ffmpeg y deno se quedan instalados (otras apps pueden usarlos): se quitan con "winget uninstall".
$app = Join-Path $env:LOCALAPPDATA 'InstaTray'

# Messages in the app's language (Ajustes → Idioma): the "language" setting, else Windows' display language
# (Spanish only if it is Spanish), like Lang.Choice in the app.
function Lang-English {
    $f = Join-Path $env:APPDATA 'InstaTray\settings.properties'
    if (Test-Path $f) {
        $line = Get-Content $f -ErrorAction SilentlyContinue | Where-Object { $_ -match '^language=' } | Select-Object -Last 1
        if ($line -match '^language=en') { return $true }
        if ($line -match '^language=es') { return $false }
    }
    return (Get-UICulture).TwoLetterISOLanguageName -ne 'es'
}
$english = Lang-English
# Msg 'español' 'English'
function Msg($es, $en) { if ($english) { $en } else { $es } }
Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like '*InstaTray.jar*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
Start-Sleep -Seconds 1
foreach ($lnk in @((Join-Path ([Environment]::GetFolderPath('Programs')) 'InstaTray.lnk'),
                   (Join-Path ([Environment]::GetFolderPath('Startup')) 'InstaTray.lnk'))) {
    if (Test-Path $lnk) { Remove-Item $lnk }
}
if (Test-Path $app) { Remove-Item $app -Recurse -Force }
Write-Host (Msg 'InstaTray: desinstalado. Tus videos siguen en su carpeta.' 'InstaTray: uninstalled. Your videos are still in their folder.')
Read-Host (Msg 'Pulsa Enter para cerrar' 'Press Enter to close') | Out-Null
