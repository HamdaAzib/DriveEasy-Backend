param(
    [switch]$Build
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$javaCandidates = @()
if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { $javaCandidates += $env:JAVA_HOME }
$javaCandidates += Get-ChildItem (Join-Path $env:ProgramFiles 'Eclipse Adoptium') -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName
$javaCandidates += Get-ChildItem (Join-Path $env:ProgramFiles 'Java') -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName
$javaHome = $javaCandidates | Where-Object {
    $releaseFile = Join-Path $_ 'release'
    (Test-Path $releaseFile) -and ((Get-Content $releaseFile -Raw) -match 'JAVA_VERSION="21\.')
} | Select-Object -First 1
if (-not $javaHome) {
    throw 'JDK 21 was not found. Install JDK 21 before starting DriveEasy.'
}
$env:JAVA_HOME = $javaHome
$java = Join-Path $javaHome 'bin\java.exe'

$services = @(
    @{ Name = 'service-registry'; Port = 8761; Jar = 'service-registry-0.0.1-SNAPSHOT.jar' },
    @{ Name = 'config-server'; Port = 8888; Jar = 'config-server-0.0.1-SNAPSHOT.jar' },
    @{ Name = 'vehicule-service'; Port = 8081; Jar = 'vehicule-service-0.0.1-SNAPSHOT.jar' },
    @{ Name = 'client-service'; Port = 8082; Jar = 'client-service-0.0.1-SNAPSHOT.jar' },
    @{ Name = 'location-service'; Port = 8083; Jar = 'location-service-0.0.1-SNAPSHOT.jar' },
    @{ Name = 'api-gateway'; Port = 8080; Jar = 'api-gateway-0.0.1-SNAPSHOT.jar' }
)

function Test-Port([int]$Port) {
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $async = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne(300)) { return $false }
        $client.EndConnect($async)
        return $true
    }
    catch { return $false }
    finally { $client.Dispose() }
}

$missingArtifacts = @($services | Where-Object { -not (Test-Path (Join-Path $root "$($_.Name)\target\$($_.Jar)")) })
$needsBuild = $Build -or ($missingArtifacts.Count -gt 0)
if ($needsBuild) {
    $busyServices = @($services | Where-Object { Test-Port $_.Port })
    if ($busyServices.Count -gt 0) {
        $busyPorts = ($busyServices | ForEach-Object { $_.Port }) -join ', '
        throw "Stop the DriveEasy services using ports $busyPorts before rebuilding, then run .\run-driveeasy.ps1 -Build again."
    }

    foreach ($service in $services) {
        Write-Host "Building $($service.Name)..."
        $originalJar = Join-Path $root "$($service.Name)\target\$($service.Jar).original"
        if (Test-Path -LiteralPath $originalJar) { Remove-Item -LiteralPath $originalJar -Force }
        Push-Location (Join-Path $root $service.Name)
        try {
            & .\mvnw.cmd -DskipTests package
            if ($LASTEXITCODE -ne 0) { throw "Build failed for $($service.Name)." }
        }
        finally {
            Pop-Location
        }
    }
}

$logDir = Join-Path $env:TEMP 'DriveEasy-logs'
New-Item -ItemType Directory -Path $logDir -Force | Out-Null

foreach ($service in $services) {
    if (Test-Port $service.Port) {
        Write-Host "$($service.Name) is already listening on $($service.Port)."
        continue
    }

    $moduleDir = Join-Path $root $service.Name
    $jarPath = Join-Path $moduleDir "target\$($service.Jar)"
    $stdout = Join-Path $logDir "$($service.Name).out.log"
    $stderr = Join-Path $logDir "$($service.Name).err.log"
    $process = Start-Process -FilePath $java `
        -ArgumentList @('-jar', $jarPath) `
        -WorkingDirectory $moduleDir `
        -WindowStyle Hidden `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr `
        -PassThru

    $ready = $false
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        Start-Sleep -Seconds 1
        if (Test-Port $service.Port) { $ready = $true; break }
        if ($process.HasExited) { break }
    }
    if (-not $ready) {
        $details = @()
        if (Test-Path $stderr) { $details += Get-Content $stderr -Tail 25 }
        if (Test-Path $stdout) { $details += Get-Content $stdout -Tail 25 }
        throw "Could not start $($service.Name). Logs are in $logDir`n$($details -join "`n")"
    }
    Write-Host "$($service.Name) is ready on port $($service.Port)."
}

Write-Host ''
Write-Host 'DriveEasy backend APIs are running. Gateway: http://localhost:8080'
Write-Host 'Start the frontend in a separate terminal: cd frontend; python -m http.server 4200'
Write-Host 'Then open http://localhost:4200'
Write-Host "Logs: $logDir"
