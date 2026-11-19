[CmdletBinding()]
param(
    [ValidateRange(1, 65535)][int]$Port = $(if ($env:VORTEX_HTTP_PORT) { $env:VORTEX_HTTP_PORT } else { 8080 }),
    [string]$DataDir = $env:VORTEX_DATA_DIR,
    [switch]$NoBrowser,
    [string]$AppRoot,
    [string]$Jar = "vortex-app.jar"
)
$ErrorActionPreference = "Stop"
if (-not $AppRoot) { $AppRoot = $PSScriptRoot }
Push-Location -LiteralPath $AppRoot
$oldDataDir = $env:VORTEX_DATA_DIR
$browserJob = $null
$runtimeJunction = $null
try {
    if (-not (Test-Path -LiteralPath $Jar -PathType Leaf)) { throw "找不到应用文件 $Jar。请先将整个 ZIP 解压，再运行启动器。" }
    $bundledJava = Join-Path $AppRoot 'runtime/bin/java.exe'
    if (Test-Path -LiteralPath $bundledJava -PathType Leaf) {
        $runtimeRoot = $AppRoot
        # The bundled Windows JRE 21 uses the ANSI code page to find its native libraries.
        $ansi = [Text.Encoding]::Default  # Windows PowerShell invoked by Start Vortex.cmd
        if ($PSVersionTable.PSEdition -eq 'Core') {
            $codePage = (Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Control\Nls\CodePage').ACP
            $ansi = [Text.Encoding]::GetEncoding([int]$codePage)
        }
        if ($ansi.GetString($ansi.GetBytes($AppRoot)) -cne $AppRoot) {
            # Java expands an 8.3 alias back to the original name. Use an actual directory junction.
            $tempRoot = [IO.Path]::GetTempPath()
            $pathHint = '请把 ZIP 解压到可写的英文路径（如 D:\Vortex），或将 TEMP 设置为你自己的可写英文临时目录，再运行启动器。'
            if ($ansi.GetString($ansi.GetBytes($tempRoot)) -cne $tempRoot) { throw $pathHint }
            $linkPath = Join-Path $tempRoot ('vortex-' + [Guid]::NewGuid().ToString('N'))
            try { $null = New-Item -ItemType Junction -Path $linkPath -Target $AppRoot }
            catch { throw "无法在 $tempRoot 创建临时目录链接。$pathHint" }
            $runtimeJunction = $linkPath
            $runtimeRoot = $runtimeJunction
            if ($DataDir) { $DataDir = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($DataDir) }
            Set-Location -LiteralPath $runtimeRoot
        }
        $java = Join-Path $runtimeRoot 'runtime/bin/java.exe'
    } elseif ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe') -PathType Leaf)) {
        $java = Join-Path $env:JAVA_HOME 'bin/java.exe'
    } else {
        $java = (Get-Command java.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1).Source
    }
    if (-not $java) { throw '找不到 Java 21。请使用含 Java 的 Windows x64 包，或安装 64 位 Java 21 后重新打开此窗口。' }
    $version = & $java --version
    if ($LASTEXITCODE -ne 0 -or (($version | Select-Object -First 1) -notmatch '^(?:java|openjdk) 21(?:[.\s+-]|$)')) {
        throw "此版本需要 Java 21。当前程序：$java。请使用含 Java 的 Windows x64 包，或将 JAVA_HOME 指向 Java 21。"
    }

    $probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
    try {
        $probe.ExclusiveAddressUse = $true
        $probe.Start()
    } catch [Net.Sockets.SocketException] {
        throw "无法使用本机端口 $Port。如果 Vortex 已在运行，打开原工作台即可。若其他程序占用此端口，可在解压目录打开 PowerShell，运行：& '.\Start Vortex.cmd' -Port 8081（或其他空闲端口）。"
    } finally { $probe.Stop() }

    if (-not $DataDir) {
        $legacyDir = Join-Path $AppRoot 'data/vortex'
        if (Test-Path -LiteralPath $legacyDir -PathType Container) {
            $DataDir = $legacyDir
            Write-Host '检测到此目录中的旧版数据，将继续使用它。升级到其他目录前，请按 README.txt 备份并指定此位置。'
        } else {
            $profileData = if ($env:LOCALAPPDATA) { $env:LOCALAPPDATA } else { [Environment]::GetFolderPath('LocalApplicationData') }
            $DataDir = Join-Path $profileData 'Vortex/data'
        }
    }
    $env:VORTEX_DATA_DIR = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($DataDir)
    [void][IO.Directory]::CreateDirectory($env:VORTEX_DATA_DIR)
    $url = "http://127.0.0.1:$Port/"
    Write-Host "工作台就绪后访问 $url"
    Write-Host "记忆保存在 $env:VORTEX_DATA_DIR"
    Write-Host '保持窗口打开。需要停止服务时，请在此按 Ctrl+C。'
    if (-not $NoBrowser) {
        $browserJob = Start-Job -ArgumentList $url -ScriptBlock {
            param($baseUrl)
            $deadline = [DateTime]::UtcNow.AddMinutes(2)
            while ([DateTime]::UtcNow -lt $deadline) {
                try {
                    $request = [Net.HttpWebRequest]::Create($baseUrl + 'actuator/health/readiness')
                    $request.Proxy = $null
                    $request.Timeout = 1500
                    $result = $request.GetResponse()
                    $result.Close()
                    Start-Process $baseUrl
                    return
                } catch { Start-Sleep -Seconds 1 }
            }
        }
    }
    & $java --enable-preview -jar $Jar --spring.profiles.active=local --server.address=127.0.0.1 "--server.port=$Port" "--management.server.port=$Port"
    if ($LASTEXITCODE -ne 0) { throw "Vortex 已停止，Java 退出码为 $LASTEXITCODE。请查看上方日志。" }
} finally {
    if ($null -ne $browserJob) { Remove-Job -Job $browserJob -Force }
    $env:VORTEX_DATA_DIR = $oldDataDir
    Pop-Location
    if ($runtimeJunction) { [IO.Directory]::Delete($runtimeJunction) }
}
