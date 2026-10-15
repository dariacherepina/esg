<#
.SYNOPSIS
  Container experiments 2.1 (single- vs multi-stage) and 2.2 (runtime base image).
  Measures build time, image size, pull time and startup time and writes every value to a CSV.

.DESCRIPTION
  Three image variants per service:
    single     Dockerfile             JDK + Maven + sources, Ubuntu        baseline
    multi-jre  Dockerfile.multistage  runtime eclipse-temurin:21-jre        2.1: single vs multi-jre = multi-stage alone
    multi      Dockerfile.multistage  runtime eclipse-temurin:21-jre-alpine 2.2: multi-jre vs multi  = Alpine alone

  Phases (run in this order, each can be run on its own with -Phases):
    build    docker build --no-cache, timed. Base images are pulled once beforehand and not timed.
    size     compressed size (what a pull transfers) and unpacked size (sum of layers), in MB (10^6 bytes)
    push     pushes the built images to the registry (needs `docker login ghcr.io` once)
    pull     removes ALL experiment images and base images locally, then times `docker pull`,
             so every pull starts from an empty cache like a fresh node
    startup  starts one container at a time (512 MB limit) against the compose Postgres/RabbitMQ;
             records time from `docker run` until /actuator/health is UP, and the JVM-reported
             application_ready_time_seconds. Warm-up runs are not recorded.

  Repeats are interleaved (run 1 of every image, then run 2, ...) so that network or machine
  load changes during the session spread over all variants instead of hitting one.

.EXAMPLE
  # quick test: one service, one run each (about 5 min)
  .\experiments\container\measure-images.ps1 -Services report-service -Repeats 1
.EXAMPLE
  # full run: 4 services x 3 variants x 5 repeats
  .\experiments\container\measure-images.ps1
.EXAMPLE
  # repeat only the pull measurement, 10 runs
  .\experiments\container\measure-images.ps1 -Phases pull -Repeats 10
#>
param(
    [ValidateSet('company-service', 'esg-score-service', 'portfolio-service', 'report-service')]
    [string[]] $Services = @('company-service', 'esg-score-service', 'portfolio-service', 'report-service'),

    [ValidateSet('single', 'multi-jre', 'multi')]
    [string[]] $Variants = @('single', 'multi-jre', 'multi'),

    [ValidateSet('build', 'size', 'push', 'pull', 'startup')]
    [string[]] $Phases = @('build', 'size', 'push', 'pull', 'startup'),

    [int] $Repeats = 5,
    [int] $StartupWarmup = 1,
    [string] $Registry = 'ghcr.io/dariacherepina',
    [string] $OutDir = (Join-Path $PSScriptRoot 'results')
)

$allServices = 'company-service', 'esg-score-service', 'portfolio-service', 'report-service'
$allVariants = 'single', 'multi-jre', 'multi'
$baseImages = 'maven:3.9-eclipse-temurin-21', 'eclipse-temurin:21-jre', 'eclipse-temurin:21-jre-alpine'

$variantDef = @{
    'single'    = @{ File = 'Dockerfile';            BuildArgs = @() }
    'multi-jre' = @{ File = 'Dockerfile.multistage'; BuildArgs = @('--build-arg', 'RUNTIME_IMAGE=eclipse-temurin:21-jre') }
    'multi'     = @{ File = 'Dockerfile.multistage'; BuildArgs = @() }
}

# Container settings for the startup phase: same as "Run the services as containers" in the README.
# The upstream URLs are only used for requests, not at startup.
$port = @{ 'company-service' = 8081; 'esg-score-service' = 8082; 'portfolio-service' = 8083; 'report-service' = 8084 }
$containerEnv = @{
    'company-service'   = @('DB_URL=jdbc:postgresql://esg-postgres:5432/company_db')
    'esg-score-service' = @('DB_URL=jdbc:postgresql://esg-postgres:5432/score_db', 'RABBITMQ_HOST=esg-rabbitmq',
                            'COMPANY_SERVICE_URL=http://company-service:8081')
    'portfolio-service' = @('DB_URL=jdbc:postgresql://esg-postgres:5432/portfolio_db', 'RABBITMQ_HOST=esg-rabbitmq',
                            'SCORE_SERVICE_URL=http://esg-score-service:8082')
    'report-service'    = @('RABBITMQ_HOST=esg-rabbitmq')
}
$network = 'esg_default'
$container = 'esg-startup-test'

