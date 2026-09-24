$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location $projectRoot
try {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Install Docker with Compose before starting the stack.' }
    docker compose version | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose is unavailable.' }
    docker info *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Start Docker Desktop (Linux containers) and try again.' }
    if (-not (Test-Path -LiteralPath '.env')) {
        $passwordBytes = New-Object byte[] 32
        $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        try { $rng.GetBytes($passwordBytes) } finally { $rng.Dispose() }
        $databasePassword = [BitConverter]::ToString($passwordBytes).Replace('-', '').ToLowerInvariant()
        $template = [IO.File]::ReadAllText((Join-Path $projectRoot '.env.example'))
        [IO.File]::WriteAllText((Join-Path $projectRoot '.env'), $template.Replace('DATABASE_PASSWORD=', 'DATABASE_PASSWORD=' + $databasePassword))
    }
    docker compose config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Check .env and Compose configuration.' }
    docker compose up --build --detach --wait --wait-timeout 180
    if ($LASTEXITCODE -ne 0) { throw 'Startup failed; inspect docker compose logs.' }
    Write-Host 'Ready. Default URL: http://localhost:8088 (or WEB_PORT from .env).'
    Write-Host 'Stop with docker compose down; the database volume is retained.'
} finally { Pop-Location }
