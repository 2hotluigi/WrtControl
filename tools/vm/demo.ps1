# Un router OpenWrt de prueba en este PC, para probar WrtControl sin router propio (Windows; en
# Linux y macOS, demo.sh, que hace lo mismo). Solo necesita QEMU (https://qemu.org/download/#windows).
#
#   powershell -ExecutionPolicy Bypass -File tools\vm\demo.ps1          # descarga y arranca
#   powershell -ExecutionPolicy Bypass -File tools\vm\demo.ps1 -Local   # solo para el emulador
#   powershell -ExecutionPolicy Bypass -File tools\vm\demo.ps1 -Reset   # empezar de cero
#
# Ver demo.sh: imagen oficial x86-64 comprobada con su sha256, red de usuario de QEMU con el puerto
# 8080 del PC en la web del router, y la primera vez una contraseña aleatoria antes de dejarse ver
# desde la red. Ctrl+C lo apaga.
param([switch]$Local, [switch]$Reset)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$Dir = if ($env:WRTC_DEMO_DIR) { $env:WRTC_DEMO_DIR } else { Join-Path $env:LOCALAPPDATA 'wrtcontrol-demo' }
$Port = if ($env:WRTC_DEMO_PORT) { [int]$env:WRTC_DEMO_PORT } else { 8080 }
$SshPort = if ($env:WRTC_DEMO_SSH_PORT) { [int]$env:WRTC_DEMO_SSH_PORT } else { 2222 }
$Downloads = 'https://downloads.openwrt.org'
$Router = '192.168.1.1'
$NoSession = '0' * 32
$Image = Join-Path $Dir 'openwrt.img'
$Console = Join-Path $Dir 'console.log'
$PasswordFile = Join-Path $Dir 'password'
$VersionFile = Join-Path $Dir 'version'

if ($Reset) {
    if (Test-Path $Dir) { Remove-Item -Recurse -Force $Dir }
    Write-Host 'Router de prueba borrado'
    exit 0
}

function Find-Qemu {
    $cmd = Get-Command qemu-system-x86_64 -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $default = Join-Path $env:ProgramFiles 'qemu\qemu-system-x86_64.exe'
    if (Test-Path $default) { return $default }
    throw 'Falta QEMU. Instálalo desde https://qemu.org/download/#windows (o: winget install SoftwareFreedomConservancy.QEMU).'
}

function Get-Image {
    if (Test-Path $Image) { return }
    $version = (Invoke-RestMethod "$Downloads/.versions.json").stable_version
    if (-not $version) { throw 'No se pudo saber la última versión de OpenWrt' }
    $base = "$Downloads/releases/$version/targets/x86/64"
    $file = "openwrt-$version-x86-64-generic-ext4-combined.img.gz"
    Write-Host "Descargando OpenWrt $version..."
    New-Item -ItemType Directory -Force $Dir | Out-Null
    $gz = Join-Path $Dir $file
    $sums = (Invoke-WebRequest "$base/sha256sums" -UseBasicParsing).Content
    $line = ($sums -split "`n") | Where-Object { $_.TrimEnd() -like "* `*$file" } | Select-Object -First 1
    if (-not $line) { throw "No está $file en sha256sums" }
    Invoke-WebRequest "$base/$file" -OutFile $gz -UseBasicParsing
    if ((Get-FileHash $gz -Algorithm SHA256).Hash -ne $line.Split(' ')[0].ToUpper()) {
        throw 'La imagen descargada no coincide con su sha256'
    }
    # Las imágenes de OpenWrt llevan la firma detrás del gzip: se lee solo el gzip.
    $tmp = "$Image.tmp"
    $in = [IO.File]::OpenRead($gz)
    $out = [IO.File]::Create($tmp)
    try {
        $unzip = New-Object IO.Compression.GZipStream($in, [IO.Compression.CompressionMode]::Decompress)
        try { $unzip.CopyTo($out) } catch [IO.InvalidDataException] { }
    } finally { $out.Close(); $in.Close() }
    if ((Get-Item $tmp).Length -eq 0) { throw 'No se pudo descomprimir la imagen' }
    Move-Item -Force $tmp $Image
    Remove-Item $gz
    Set-Content -Path $VersionFile -Value $version
}

