<#
.SYNOPSIS
  Measurement Study 1 (single- vs multi-stage, runtime base image) and Experiment 2 (layer cache order).
  Measures build time, image size, pull time and startup time and writes every value to a CSV.

.DESCRIPTION
  Four image variants per service:
    single     Dockerfile             JDK + Maven + sources, Ubuntu         baseline
    multi-jdk  Dockerfile.multistage  runtime eclipse-temurin:21.0.13_9-jdk  single    vs multi-jdk = Maven/sources removed
    multi-jre  Dockerfile.multistage  runtime eclipse-temurin:21.0.13_9-jre  multi-jdk vs multi-jre = JDK -> JRE
    multi      Dockerfile.multistage  runtime ...:21.0.13_9-jre-alpine       multi-jre vs multi     = Ubuntu -> Alpine

  Phases (run in this order, each can be run on its own with -Phases; push and pull are not in the default):
    build    docker build --no-cache, timed. Base images are pulled once beforehand and not timed.
    size     compressed size (layer sizes in a local registry = what a pull transfers) and
             uncompressed size (sum of layers), in MB (10^6 bytes)
    push     pushes the built images to the registry (needs `docker login ghcr.io` once)
    pull     removes ALL experiment images and base images locally, then times `docker pull`,
             so every pull starts from an empty cache like a fresh node
    startup  starts one container at a time (512 MB limit) against the compose Postgres/RabbitMQ;
             records time from `docker run` until /actuator/health is UP, and the JVM-reported
             application_ready_time_seconds, then idle memory after -MemoryWarmupSeconds.
             Every variant runs with the same JVM flags (-JvmFlags). Warm-up runs are not recorded.
    cache    experiment 2: Dockerfile.cache-{wrong,right}[-mount] per service (order x BuildKit cache mount).
             S1 = cold build (--no-cache, cache mount cleared), S2 = rebuild after deleting the layer cache
             but keeping the cache mount (reused machine), S3 = rebuild after one .java file changed (then restored).
    load     k6 load test against esg-score-service: throughput, p99 latency, peak memory.

  Repeats are interleaved (run 1 of every image, then run 2, ...) so that network or machine
  load changes during the session spread over all variants instead of hitting one.

.EXAMPLE
  # quick test: one service, one run each (about 5 min)
  .\experiments\container\measure-images.ps1 -Services report-service -Repeats 1
.EXAMPLE
  # full run: 4 services x 4 variants x 5 repeats, experiment 2 and the load test
  .\experiments\container\measure-images.ps1
.EXAMPLE
  # repeat only the pull measurement, 10 runs
  .\experiments\container\measure-images.ps1 -Phases pull -Repeats 10
#>
param(
    [ValidateSet('company-service', 'esg-score-service', 'portfolio-service', 'report-service')]
    [string[]] $Services = @('company-service', 'esg-score-service', 'portfolio-service', 'report-service'),

    [ValidateSet('single', 'multi-jdk', 'multi-jre', 'multi')]
    [string[]] $Variants = @('single', 'multi-jdk', 'multi-jre', 'multi'),

    [ValidateSet('build', 'size', 'push', 'pull', 'startup', 'cache', 'load')]
    [string[]] $Phases = @('build', 'size', 'startup', 'load', 'cache'),

    [int] $Repeats = 5,
    [int] $StartupWarmup = 1,
    [string] $Label = '',
    [string] $JvmFlags = '-XX:+UseContainerSupport -XX:MaxRAMPercentage=75',
    [int] $MemoryWarmupSeconds = 30,
    [ValidateSet('cache-wrong', 'cache-right', 'cache-wrong-mount', 'cache-right-mount')]
    [string[]] $CacheVariants = @('cache-wrong', 'cache-right', 'cache-wrong-mount', 'cache-right-mount'),
    [int] $LoadRepeats = 5,
    [int] $LoadVUs = 50,
    [string] $LoadDuration = '60s',
    [string] $LoadWarmup = '10s',
    [string] $K6 = (Join-Path $PSScriptRoot 'tools\k6.exe'),
    [string] $Registry = 'ghcr.io/dariacherepina',
    [string] $OutDir = (Join-Path $PSScriptRoot 'results')
)

