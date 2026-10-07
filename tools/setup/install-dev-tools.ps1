# Installs the Meridian Core developer toolchain as portable copies under D:\DevTools (no admin needed).
# Re-runnable: tools that already exist are skipped. Usage:
#   powershell -ExecutionPolicy Bypass -File tools\setup\install-dev-tools.ps1 [-WithFlutter]
# -WithFlutter also installs the Flutter SDK (stable) into D:\DevTools\flutter for mobile/* (about 1.9 GB download;
# the archive's SHA-256 is checked against the official releases JSON; not added to PATH, see docs/DEV_TOOLS.md).
# JDK 25 is NOT put first on PATH and JAVA_HOME is NOT changed, so other projects keep their JDK.
# The build selects JDK 25 through Gradle toolchains (see gradle.properties).

param([switch]$WithFlutter)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$Root = 'D:\DevTools'
$Bin  = Join-Path $Root 'bin'
New-Item -ItemType Directory -Force -Path $Root, $Bin | Out-Null
$Tmp = Join-Path $env:TEMP 'meridian-setup'
New-Item -ItemType Directory -Force -Path $Tmp | Out-Null
$Headers = @{ 'User-Agent' = 'meridian-setup' }

function Log($m) { Write-Host ("[{0}] {1}" -f (Get-Date -Format HH:mm:ss), $m) }

function Get-Zip($name, $url, $dest) {
    if (Test-Path $dest) { Log "$name already present at $dest"; return }
    $zip = Join-Path $Tmp "$name.zip"
    Log "Downloading $name from $url"
    Invoke-WebRequest -Uri $url -OutFile $zip -Headers $Headers -MaximumRedirection 10
    $x = Join-Path $Tmp "$name-x"
    if (Test-Path $x) { Remove-Item -Recurse -Force $x }
    Expand-Archive -Path $zip -DestinationPath $x -Force
    $items = @(Get-ChildItem $x)
    if ($items.Count -eq 1 -and $items[0].PSIsContainer) { Move-Item $items[0].FullName $dest } else { Move-Item $x $dest }
    Remove-Item $zip -Force
    Log "$name installed at $dest"
}

function Get-Exe($name, $url, $dest) {
    if (Test-Path $dest) { Log "$name already present"; return }
    Log "Downloading $name"
    Invoke-WebRequest -Uri $url -OutFile $dest -Headers $Headers -MaximumRedirection 10
    Log "$name installed at $dest"
}