# Arranca el router; $Bind es dónde escucha el puerto de la web (127.0.0.1 o 0.0.0.0).
function Start-Router([string]$Bind) {
    $net = "user,id=lan,net=192.168.1.0/24,host=192.168.1.2,dhcpstart=192.168.1.100," +
        "hostfwd=tcp:${Bind}:$Port-${Router}:80,hostfwd=tcp:127.0.0.1:$SshPort-${Router}:22"
    $arguments = @(
        '-m', '256', '-smp', '2',
        '-drive', "`"file=$Image,format=raw,if=virtio`"",
        '-netdev', $net, '-device', 'virtio-net-pci,netdev=lan',
        '-netdev', 'user,id=wan', '-device', 'virtio-net-pci,netdev=wan',
        '-display', 'none', '-monitor', 'none', '-serial', "`"file:$Console`""
    )
    Start-Process -FilePath $script:Qemu -ArgumentList ($arguments -join ' ') -WindowStyle Hidden -PassThru
}

function Invoke-Ubus([string]$Session, [string]$Object, [string]$Method, $Params) {
    $body = @{ jsonrpc = '2.0'; id = 1; method = 'call'; params = @($Session, $Object, $Method, $Params) } |
        ConvertTo-Json -Depth 5 -Compress
    Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$Port/ubus" -ContentType 'application/json' -Body $body -TimeoutSec 5
}

# Espera a que el router deje entrar con $Password y devuelve la sesión.
function Wait-Login($Process, [string]$Password) {
    for ($i = 0; $i -lt 120; $i++) {
        if ($Process.HasExited) { Get-Content $Console -Tail 30 -ErrorAction SilentlyContinue; throw 'QEMU se cerró' }
        try {
            $r = Invoke-Ubus $NoSession 'session' 'login' @{ username = 'root'; password = $Password }
            if ($r.result -and $r.result[0] -eq 0) { return $r.result[1].ubus_rpc_session }
        } catch { }
        Start-Sleep -Seconds 2
    }
    Get-Content $Console -Tail 30 -ErrorAction SilentlyContinue
    throw 'El router no arrancó'
}

# Apaga el router: escribe en el disco lo pendiente y cierra QEMU.
function Stop-Router($Process, [string]$Password) {
    if ($Process.HasExited) { return }
    try {
        $r = Invoke-Ubus $NoSession 'session' 'login' @{ username = 'root'; password = $Password }
        Invoke-Ubus $r.result[1].ubus_rpc_session 'file' 'exec' @{ command = '/bin/sync' } | Out-Null
    } catch { }
    Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue
    $Process.WaitForExit()
}

function New-Password {
    $chars = 'abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789'
    $bytes = New-Object byte[] 12
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })
}

$script:Qemu = Find-Qemu
Get-Image

# La primera vez: solo para este PC, contraseña aleatoria, y apagar.
if (-not (Test-Path $PasswordFile)) {
    Write-Host 'Primer arranque: poniendo una contraseña al router...'
    $p = Start-Router '127.0.0.1'
    $password = New-Password
    try {
        $session = Wait-Login $p ''
        $r = Invoke-Ubus $session 'luci' 'setPassword' @{ username = 'root'; password = $password }
        if (-not $r.result -or $r.result[0] -ne 0) { throw 'No se pudo poner la contraseña' }
    } finally { Stop-Router $p $password }
    Set-Content -Path $PasswordFile -Value $password
}

$password = (Get-Content $PasswordFile -TotalCount 1).Trim()
$bind = if ($Local) { '127.0.0.1' } else { '0.0.0.0' }
$p = Start-Router $bind
try {
    Wait-Login $p $password | Out-Null
    Write-Host ''
    Write-Host "Router de prueba listo: OpenWrt $((Get-Content $VersionFile -TotalCount 1).Trim())"
    Write-Host ''
    Write-Host '  En WrtControl, dirección:'
    Write-Host "    10.0.2.2:$Port          desde el emulador de Android de este PC"
    if (-not $Local) {
        # Las de las tarjetas con salida a internet (no las de VirtualBox, WSL...).
        Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway } | ForEach-Object { $_.IPv4Address.IPAddress } |
            ForEach-Object { Write-Host "    ${_}:$Port   desde un teléfono en la misma red que este PC" }
        Write-Host '    (si Windows pregunta, deja que QEMU acepte conexiones en redes privadas)'
    }
    Write-Host "  Usuario root, contraseña $password"
    Write-Host ''
    Write-Host "  LuCI: http://127.0.0.1:$Port/   SSH: ssh -p $SshPort root@127.0.0.1"
    Write-Host '  Ctrl+C para apagarlo. Lo que cambies se queda hasta -Reset.'
    # Sin bloquear en .NET, para que Ctrl+C llegue.
    while (-not $p.HasExited) { Start-Sleep -Seconds 1 }
} finally {
    Write-Host 'Apagando el router...'
    Stop-Router $p $password
}