$allServices = 'company-service', 'esg-score-service', 'portfolio-service', 'report-service'
$allVariants = 'single', 'multi-jdk', 'multi-jre', 'multi'
# Pinned base images (tag for humans, digest for Docker): identical images on every run and machine.
$mavenImage  = 'maven:3.9.16-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320'
$jdkImage    = 'eclipse-temurin:21.0.13_9-jdk@sha256:db5cc6341a1d7bc40cdb51e1f0d9824f3bb2cc8107d29da421dc4df7d11f05f1'
$jreImage    = 'eclipse-temurin:21.0.13_9-jre@sha256:0b99b3376fbb50dbf9c0af8535fdd5b8eb0d3fdfc8b1e4967f383b6919f6901a'
$alpineImage = 'eclipse-temurin:21.0.13_9-jre-alpine@sha256:ac05c3c42b7c2f1a52c9bd52f01041fa1958231b15ebeae7420429cbc5baf474'
$baseImages  = $mavenImage, $jdkImage, $jreImage, $alpineImage

$variantDef = @{
    'single'    = @{ File = 'Dockerfile';            BuildArgs = @() }
    'multi-jdk' = @{ File = 'Dockerfile.multistage'; BuildArgs = @('--build-arg', "RUNTIME_IMAGE=$jdkImage") }
    'multi-jre' = @{ File = 'Dockerfile.multistage'; BuildArgs = @('--build-arg', "RUNTIME_IMAGE=$jreImage") }
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
$network = 'esg-net'
$container = 'esg-startup-test'

# Numbers are written with a dot as decimal separator, whatever the Windows language settings are.
$inv = [Globalization.CultureInfo]::InvariantCulture
$utf8 = New-Object Text.UTF8Encoding $false

New-Item -ItemType Directory -Force $OutDir | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
# The label keeps parallel CI jobs (one per service) from writing to the same file name.
$prefix = if ($Label) { "$Label-$stamp" } else { $stamp }
$csv = Join-Path $OutDir "measurements-$prefix.csv"
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

function Wait-Healthy($baseUrl, $timeoutSeconds) {
    # Same polling as in Measure-Startup: /actuator/health every 200 ms until UP.
    $sw = [Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $timeoutSeconds) {
        try {
            if ((Invoke-RestMethod "$baseUrl/actuator/health" -TimeoutSec 2).status -eq 'UP') { return $true }
        } catch { }
        Start-Sleep -Milliseconds 200
    }
    return $false
}

function ConvertTo-MiB($memUsage) {
    # "123.4MiB / 512MiB" (docker stats) -> 123.4
    $used = ($memUsage -split '/')[0]
    if ($used -match '([\d.]+)\s*([KMG])iB') {
        return [double]::Parse($Matches[1], $inv) * @{ K = 1 / 1024; M = 1; G = 1024 }[$Matches[2]]
    }
    return $null
}

function ConvertFrom-K6Duration($d) {
    # k6 duration string ("60s", "1m30s", "500ms") -> seconds
    $total = 0.0
    foreach ($m in [regex]::Matches($d, '([\d.]+)(ms|s|m|h)')) {
        $n = [double]::Parse($m.Groups[1].Value, $inv)
        $total += $n * @{ ms = 0.001; s = 1; m = 60; h = 3600 }[$m.Groups[2].Value]
    }
    return $total
}

function Read-K6Measure($path, $durationSeconds) {
    # Streams k6's gzipped JSON output (one JSON object per line) and keeps only data points of the
    # measure scenario. Lines are filtered with plain string checks, because a run writes millions of
    # lines and ConvertFrom-Json per line would take minutes in Windows PowerShell.
    $durations = New-Object 'System.Collections.Generic.List[double]'
    $requests = 0; $failed = 0
    $fs = [IO.File]::OpenRead($path)
    $reader = New-Object IO.StreamReader (New-Object IO.Compression.GZipStream($fs, [IO.Compression.CompressionMode]::Decompress))
    try {
        while ($null -ne ($line = $reader.ReadLine())) {
            if (-not $line.Contains('"type":"Point"') -or -not $line.Contains('"phase":"measure"')) { continue }
            if ($line.Contains('"metric":"http_reqs"')) { $requests++ }
            elseif ($line.Contains('"metric":"http_req_failed"')) { if ($line -match '"value":1[,}]') { $failed++ } }
            elseif ($line.Contains('"metric":"http_req_duration"')) {
                if ($line -match '"value":([0-9.eE+-]+)') { $durations.Add([double]::Parse($Matches[1], $inv)) }
            }
        }
    } finally { $reader.Dispose() }

    $p99 = $null
    if ($durations.Count -gt 0) {
        # same percentile definition as k6: linear interpolation between the two nearest ranks
        $durations.Sort()
        $i = 0.99 * ($durations.Count - 1)
        $lo = [math]::Floor($i); $hi = [math]::Ceiling($i)
        $p99 = $durations[$lo] + ($durations[$hi] - $durations[$lo]) * ($i - $lo)
    }
    return [pscustomobject] @{
        # k6's http_reqs rate is count / test time; here the time is the measure scenario only
        Throughput = $requests / $durationSeconds
        P99        = $p99
        Requests   = $requests
        Failed     = $failed
    }
}

function Measure-Startup($s, $v, $run, [bool] $record) {
    $img = RemoteTag $s $v
    if (-not (Test-Image $img)) { docker pull -q $img | Out-Null }
    docker rm -f $container 2>$null | Out-Null

    $hostPort = 10000 + $port[$s]
    $runArgs = @('run', '-d', '--name', $container, '--network', $network, '--memory', '512m', '-p', "${hostPort}:$($port[$s])")
    foreach ($e in $containerEnv[$s]) { $runArgs += @('-e', $e) }
    # Same JVM flags for every variant: JAVA_TOOL_OPTIONS is read by the JVM itself, so it also
    # works for the single-stage ENTRYPOINT; JAVA_OPTS is emptied so multi-stage doesn't add its own.
    $runArgs += @('-e', "JAVA_TOOL_OPTIONS=$JvmFlags", '-e', 'JAVA_OPTS=')

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

    # Memory at idle: wait until startup work (JIT, Hibernate, connection pools) has settled.
    $memMiB = $null
    if ($record) {
        Start-Sleep -Seconds $MemoryWarmupSeconds
        $mem = (docker stats $container --no-stream --format '{{.MemUsage}}') -split '/' | Select-Object -First 1
        if ($mem -match '([\d.]+)\s*([KMG])iB') {
            $memMiB = [double]::Parse($Matches[1], $inv) * @{ K = 1 / 1024; M = 1; G = 1024 }[$Matches[2]]
        }
    }
    docker rm -f $container | Out-Null

    if ($record) {
        Write-Result 'startup_wall' $s $v $run $sw.Elapsed.TotalSeconds 's'
        Write-Result 'startup_ready' $s $v $run ([double]::Parse(($readyLine.Trim() -split ' ')[-1], $inv)) 's'
        if ($memMiB) { Write-Result 'memory_idle' $s $v $run $memMiB 'MiB' }
    }
}

function Start-LocalRegistry {
    if (-not (docker ps -q -f 'name=^esg-registry$')) {
        docker rm -f esg-registry 2>$null | Out-Null
        docker pull -q registry:2.8.3 | Out-Null
        docker run -d --name esg-registry -p 5000:5000 registry:2.8.3 | Out-Null
        Start-Sleep -Seconds 2
    }
}

function Get-CompressedSize($img, $s, $v) {
    # Push to a local registry and add up the compressed layer sizes from its manifest:
    # exactly what a `docker pull` downloads, whatever storage Docker uses locally
    # (`docker image inspect` reports compressed size on Docker Desktop, uncompressed on Linux CI).
    $ref = "localhost:5000/${s}:$v"
    docker tag $img $ref
    docker push -q $ref | Out-Null
    $accept = 'application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json, ' +
              'application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.docker.distribution.manifest.v2+json'
    $get = {
        param($u)
        $c = (Invoke-WebRequest $u -Headers @{ Accept = $accept } -UseBasicParsing).Content
        if ($c -is [byte[]]) { $c = [Text.Encoding]::UTF8.GetString($c) }
        $c | ConvertFrom-Json
    }
    $m = & $get "http://localhost:5000/v2/$s/manifests/$v"
    if ($m.manifests) {   # an index (several platforms / attestations): pick the one for this machine
        $arch = docker version --format '{{.Server.Arch}}'
        $digest = ($m.manifests | Where-Object { $_.platform.os -eq 'linux' -and $_.platform.architecture -eq $arch } |
                   Select-Object -First 1).digest
        $m = & $get "http://localhost:5000/v2/$s/manifests/$digest"
    }
    docker rmi $ref | Out-Null
    return ($m.layers | Measure-Object size -Sum).Sum
}

# Join-Path twice instead of '..\..', so the path also works on Linux (GitHub Actions)
$repoRoot = (Resolve-Path (Join-Path (Join-Path $PSScriptRoot '..') '..')).Path
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
            Start-LocalRegistry
            $compressed = Get-CompressedSize $img $s $v
            $unpacked = (docker history $img --human=false --format '{{.Size}}' | ForEach-Object { [double] $_ } | Measure-Object -Sum).Sum
            Write-Result 'size_compressed' $s $v 1 ($compressed / 1e6) 'MB'
            Write-Result 'size_uncompressed' $s $v 1 ($unpacked / 1e6) 'MB'
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
    if ($Phases -contains 'cache') {
        Write-Host '== cache: S1 cold, S2 reused machine (no layer cache, warm mount), S3 incremental (src/ changed)'
        docker pull -q $mavenImage | Out-Null
        docker pull -q $alpineImage | Out-Null
        for ($r = 1; $r -le $Repeats; $r++) {
            foreach ($s in $Services) { foreach ($v in $CacheVariants) {
                $file = "$s/Dockerfile.$v"
                $tag = "${s}:$v"

                # S1: cold - no layer cache (--no-cache) and an empty cache mount (--no-cache keeps mounts)
                docker builder prune -f --filter type=exec.cachemount 2>$null | Out-Null
                $sw = [Diagnostics.Stopwatch]::StartNew()
                docker build --no-cache -q -f $file -t $tag . | Out-Null
                $sw.Stop()
                if ($LASTEXITCODE -ne 0) { Write-Warning "S1 failed: $s $v run $r"; continue }
                Write-Result 'cache_s1_cold' $s $v $r $sw.Elapsed.TotalSeconds 's'

                # S2: reused machine - layer cache deleted, cache mount from S1 kept (--no-cache would ignore the mount too)
                docker builder prune -af --filter type=regular 2>$null | Out-Null
                $sw = [Diagnostics.Stopwatch]::StartNew()
                docker build -q -f $file -t $tag . | Out-Null
                $sw.Stop()
                if ($LASTEXITCODE -ne 0) { Write-Warning "S2 failed: $s $v run $r"; continue }
                Write-Result 'cache_s2_reused' $s $v $r $sw.Elapsed.TotalSeconds 's'

                # S3: make sure the unchanged source is in the layer cache, then change one .java file
                docker build -q -f $file -t $tag . | Out-Null
                $src = Get-ChildItem (Join-Path $s 'src/main/java') -Recurse -Filter '*Application.java' | Select-Object -First 1
                $original = [IO.File]::ReadAllText($src.FullName)
                try {
                    # unique comment, so no run can reuse the cache of a previous run's change
                    [IO.File]::AppendAllText($src.FullName, "`n// cache experiment $v run $r $([guid]::NewGuid())`n")
                    $sw = [Diagnostics.Stopwatch]::StartNew()
                    docker build -q -f $file -t $tag . | Out-Null
                    $sw.Stop()
                    if ($LASTEXITCODE -ne 0) { Write-Warning "S3 failed: $s $v run $r"; continue }
                    Write-Result 'cache_s3_incremental' $s $v $r $sw.Elapsed.TotalSeconds 's'
                } finally {
                    [IO.File]::WriteAllText($src.FullName, $original)   # always restore the source file
                }
            } }
        }
        docker builder prune -f --filter type=exec.cachemount 2>$null | Out-Null   # leave no warm mount behind
    }
    if ($Phases -contains 'load') {
        $s = 'esg-score-service'   # the load test targets this service only
        $k6Script = Join-Path $PSScriptRoot 'load-test.js'
        if (-not (Test-Path $K6)) {
            Write-Warning "k6 not found at $K6 - load phase skipped (put k6.exe there or pass -K6 <path>)"
        } else {
            Write-Host "== load: k6 against $s, $LoadVUs VUs for $LoadDuration after $LoadWarmup warm-up; starting Postgres, RabbitMQ, company-service"
            # company-service is needed for seeding: POST /api/scores fetches the companies from it
            # (reachable as company-service:8081 on the compose network, = COMPANY_SERVICE_URL).
            docker compose up -d --wait postgres rabbitmq company-service 2>$null | Out-Null
            $hostPort = 10000 + $port[$s]   # 18082, same as in Measure-Startup
            $base = "http://localhost:$hostPort"
            $measureSeconds = ConvertFrom-K6Duration $LoadDuration
            for ($r = 1; $r -le $LoadRepeats; $r++) {
                foreach ($v in $Variants) {
                    $img = RemoteTag $s $v
                    if (-not (Test-Image $img)) { $img = LocalTag $s $v }
                    if (-not (Test-Image $img)) { Write-Warning "no image for $s $v - run the build phase first"; continue }

                    docker rm -f $container 2>$null | Out-Null
                    $runArgs = @('run', '-d', '--name', $container, '--network', $network, '--memory', '512m', '-p', "${hostPort}:$($port[$s])")
                    foreach ($e in $containerEnv[$s]) { $runArgs += @('-e', $e) }
                    $runArgs += @('-e', "JAVA_TOOL_OPTIONS=$JvmFlags", '-e', 'JAVA_OPTS=')   # same JVM flags as the startup phase
                    docker @runArgs $img | Out-Null

                    $memJob = $null
                    $tmpJson = Join-Path ([IO.Path]::GetTempPath()) "k6-$prefix-$v-$r.json.gz"   # .gz: k6 compresses it
                    try {
                        if (-not (Wait-Healthy $base 180)) {
                            Write-Warning "$s $v did not become healthy within 180 s, last log lines:"
                            docker logs --tail 20 $container
                            continue
                        }

                        # POST /api/scores fetches all companies from company-service and upserts one score
                        # per company (primary key = company id), so score_db always holds the same 20 rows.
                        # If company-service isn't reachable, the scores already stored in score_db are used.
                        try { Invoke-RestMethod -Method Post "$base/api/scores" -TimeoutSec 60 | Out-Null }
                        catch { Write-Warning "seeding failed ($($_.Exception.Message)) - using the scores already in score_db" }
                        # k6 requests ids 1-20: all of them must exist, or part of the load would be 404s
                        $ids = @(Invoke-RestMethod "$base/api/scores" -TimeoutSec 10 | ForEach-Object { $_.companyId })
                        $missing = @(1..20 | Where-Object { $ids -notcontains $_ })
                        if ($missing.Count -gt 0) {
                            Write-Warning "no score for company id(s) $($missing -join ', ') - $v run $r skipped"; continue
                        }
                        Start-Sleep -Seconds 5

                        # memory samples every 2 s while k6 runs; the peak is recorded
                        $memJob = Start-Job -ArgumentList $container -ScriptBlock {
                            param($c)
                            while ($true) { docker stats $c --no-stream --format '{{.MemUsage}}'; Start-Sleep -Seconds 2 }
                        }

                        # --quiet: no progress bar (it would flood the console and logs)
                        & $K6 run --quiet --env "BASE_URL=$base" --env "VUS=$LoadVUs" --env "DURATION=$LoadDuration" `
                            --env "WARMUP=$LoadWarmup" --out "json=$tmpJson" $k6Script | Out-Null
                        $k6Exit = $LASTEXITCODE

                        Stop-Job $memJob
                        $samples = @(Receive-Job $memJob | ForEach-Object { ConvertTo-MiB $_ } | Where-Object { $_ })
                        Remove-Job $memJob -Force; $memJob = $null

                        if ($k6Exit -ne 0) { Write-Warning "k6 failed (exit $k6Exit): $v run $r"; continue }
                        $res = Read-K6Measure $tmpJson $measureSeconds
                        if ($res.Failed -gt 0) {
                            Write-Warning ("{0} of {1} requests failed in {2} run {3} - check the service logs" -f $res.Failed, $res.Requests, $v, $r)
                        }
                        Write-Result 'load_throughput' $s $v $r $res.Throughput 'req/s'
                        if ($null -ne $res.P99) { Write-Result 'load_p99' $s $v $r $res.P99 'ms' }
                        if ($samples.Count -gt 0) {
                            Write-Result 'load_mem_peak' $s $v $r ($samples | Measure-Object -Maximum).Maximum 'MiB'
                        }
                    } finally {
                        if ($memJob) { Stop-Job $memJob; Remove-Job $memJob -Force }
                        docker rm -f $container 2>$null | Out-Null
                        Remove-Item $tmpJson -ErrorAction SilentlyContinue
                    }
                }
            }
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
$summaryCsv = Join-Path $OutDir "summary-$prefix.csv"
$summary | Export-Csv $summaryCsv -NoTypeInformation -Encoding UTF8
$summary | Format-Table -AutoSize
Write-Host "Measurements: $csv"
Write-Host "Summary:      $summaryCsv"