function Latest-GitHub($repo) {
    (Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/latest" -Headers $Headers).tag_name
}

# --- JDK 25 (Eclipse Temurin) ---------------------------------------------------------------
Get-Zip 'jdk-25' 'https://api.adoptium.net/v3/binary/latest/25/ga/windows/x64/jdk/hotspot/normal/eclipse' (Join-Path $Root 'jdk-25')

# --- Gradle (current release) ---------------------------------------------------------------
$g = Invoke-RestMethod 'https://services.gradle.org/versions/current'
Get-Zip "gradle-$($g.version)" $g.downloadUrl (Join-Path $Root 'gradle')

# --- kubectl ----------------------------------------------------------------------------------
$k = (Invoke-RestMethod 'https://dl.k8s.io/release/stable.txt').Trim()
Get-Exe 'kubectl' "https://dl.k8s.io/release/$k/bin/windows/amd64/kubectl.exe" (Join-Path $Bin 'kubectl.exe')

# --- Helm -------------------------------------------------------------------------------------
if (-not (Test-Path (Join-Path $Bin 'helm.exe'))) {
    $h = Latest-GitHub 'helm/helm'
    Get-Zip 'helm' "https://get.helm.sh/helm-$h-windows-amd64.zip" (Join-Path $Tmp 'helm-dist')
    Copy-Item (Join-Path $Tmp 'helm-dist\helm.exe') $Bin; Remove-Item -Recurse -Force (Join-Path $Tmp 'helm-dist')
}

# --- OpenTofu (Terraform-compatible IaC) ------------------------------------------------------
if (-not (Test-Path (Join-Path $Bin 'tofu.exe'))) {
    $t = Latest-GitHub 'opentofu/opentofu'; $tv = $t.TrimStart('v')
    Get-Zip 'tofu' "https://github.com/opentofu/opentofu/releases/download/$t/tofu_${tv}_windows_amd64.zip" (Join-Path $Tmp 'tofu-dist')
    Copy-Item (Join-Path $Tmp 'tofu-dist\tofu.exe') $Bin; Remove-Item -Recurse -Force (Join-Path $Tmp 'tofu-dist')
}

# --- Temporal CLI (workflow dev server + CLI) -------------------------------------------------
if (-not (Test-Path (Join-Path $Bin 'temporal.exe'))) {
    Get-Zip 'temporal' 'https://temporal.download/cli/archive/latest?platform=windows&arch=amd64' (Join-Path $Tmp 'temporal-dist')
    Copy-Item (Join-Path $Tmp 'temporal-dist\temporal.exe') $Bin; Remove-Item -Recurse -Force (Join-Path $Tmp 'temporal-dist')
}

# --- k9s (Kubernetes TUI) ---------------------------------------------------------------------
if (-not (Test-Path (Join-Path $Bin 'k9s.exe'))) {
    $k9 = Latest-GitHub 'derailed/k9s'
    Get-Zip 'k9s' "https://github.com/derailed/k9s/releases/download/$k9/k9s_Windows_amd64.zip" (Join-Path $Tmp 'k9s-dist')
    Copy-Item (Join-Path $Tmp 'k9s-dist\k9s.exe') $Bin; Remove-Item -Recurse -Force (Join-Path $Tmp 'k9s-dist')
}

# --- DBeaver Community (database GUI) ---------------------------------------------------------
Get-Zip 'dbeaver' 'https://dbeaver.io/files/dbeaver-ce-latest-win32.win32.x86_64.zip' (Join-Path $Root 'dbeaver')

# --- Flutter SDK (optional: -WithFlutter; mobile/customer_app, mobile/agent_app) ------------------------
if ($WithFlutter -and -not (Test-Path (Join-Path $Root 'flutter\bin\flutter.bat'))) {
    $base = 'https://storage.googleapis.com/flutter_infra_release/releases'
    $rel = Invoke-RestMethod "$base/releases_windows.json"
    $cur = $rel.releases | Where-Object { $_.hash -eq $rel.current_release.stable } | Select-Object -First 1
    $zip = Join-Path $Tmp 'flutter.zip'
    Log "Downloading Flutter $($cur.version) (stable)"
    Invoke-WebRequest -Uri "$base/$($cur.archive)" -OutFile $zip -Headers $Headers
    $sha = (Get-FileHash -Algorithm SHA256 $zip).Hash.ToLowerInvariant()
    if ($sha -ne $cur.sha256) { Remove-Item $zip -Force; throw "Flutter archive SHA-256 mismatch: $sha <> $($cur.sha256)" }
    Expand-Archive -Path $zip -DestinationPath $Root -Force   # the archive's top folder is flutter\
    Remove-Item $zip -Force
    & (Join-Path $Root 'flutter\bin\flutter.bat') --disable-analytics | Out-Null
    Log "Flutter $($cur.version) installed at $(Join-Path $Root 'flutter') (not on PATH; call it by full path)"
}

# --- User PATH (append only; never ahead of existing entries) ---------------------------------
$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
foreach ($p in @($Bin, (Join-Path $Root 'gradle\bin'))) {
    if (($userPath -split ';') -notcontains $p) { $userPath = ($userPath.TrimEnd(';') + ';' + $p); Log "Added $p to user PATH" }
}
[Environment]::SetEnvironmentVariable('Path', $userPath, 'User')
[Environment]::SetEnvironmentVariable('MERIDIAN_JDK25', (Join-Path $Root 'jdk-25'), 'User')

$ErrorActionPreference = 'Continue'
Log 'Versions:'
& (Join-Path $Root 'jdk-25\bin\java.exe') -version 2>&1 | Select-Object -First 1
& (Join-Path $Root 'gradle\bin\gradle.bat') --version 2>&1 | Select-String 'Gradle '
& (Join-Path $Bin 'kubectl.exe') version --client 2>&1 | Select-Object -First 1
& (Join-Path $Bin 'helm.exe') version --short
& (Join-Path $Bin 'tofu.exe') version 2>&1 | Select-Object -First 1
& (Join-Path $Bin 'temporal.exe') --version
& (Join-Path $Bin 'k9s.exe') version --short 2>&1 | Select-Object -First 1
if (Test-Path (Join-Path $Root 'flutter\bin\flutter.bat')) { & (Join-Path $Root 'flutter\bin\flutter.bat') --version 2>&1 | Select-Object -First 1 }
Log 'Done.'