# Numbers are written with a dot as decimal separator, whatever the Windows language settings are.
$inv = [Globalization.CultureInfo]::InvariantCulture
$utf8 = New-Object Text.UTF8Encoding $false

New-Item -ItemType Directory -Force $OutDir | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$csv = Join-Path $OutDir "measurements-$stamp.csv"
[IO.File]::WriteAllText($csv, "timestamp,metric,service,variant,run,value,unit`r`n", $utf8)

function LocalTag($s, $v)  { "${s}:$v" }
function RemoteTag($s, $v) { "$Registry/${s}:$v" }

function Write-Result($metric, $s, $v, $run, [double] $value, $unit) {
    $line = '{0},{1},{2},{3},{4},{5},{6}' -f (Get-Date -Format 's'), $metric, $s, $v, $run, $value.ToString('0.###', $inv), $unit
    [IO.File]::AppendAllText($csv, "$line`r`n", $utf8)
    Write-Host ('  {0,-16} {1,-18} {2,-10} run {3}: {4} {5}' -f $metric, $s, $v, $run, $value.ToString('0.##', $inv), $unit)
}

function Test-Image($img) {
    docker image inspect $img --format '{{.Id}}' 2>$null | Out-Null
    return $LASTEXITCODE -eq 0
}

function Remove-ExperimentImages {
    # Every tag of every service and variant plus the base images: they share layers
    # (e.g. the Ubuntu layer), and a shared layer still on disk would not be downloaded again.
    $all = foreach ($s in $allServices) { foreach ($v in $allVariants) { LocalTag $s $v; RemoteTag $s $v } }
    $present = @($all + $baseImages | Where-Object { Test-Image $_ })
    if ($present.Count -gt 0) { docker image rm -f @present 2>$null | Out-Null }
}

