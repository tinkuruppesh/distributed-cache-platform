# Windows PowerShell: build the engine and start one Spring Boot API (dev profile).
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot "..")

function Get-ListeningTcpPorts {
    foreach ($line in (netstat -ano -p tcp)) {
        if ($line -match '^\s*TCP\s+\S+:(\d+)\s+\S+\s+LISTENING\s+(\d+)\s*$') {
            [pscustomobject]@{ LocalPort = [int]$Matches[1]; OwningProcess = [int]$Matches[2] }
        }
    }
}

# Prevent two copies of this launcher from racing to open the same H2 file.
$launcherMutex = [System.Threading.Mutex]::new($false, 'Local\SelfHealingDistributedCacheBackend')
if (-not $launcherMutex.WaitOne(0)) {
    Write-Error 'Another backend launcher is already running. Do not start a second API against the same H2 file.'
    exit 1
}

try {
    $listeners = @(Get-ListeningTcpPorts)
    $apiListener = @($listeners | Where-Object LocalPort -eq 8081)
    $nodePorts = 7001..7006

    if ($apiListener.Count -gt 0) {
        $apiHealthy = $false
        try {
            $apiHealthy = (Invoke-RestMethod -Uri 'http://localhost:8081/actuator/health' -TimeoutSec 3).status -eq 'UP'
        } catch {
            $apiHealthy = $false
        }

        if ($apiHealthy) {
            $missingNodePorts = @($nodePorts | Where-Object {
                @($listeners | Where-Object LocalPort -eq $_).Count -eq 0
            })
            if ($missingNodePorts.Count -eq 0) {
                Write-Host 'The dev backend is already healthy on 8081 with cache nodes on 7001-7006. Leaving it running; no second instance was started.'
                return
            }

            Write-Error "The API is already healthy, but cache-node ports are missing: $($missingNodePorts -join ', '). Inspect the existing instance; this launcher will not start a duplicate."
            exit 1
        }

        $owners = ($apiListener | Select-Object -ExpandProperty OwningProcess -Unique) -join ', '
        Write-Error "Port 8081 is already occupied by PID(s) $owners, but the health endpoint is not UP. Inspect that process before starting another backend."
        exit 1
    }

    $occupiedNodePorts = @($nodePorts | Where-Object {
        @($listeners | Where-Object LocalPort -eq $_).Count -gt 0
    })
    if ($occupiedNodePorts.Count -gt 0) {
        Write-Error "Cache-node port(s) already have listeners: $($occupiedNodePorts -join ', '). Inspect them before starting embedded nodes."
        exit 1
    }

    $env:SPRING_PROFILES_ACTIVE = 'dev'
    $localMavenRepo = Join-Path $env:USERPROFILE '.m2\repository'
    mvn -B -q "-Dmaven.repo.local=$localMavenRepo" -Daether.syncContext.named.factory=rwlock-local -DskipTests install
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    # Recheck after the build in case another command started the API meanwhile.
    if (@(Get-ListeningTcpPorts | Where-Object LocalPort -eq 8081).Count -gt 0) {
        Write-Error 'Port 8081 became occupied during the build. Not starting a second API.'
        exit 1
    }

    mvn -B -pl management-api spring-boot:run
    exit $LASTEXITCODE
} finally {
    $launcherMutex.ReleaseMutex()
    $launcherMutex.Dispose()
}
