# CHK Home MCP installer — no Render, no IIS changes.
$ErrorActionPreference = 'Stop'
$Host.UI.RawUI.WindowTitle = 'CHK Home MCP / installation locale'
$source = Join-Path $PSScriptRoot 'CHK-Home-MCP.exe'
$folder = Join-Path $env:LOCALAPPDATA 'CHKHomeMCP'
$binary = Join-Path $folder 'CHK-Home-MCP.exe'
Write-Host '=== CHK HOME MCP / SANS RENDER ===' -ForegroundColor Cyan
try {
    if (-not (Test-Path $source)) {
        throw 'Executable absent. Extraire le ZIP complet de GitHub Actions.'
    }
    New-Item -ItemType Directory -Force -Path $folder | Out-Null

    Get-CimInstance Win32_Process -Filter "name='CHK-Home-MCP.exe'" -ErrorAction SilentlyContinue |
      Where-Object { $_.ExecutablePath -and $_.ExecutablePath -ieq $binary } |
      ForEach-Object { Stop-Process -Id $_.ProcessId -ErrorAction SilentlyContinue }
    Start-Sleep -Milliseconds 600
    Copy-Item -LiteralPath $source -Destination $binary -Force
    $localTest = & $binary --once-test
    if ($LASTEXITCODE -ne 0 -or $localTest -notmatch '10 MCP tools') {
        throw "Le test MCP local a echoue : $localTest"
    }

    # A shortcut under the owner's Startup folder needs no administrator privilege.
    $shortcut = Join-Path ([Environment]::GetFolderPath('Startup')) 'CHK Home MCP.lnk'
    $shell = New-Object -ComObject WScript.Shell
    $link = $shell.CreateShortcut($shortcut)
    $link.TargetPath = $binary
    $link.WorkingDirectory = $folder
    $link.WindowStyle = 7
    $link.Description = 'Serveur MCP personnel sans Render'
    $link.Save()

    Start-Process -FilePath $binary -WorkingDirectory $folder -WindowStyle Hidden
    $healthy = $false
    for ($i = 0; $i -lt 15; $i++) {
        try {
            $health = Invoke-RestMethod 'http://127.0.0.1:8766/health' -TimeoutSec 2
            if ($health.ok -and $health.render -eq $false) { $healthy = $true; break }
        } catch {}
        Start-Sleep -Seconds 1
    }
    if (-not $healthy) { throw 'Serveur MCP local inaccessible : port 8766 ou logiciel bloque.' }
    $config = Get-Content -LiteralPath (Join-Path $folder 'config.json') -Raw | ConvertFrom-Json
    $token = [string]$config.secret
    $payload = @{jsonrpc='2.0'; id=1; method='tools/list'; params=@{}} | ConvertTo-Json -Compress
    $localUrl = 'http://127.0.0.1:8766/mcp/' + $token
    $check = Invoke-RestMethod -Method Post -Uri $localUrl -Body $payload -ContentType 'application/json' -TimeoutSec 6
    if ($check.result.tools.Count -ne 10) { throw 'Le MCP local ne repond pas correctement.' }
    Write-Host 'MCP local actif (10 outils, 0 appel Render).' -ForegroundColor Green

    $tailscale = Get-Command 'tailscale.exe' -ErrorAction SilentlyContinue |
      Select-Object -First 1 -ExpandProperty Source
    if (-not $tailscale) {
        $candidate = Join-Path $env:ProgramFiles 'Tailscale\tailscale.exe'
        if (Test-Path $candidate) { $tailscale = $candidate }
    }
    if (-not $tailscale) { throw 'Tailscale introuvable sur le PC. MCP local installe, HTTPS absent.' }
    $stateRaw = & $tailscale status --json
    if ($LASTEXITCODE -ne 0) { throw 'Tailscale PC non connecte.' }
    $state = $stateRaw | ConvertFrom-Json
    $dns = ([string]$state.Self.DNSName).TrimEnd('.')
    if ($dns -notlike '*.ts.net') { throw 'Nom DNS Tailscale indisponible.' }

    # Preserve the existing private IIS website on HTTPS 443: use only 8443.
    $oldStatus = (& $tailscale funnel status 2>&1 | Out-String)
    if ($oldStatus -match '8443') {
        throw 'Port Funnel 8443 deja configure. Aucun ecrasement autorise.'
    }
    Write-Host 'Activation Funnel : port dedie 8443, chemin /mcp (IIS 443 intact).'
    & $tailscale funnel --bg --https=8443 --set-path=/mcp http://127.0.0.1:8766
    if ($LASTEXITCODE -ne 0) { throw 'Tailscale Funnel refuse la publication HTTPS.' }
    $newStatus = (& $tailscale funnel status 2>&1 | Out-String)
    if ($newStatus -notmatch '8443') { throw 'Funnel 8443 non confirme.' }

    $url = 'https://' + $dns + ':8443/mcp/' + $token
    Set-Clipboard -Value $url
    Write-Host ''
    Write-Host 'ADRESSE MCP COPIEE DANS LE PRESSE-PAPIERS DU PC.' -ForegroundColor Green
    Write-Host 'ChatGPT > Plugins > Ajouter > URL du serveur > Ctrl+V'
    Write-Host 'Authentification : Sans authentification (cle longue dans URL).'
    Write-Host 'Test depuis ChatGPT requis avant confirmation de fonctionnement.'
    Write-Host 'Conserve cette adresse privee. Ne la montre pas en capture.'
    Write-Host 'Ni Render ni ton site IIS existant ne sont modifies.'
} catch {
    Write-Host ''
    Write-Host ('INSTALLATION INCOMPLETE : ' + $_.Exception.Message) -ForegroundColor Yellow
    Write-Host 'Aucune URL MCP publique validee.'
} finally {
    Write-Host ''
    Read-Host 'Entree pour fermer le diagnostic'
}