function Measure-Startup($s, $v, $run, [bool] $record) {
    $img = RemoteTag $s $v
    if (-not (Test-Image $img)) { docker pull -q $img | Out-Null }
    docker rm -f $container 2>$null | Out-Null

    $hostPort = 10000 + $port[$s]
    $runArgs = @('run', '-d', '--name', $container, '--network', $network, '--memory', '512m', '-p', "${hostPort}:$($port[$s])")
    foreach ($e in $containerEnv[$s]) { $runArgs += @('-e', $e) }

    $sw = [Diagnostics.Stopwatch]::StartNew()
    docker @runArgs $img | Out-Null
    $up = $false
    while ($sw.Elapsed.TotalSeconds -lt 180) {
        try {
            if ((Invoke-RestMethod "http://localhost:$hostPort/actuator/health" -TimeoutSec 2).status -eq 'UP') { $up = $true; break }
        } catch { }
        Start-Sleep -Milliseconds 200
    }
    $sw.Stop()

    if (-not $up) {
        Write-Warning "$s $v did not become healthy within 180 s, last log lines:"
        docker logs --tail 20 $container
        docker rm -f $container | Out-Null
        return
    }
    $metrics = (Invoke-WebRequest "http://localhost:$hostPort/actuator/prometheus" -UseBasicParsing).Content -split "`n"
    $readyLine = $metrics | Where-Object { $_ -like 'application_ready_time_seconds*' } | Select-Object -First 1
    docker rm -f $container | Out-Null

    if ($record) {
        Write-Result 'startup_wall' $s $v $run $sw.Elapsed.TotalSeconds 's'
        Write-Result 'startup_ready' $s $v $run ([double]::Parse(($readyLine.Trim() -split ' ')[-1], $inv)) 's'
    }
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')
Push-Location $repoRoot
try {
    if ($Phases -contains 'build') {
        Write-Host '== build: pulling base images once (not timed)'
        foreach ($b in $baseImages) { docker pull -q $b | Out-Null }
        for ($r = 1; $r -le $Repeats; $r++) {
            foreach ($s in $Services) { foreach ($v in $Variants) {
                $d = $variantDef[$v]
                $buildArgs = @('build', '--no-cache', '-q', '-f', "$s/$($d.File)") + $d.BuildArgs +
                             @('-t', (LocalTag $s $v), '-t', (RemoteTag $s $v), '.')
                $sw = [Diagnostics.Stopwatch]::StartNew()
                docker @buildArgs | Out-Null
                $sw.Stop()
                if ($LASTEXITCODE -ne 0) { Write-Warning "build failed: $s $v run $r"; continue }
                Write-Result 'build_time' $s $v $r $sw.Elapsed.TotalSeconds 's'
            } }
        }
    }

    if ($Phases -contains 'size') {
        Write-Host '== size'
        foreach ($s in $Services) { foreach ($v in $Variants) {
            $img = RemoteTag $s $v
            if (-not (Test-Image $img)) { $img = LocalTag $s $v }
            if (-not (Test-Image $img)) { Write-Warning "no image for $s $v - run the build phase first"; continue }
            $compressed = [double] (docker image inspect $img --format '{{.Size}}')
            $unpacked = (docker history $img --human=false --format '{{.Size}}' | ForEach-Object { [double] $_ } | Measure-Object -Sum).Sum
            Write-Result 'size_compressed' $s $v 1 ($compressed / 1e6) 'MB'
            Write-Result 'size_unpacked' $s $v 1 ($unpacked / 1e6) 'MB'
        } }
    }

    if ($Phases -contains 'push') {
        Write-Host "== push to $Registry"
        foreach ($s in $Services) { foreach ($v in $Variants) {
            $img = RemoteTag $s $v
            if (-not (Test-Image $img)) { Write-Warning "no image $img - run the build phase first"; continue }
            # GHCR sometimes answers a push with a transient 500, so retry
            for ($a = 1; $a -le 3; $a++) {
                docker push -q $img | Out-Null
                if ($LASTEXITCODE -eq 0) { Write-Host "  pushed $img"; break }
                Write-Warning "push of $img failed (attempt $a of 3), retrying in 10 s"
                Start-Sleep -Seconds 10
            }
        } }
    }

    if ($Phases -contains 'pull') {
        Write-Host '== pull (every pull starts with all experiment and base images removed)'
        docker builder prune -af 2>$null | Out-Null
        for ($r = 1; $r -le $Repeats; $r++) {
            foreach ($s in $Services) { foreach ($v in $Variants) {
                Remove-ExperimentImages
                $img = RemoteTag $s $v
                $sw = [Diagnostics.Stopwatch]::StartNew()
                docker pull -q $img | Out-Null
                $sw.Stop()
                if ($LASTEXITCODE -ne 0) { Write-Warning "pull failed: $img run $r"; continue }
                Write-Result 'pull_time' $s $v $r $sw.Elapsed.TotalSeconds 's'
            } }
        }
    }

    if ($Phases -contains 'startup') {
        Write-Host '== startup: starting Postgres + RabbitMQ'
        docker compose up -d --wait postgres rabbitmq 2>$null | Out-Null
        if ($StartupWarmup -gt 0) { Write-Host "  $StartupWarmup warm-up start(s) per image, not recorded" }
        foreach ($s in $Services) { foreach ($v in $Variants) {
            for ($w = 1; $w -le $StartupWarmup; $w++) { Measure-Startup $s $v 0 $false }
        } }
        for ($r = 1; $r -le $Repeats; $r++) {
            foreach ($s in $Services) { foreach ($v in $Variants) { Measure-Startup $s $v $r $true } }
        }
    }
} finally {
    Pop-Location
}

# ---------- Summary: n, mean, standard deviation, min, max per metric / service / variant ----------
$rows = @(Import-Csv $csv)
if ($rows.Count -eq 0) { Write-Host 'No measurements recorded.'; return }

$fmt = { param($x) ([double] $x).ToString('0.##', $inv) }
$summary = $rows | Group-Object metric, service, variant | ForEach-Object {
    $first = $_.Group[0]
    $vals = @($_.Group | ForEach-Object { [double]::Parse($_.value, $inv) })
    $stats = $vals | Measure-Object -Average -Minimum -Maximum
    $sd = 0
    if ($vals.Count -gt 1) {
        $sq = ($vals | ForEach-Object { ($_ - $stats.Average) * ($_ - $stats.Average) } | Measure-Object -Sum).Sum
        $sd = [math]::Sqrt($sq / ($vals.Count - 1))
    }
    [pscustomobject] @{
        metric = $first.metric; service = $first.service; variant = $first.variant; n = $vals.Count
        mean = & $fmt $stats.Average; sd = & $fmt $sd; min = & $fmt $stats.Minimum; max = & $fmt $stats.Maximum
        unit = $first.unit
    }
}
$summaryCsv = Join-Path $OutDir "summary-$stamp.csv"
$summary | Export-Csv $summaryCsv -NoTypeInformation -Encoding UTF8
$summary | Format-Table -AutoSize
Write-Host "Measurements: $csv"
Write-Host "Summary:      $summaryCsv"
