# Quita InstaTray de Windows: cierra la app y borra el programa, sus herramientas y los accesos directos.
# No toca los videos ni tus cookies. La configuración (%APPDATA%\InstaTray) también se queda, por si vuelves.
# Java, Python, ffmpeg y deno se quedan instalados (otras apps pueden usarlos): se quitan con "winget uninstall".
$app = Join-Path $env:LOCALAPPDATA 'InstaTray'
Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like '*InstaTray.jar*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
Start-Sleep -Seconds 1
foreach ($lnk in @((Join-Path ([Environment]::GetFolderPath('Programs')) 'InstaTray.lnk'),
                   (Join-Path ([Environment]::GetFolderPath('Startup')) 'InstaTray.lnk'))) {
    if (Test-Path $lnk) { Remove-Item $lnk }
}
if (Test-Path $app) { Remove-Item $app -Recurse -Force }
Write-Host 'InstaTray: desinstalado. Tus videos siguen en su carpeta.'
Read-Host 'Pulsa Enter para cerrar' | Out-Null
