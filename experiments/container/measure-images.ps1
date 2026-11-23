<#
.SYNOPSIS
  Measurement Study 1 (single- vs multi-stage, runtime base image).
  Measures build time, image size, pull time, startup time and runtime behaviour under load and writes every value to a CSV.

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
    startup  starts one container at a time (512 MB, -Cpus limit) against the compose Postgres/RabbitMQ;
             records time from `docker run` until /actuator/health is UP, and the JVM-reported
             application_ready_time_seconds, then idle memory after -MemoryWarmupSeconds.
             Every variant runs with the same JVM flags (-JvmFlags). Warm-up runs are not recorded.
    load     k6 concurrency series against esg-score-service: every -LoadLevels level x every -LoadVariants
             variant, -LoadRepeats times, in a random order per repeat (-Seed), each run in a fresh container.
             Records throughput, p99, CPU time per request, peak memory (docker stats and kernel RSS),
             JVM heap/threads and GC pauses (GC log); memory every 2 s goes to timeseries-*.csv.
             multi-jre-arena2 = the multi-jre image with MALLOC_ARENA_MAX=2 (glibc limited to 2 malloc arenas).
             -Nmt adds Native Memory Tracking (explanation runs only, it costs memory itself).
    soak     long run (-SoakDuration at -SoakLevel), same measurements plus the RSS trend (MiB per 10 min).

  Repeats are interleaved (run 1 of every image, then run 2, ...) so that network or machine
  load changes during the session spread over all variants instead of hitting one.
  Every CSV row has a level column (concurrent users); it is empty outside the load and soak phases.

.EXAMPLE
  # quick test: one service, one run each (about 5 min)
  .\experiments\container\measure-images.ps1 -Services report-service -Repeats 1
.EXAMPLE
  # image part: 4 services x 4 variants x 5 repeats (build, size, startup)
  .\experiments\container\measure-images.ps1 -Phases build,size,startup
.EXAMPLE
  # runtime part: concurrency series (6 levels x 3 variants x 10 runs, about 9 h), then the long runs
  .\experiments\container\measure-images.ps1 -Phases load
  .\experiments\container\measure-images.ps1 -Phases soak
.EXAMPLE
  # repeat only the pull measurement, 10 runs
  .\experiments\container\measure-images.ps1 -Phases pull -Repeats 10
#>
param(
    [ValidateSet('company-service', 'esg-score-service', 'portfolio-service', 'report-service')]
    [string[]] $Services = @('company-service', 'esg-score-service', 'portfolio-service', 'report-service'),

    [ValidateSet('single', 'multi-jdk', 'multi-jre', 'multi')]
    [string[]] $Variants = @('single', 'multi-jdk', 'multi-jre', 'multi'),

    [ValidateSet('build', 'size', 'push', 'pull', 'startup', 'load', 'soak')]
    [string[]] $Phases = @('build', 'size', 'startup', 'load'),

    [int] $Repeats = 5,
    [int] $StartupWarmup = 1,
    [string] $Label = '',
    [string] $JvmFlags = '-XX:+UseContainerSupport -XX:MaxRAMPercentage=75',
    [int] $MemoryWarmupSeconds = 30,
    [string] $Cpus = '2',   # CPU limit for startup, load and soak containers ('' = no limit)
    [ValidateSet('single', 'multi-jdk', 'multi-jre', 'multi-jre-arena2', 'multi')]
    [string[]] $LoadVariants = @('multi-jre', 'multi-jre-arena2', 'multi'),
    [int[]] $LoadLevels = @(10, 25, 50, 100, 200, 400),
    [int] $LoadRepeats = 10,
    [string] $LoadDuration = '60s',
    [string] $LoadWarmup = '30s',
    [int] $Seed = 20261123,   # random run order, fixed seed so the order is reproducible
    [int] $SoakLevel = 200,
    [string] $SoakDuration = '30m',
    [int] $SoakRepeats = 3,
    [switch] $Nmt,   # Native Memory Tracking in load/soak; costs memory itself, so not for the main runs
    [string] $K6 = (Join-Path $PSScriptRoot 'tools\k6.exe'),
    [string] $Registry = 'ghcr.io/dariacherepina',
    [string] $OutDir = (Join-Path $PSScriptRoot 'results')
)

# Docker writes notices to stderr; with 'Stop' (GitHub Actions default) Windows PowerShell would treat them as fatal.
$ErrorActionPreference = 'Continue'

$allServices = 'company-service', 'esg-score-service', 'portfolio-service', 'report-service'
$allVariants = 'single', 'multi-jdk', 'multi-jre', 'multi'
# Pinned base images (tag for humans, digest for Docker): identical images on every run and machine.
$mavenImage  = 'maven:3.9.16-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320'
$jdkImage    = 'eclipse-temurin:21.0.13_9-jdk@sha256:db5cc6341a1d7bc40cdb51e1f0d9824f3bb2cc8107d29da421dc4df7d11f05f1'
$jreImage    = 'eclipse-temurin:21.0.13_9-jre@sha256:0b99b3376fbb50dbf9c0af8535fdd5b8eb0d3fdfc8b1e4967f383b6919f6901a'
$alpineImage = 'eclipse-temurin:21.0.13_9-jre-alpine@sha256:ac05c3c42b7c2f1a52c9bd52f01041fa1958231b15ebeae7420429cbc5baf474'
$baseImages  = $mavenImage, $jdkImage, $jreImage, $alpineImage
$singleBuildImage = 'esg-maven:3.9.16-temurin-21.0.13'   # built locally from experiments/container/maven-temurin

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

# on battery Windows throttles the CPU, which makes every time and throughput value worthless
$battery = Get-CimInstance Win32_Battery -ErrorAction SilentlyContinue | Select-Object -First 1
if ($battery -and $battery.BatteryStatus -eq 1) { Write-Warning 'The laptop is running on battery: plug it in before measuring.' }

New-Item -ItemType Directory -Force $OutDir | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
# The label keeps parallel CI jobs (one per service) from writing to the same file name.
$prefix = if ($Label) { "$Label-$stamp" } else { $stamp }
$csv = Join-Path $OutDir "measurements-$prefix.csv"
[IO.File]::WriteAllText($csv, "timestamp,metric,service,variant,run,value,unit,level`r`n", $utf8)
$seriesCsv = Join-Path $OutDir "timeseries-$prefix.csv"   # memory every 2 s during load and soak runs

function LocalTag($s, $v)  { "${s}:$v" }
function RemoteTag($s, $v) { "$Registry/${s}:$v" }

function Write-Result($metric, $s, $v, $run, [double] $value, $unit, $level = '') {
    $line = '{0},{1},{2},{3},{4},{5},{6},{7}' -f (Get-Date -Format 's'), $metric, $s, $v, $run, $value.ToString('0.###', $inv), $unit, $level
    [IO.File]::AppendAllText($csv, "$line`r`n", $utf8)
    $at = if ($level) { " @$level" } else { '' }
    Write-Host ('  {0,-22} {1,-18} {2,-16} run {3}: {4} {5}' -f $metric, $s, "$v$at", $run, $value.ToString('0.##', $inv), $unit)
}

function Get-CpuArgs { if ($Cpus) { @('--cpus', $Cpus) } else { @() } }

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

function Get-JvmMetrics($baseUrl) {
    # JVM metrics from Spring Boot Actuator (Micrometer, Prometheus format); summed over all label sets
    try { $lines = (Invoke-WebRequest "$baseUrl/actuator/prometheus" -UseBasicParsing -TimeoutSec 10).Content -split "`n" }
    catch { Write-Warning "could not read JVM metrics: $($_.Exception.Message)"; return $null }
    $sum = {
        param($name, $filter)
        $total = 0.0
        foreach ($l in $lines) {
            if ($l.StartsWith("$name{") -and (-not $filter -or $l.Contains($filter))) {
                $total += [double]::Parse(($l.Trim() -split ' ')[-1], $inv)
            }
        }
        $total
    }
    return [pscustomobject] @{
        GcCount       = & $sum 'jvm_gc_pause_seconds_count' $null
        GcSeconds     = & $sum 'jvm_gc_pause_seconds_sum' $null
        HeapUsed      = & $sum 'jvm_memory_used_bytes' 'area="heap"'
        HeapCommitted = & $sum 'jvm_memory_committed_bytes' 'area="heap"'
        NonHeapUsed   = & $sum 'jvm_memory_used_bytes' 'area="nonheap"'
        ThreadsPeak   = & $sum 'jvm_threads_peak_threads' $null
        Uptime        = & $sum 'process_uptime_seconds' $null   # same clock as the GC log's uptime decorator
    }
}

function Get-ProcMemory($c) {
    # Kernel view of the Java process (PID 1): VmRSS = resident now, VmHWM = highest RSS since start
    $status = docker exec $c cat /proc/1/status 2>$null
    $mem = @{}
    foreach ($l in $status) { if ($l -match '^(VmRSS|VmHWM):\s+(\d+) kB') { $mem[$Matches[1]] = [double] $Matches[2] / 1024 } }
    return $mem
}

function Get-Percentile($sorted, $p) {
    # linear interpolation between the two nearest ranks, same definition as k6 (input must be sorted)
    $i = $p * ($sorted.Count - 1)
    $lo = [math]::Floor($i); $hi = [math]::Ceiling($i)
    return $sorted[$lo] + ($sorted[$hi] - $sorted[$lo]) * ($i - $lo)
}

function Read-GcPauses($path, $from, $to) {
    # Unified GC log lines like "[12.345s] GC(3) Pause Young (Allocation Failure) 120M->30M(300M) 4.567ms"
    $pauses = New-Object 'System.Collections.Generic.List[double]'
    foreach ($l in [IO.File]::ReadAllLines($path)) {
        if ($l -match '^\[([\d.]+)s\].* Pause .* ([\d.]+)ms$') {
            $t = [double]::Parse($Matches[1], $inv)
            if ($t -ge $from -and $t -le $to) { $pauses.Add([double]::Parse($Matches[2], $inv)) }
        }
    }
    $pauses.Sort()
    return , $pauses
}

function Read-NmtSummary($lines) {
    # NMT summary printed at JVM exit (bytes, or KB/MB with a scale) -> committed MiB per category plus "Total"
    $result = [ordered] @{}
    $toMiB = { param($n, $unit) [double] $n * @{ '' = 1; 'KB' = 1KB; 'MB' = 1MB; 'GB' = 1GB }["$unit"] / 1MB }
    foreach ($l in $lines) {
        if ($l -match '^Total: reserved=\d+[KMG]?B?, committed=(\d+)([KMG]B)?') { $result['Total'] = & $toMiB $Matches[1] $Matches[2] }
        elseif ($l -match '^-\s+(.+?) \(reserved=\d+[KMG]?B?, committed=(\d+)([KMG]B)?') {
            $result[$Matches[1].Trim()] = & $toMiB $Matches[2] $Matches[3]
        }
    }
    return $result
}

function Read-K6Summary($path, $durationSeconds) {
    # k6's end-of-test summary (handleSummary in load-test.js); the {phase:measure} submetrics exclude the warm-up
    $m = ([IO.File]::ReadAllText($path) | ConvertFrom-Json).metrics
    $requests = $m.'http_reqs{phase:measure}'.values.count
    return [pscustomobject] @{
        # k6's http_reqs rate is count / test time; here the time is the measure scenario only
        Throughput    = $requests / $durationSeconds
        P99           = $m.'http_req_duration{phase:measure}'.values.'p(99)'
        Median        = $m.'http_req_duration{phase:measure}'.values.med
        Requests      = $requests
        Failed        = $m.'http_req_failed{phase:measure}'.values.passes   # passes = requests counted as failed
    }
}

function Get-CpuSeconds($c) {
    # user + system CPU time of the Java process from /proc/1/stat (fields 14 and 15, in 1/100 s ticks)
    $stat = "$(docker exec $c cat /proc/1/stat 2>$null)"
    if (-not $stat) { return $null }
    $fields = $stat.Substring($stat.LastIndexOf(')') + 2) -split ' '   # field 3 onwards, after "pid (comm)"
    return ([double] $fields[11] + [double] $fields[12]) / 100
}

function Get-Slope($xs, $ys) {
    # least-squares slope of y over x
    $n = $xs.Count; if ($n -lt 3) { return $null }
    $mx = ($xs | Measure-Object -Average).Average; $my = ($ys | Measure-Object -Average).Average
    $num = 0.0; $den = 0.0
    for ($i = 0; $i -lt $n; $i++) { $num += ($xs[$i] - $mx) * ($ys[$i] - $my); $den += ($xs[$i] - $mx) * ($xs[$i] - $mx) }
    if ($den -eq 0) { return $null }
    return $num / $den
}

function Measure-Startup($s, $v, $run, [bool] $record) {
    $img = RemoteTag $s $v
    if (-not (Test-Image $img)) { docker pull -q $img | Out-Null }
    docker rm -f $container 2>$null | Out-Null

    $hostPort = 10000 + $port[$s]
    $runArgs = @('run', '-d', '--name', $container, '--network', $network, '--memory', '512m') + (Get-CpuArgs) + @('-p', "${hostPort}:$($port[$s])")
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
    $memMiB = $null; $proc = @{}
    if ($record) {
        Start-Sleep -Seconds $MemoryWarmupSeconds
        $memMiB = ConvertTo-MiB (docker stats $container --no-stream --format '{{.MemUsage}}')
        $proc = Get-ProcMemory $container   # cgroup usage (docker stats) also counts page cache, RSS only the process
    }
    docker rm -f $container | Out-Null

    if ($record) {
        Write-Result 'startup_wall' $s $v $run $sw.Elapsed.TotalSeconds 's'
        Write-Result 'startup_ready' $s $v $run ([double]::Parse(($readyLine.Trim() -split ' ')[-1], $inv)) 's'
        if ($memMiB) { Write-Result 'memory_idle' $s $v $run $memMiB 'MiB' }
        if ($proc.VmRSS) { Write-Result 'memory_idle_rss' $s $v $run $proc.VmRSS 'MiB' }
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

function Write-Series($kind, $v, $level, $run, $samples) {
    # memory time series of one run: seconds since sampling started, docker stats MiB, kernel RSS MiB
    if (-not (Test-Path $seriesCsv)) { [IO.File]::WriteAllText($seriesCsv, "kind,variant,level,run,t_s,docker_mib,rss_mib`r`n", $utf8) }
    $lines = foreach ($x in $samples) {
        $d = if ($null -ne $x.Docker) { $x.Docker.ToString('0.#', $inv) } else { '' }
        $m = if ($null -ne $x.Rss) { $x.Rss.ToString('0.#', $inv) } else { '' }
        "$kind,$v,$level,$run,$($x.T),$d,$m"
    }
    if ($lines) { [IO.File]::AppendAllText($seriesCsv, (($lines -join "`r`n") + "`r`n"), $utf8) }
}

function Invoke-LoadRun($kind, $v, $level, $duration, $run) {
    # one k6 run against a fresh esg-score-service container; $kind ('load' or 'soak') prefixes the metric names
    $s = 'esg-score-service'
    $imageVariant = if ($v -eq 'multi-jre-arena2') { 'multi-jre' } else { $v }
    $img = RemoteTag $s $imageVariant
    if (-not (Test-Image $img)) { $img = LocalTag $s $imageVariant }
    if (-not (Test-Image $img)) { Write-Warning "no image for $s $imageVariant - run the build phase first"; return }
    $emit = { param($metric, $value, $unit) Write-Result "${kind}_$metric" $s $v $run $value $unit $level }
    $measureSeconds = ConvertFrom-K6Duration $duration
    $tag = "$kind-$v-c$level-run$run"

    docker rm -f $container 2>$null | Out-Null
    $runArgs = @('run', '-d', '--name', $container, '--network', $network, '--memory', '512m') + (Get-CpuArgs) + @('-p', "${loadPort}:$($port[$s])")
    foreach ($e in $containerEnv[$s]) { $runArgs += @('-e', $e) }
    $runArgs += @('-e', "JAVA_TOOL_OPTIONS=$loadFlags", '-e', 'JAVA_OPTS=')   # startup phase flags plus logging
    if ($v -eq 'multi-jre-arena2') { $runArgs += @('-e', 'MALLOC_ARENA_MAX=2') }   # read by glibc at process start
    docker @runArgs $img | Out-Null

    $memJob = $null
    $summaryFile = Join-Path ([IO.Path]::GetTempPath()) "k6-$prefix-$tag.json"
    try {
        if (-not (Wait-Healthy $loadBase 180)) {
            Write-Warning "$s $v did not become healthy within 180 s, last log lines:"
            docker logs --tail 20 $container
            return
        }

        # POST /api/scores fetches all companies from company-service and upserts one score
        # per company (primary key = company id), so score_db always holds the same 20 rows.
        # If company-service isn't reachable, the scores already stored in score_db are used.
        try { Invoke-RestMethod -Method Post "$loadBase/api/scores" -TimeoutSec 60 | Out-Null }
        catch { Write-Warning "seeding failed ($($_.Exception.Message)) - using the scores already in score_db" }
        # k6 requests ids 1-20: all of them must exist, or part of the load would be 404s
        $ids = @(Invoke-RestMethod "$loadBase/api/scores" -TimeoutSec 10 | ForEach-Object { $_.companyId })
        $missing = @(1..20 | Where-Object { $ids -notcontains $_ })
        if ($missing.Count -gt 0) { Write-Warning "no score for company id(s) $($missing -join ', ') - $tag skipped"; return }
        Start-Sleep -Seconds 5

        # docker stats and kernel RSS every 2 s from the warm-up on: peak and time series
        $memJob = Start-Job -ArgumentList $container -ScriptBlock {
            param($c)
            $sw = [Diagnostics.Stopwatch]::StartNew()
            while ($true) {
                $mem = docker stats $c --no-stream --format '{{.MemUsage}}'
                $rss = "$(docker exec $c cat /proc/1/status 2>$null | Where-Object { $_ -like 'VmRSS:*' })" -replace '\D', ''
                '{0};{1};{2}' -f [int] $sw.Elapsed.TotalSeconds, $mem, $rss
                Start-Sleep -Seconds 2
            }
        }

        # warm-up as its own k6 run, so CPU and GC can be read between warm-up (JIT compilation) and measurement
        # --log-output=none: failed requests are counted in the summary instead of one log line each
        & $K6 run --quiet --log-output=none --env "BASE_URL=$loadBase" --env "WARMUP=$LoadWarmup" --env ONLY=warmup `
            --env "SUMMARY=$summaryFile.warmup" $k6Script | Out-Null
        Remove-Item "$summaryFile.warmup" -ErrorAction SilentlyContinue
        $jvmBefore = Get-JvmMetrics $loadBase   # GC counters are cumulative, so the measured share is after minus before
        $cpuBefore = Get-CpuSeconds $container

        $sw = [Diagnostics.Stopwatch]::StartNew()
        & $K6 run --quiet --log-output=none --env "BASE_URL=$loadBase" --env "VUS=$level" --env "DURATION=$duration" `
            --env ONLY=measure --env "SUMMARY=$summaryFile" $k6Script | Out-Null
        $k6Exit = $LASTEXITCODE
        $sw.Stop()
        $cpuAfter = Get-CpuSeconds $container
        $jvmAfter = Get-JvmMetrics $loadBase
        $proc = Get-ProcMemory $container

        # the JRE images have no jcmd, so NMT prints its summary at JVM exit: stop gracefully, read the log
        $nmtSummary = $null
        if ($Nmt) {
            docker stop -t 60 $container 2>$null | Out-Null
            $log = docker logs $container 2>&1 | ForEach-Object { "$_" }
            [IO.File]::WriteAllLines((Join-Path $diagDir "nmt-$tag.txt"), [string[]] $log, $utf8)
            $nmtSummary = Read-NmtSummary $log
        }
        $gcFile = Join-Path $diagDir "gc-$tag.log"
        docker cp "${container}:/tmp/gc.log" $gcFile 2>$null | Out-Null

        Stop-Job $memJob
        $samples = @(Receive-Job $memJob | ForEach-Object {
            $p = "$_" -split ';'
            [pscustomobject] @{ T = [double] $p[0]; Docker = ConvertTo-MiB $p[1]; Rss = if ($p[2]) { [double] $p[2] / 1024 } else { $null } }
        })
        Remove-Job $memJob -Force; $memJob = $null
        Write-Series $kind $v $level $run $samples

        if ($k6Exit -ne 0 -or -not (Test-Path $summaryFile)) { Write-Warning "k6 failed (exit $k6Exit): $tag"; return }
        $res = Read-K6Summary $summaryFile $measureSeconds
        if ($res.Failed -gt 0) { Write-Warning ("{0} of {1} requests failed in {2}" -f $res.Failed, $res.Requests, $tag) }
        & $emit 'throughput' $res.Throughput 'req/s'
        & $emit 'p99' $res.P99 'ms'
        & $emit 'latency_median' $res.Median 'ms'
        & $emit 'errors' $res.Failed 'count'
        if ($null -ne $cpuBefore -and $null -ne $cpuAfter -and $res.Requests -gt 0) {
            # CPU time of the Java process per measured request and average cores used, warm-up excluded
            & $emit 'cpu_per_request' (1e6 * ($cpuAfter - $cpuBefore) / $res.Requests) 'us'
            & $emit 'cpu_cores' (($cpuAfter - $cpuBefore) / $sw.Elapsed.TotalSeconds) 'cores'
        }
        $dockerPeak = @($samples | Where-Object { $null -ne $_.Docker } | ForEach-Object { $_.Docker })
        if ($dockerPeak.Count -gt 0) { & $emit 'mem_peak' ($dockerPeak | Measure-Object -Maximum).Maximum 'MiB' }
        if ($proc.VmHWM) { & $emit 'rss_peak' $proc.VmHWM 'MiB' }
        if ($kind -eq 'soak') {
            # RSS trend after the first 5 minutes of load: does memory keep growing (fragmentation) or level off?
            $from = (ConvertFrom-K6Duration $LoadWarmup) + [math]::Min(300, $measureSeconds / 2)
            $late = @($samples | Where-Object { $_.T -ge $from -and $null -ne $_.Rss })
            $slope = Get-Slope @($late | ForEach-Object { $_.T }) @($late | ForEach-Object { $_.Rss })
            if ($null -ne $slope) { & $emit 'rss_trend' ($slope * 600) 'MiB/10min' }
        }
        if ($jvmBefore -and $jvmAfter) {
            & $emit 'gc_pauses' ($jvmAfter.GcCount - $jvmBefore.GcCount) 'count'
            & $emit 'gc_pause_time' (1000 * ($jvmAfter.GcSeconds - $jvmBefore.GcSeconds)) 'ms'
            & $emit 'heap_used' ($jvmAfter.HeapUsed / 1MB) 'MiB'
            & $emit 'heap_committed' ($jvmAfter.HeapCommitted / 1MB) 'MiB'
            & $emit 'nonheap_used' ($jvmAfter.NonHeapUsed / 1MB) 'MiB'
            & $emit 'threads_peak' $jvmAfter.ThreadsPeak 'count'
            if (Test-Path $gcFile) {
                $pauses = Read-GcPauses $gcFile $jvmBefore.Uptime $jvmAfter.Uptime
                if ($pauses.Count -gt 0) {
                    & $emit 'gc_pause_p50' (Get-Percentile $pauses 0.5) 'ms'
                    & $emit 'gc_pause_p95' (Get-Percentile $pauses 0.95) 'ms'
                    & $emit 'gc_pause_max' $pauses[$pauses.Count - 1] 'ms'
                }
            }
        }
        if ($nmtSummary) {
            foreach ($k in $nmtSummary.Keys) { & $emit ('nmt_' + ($k.ToLower() -replace '[^a-z0-9]+', '_')) $nmtSummary[$k] 'MiB' }
            # memory the kernel sees but the JVM doesn't account for: libc allocator, shared libraries
            if ($proc.VmRSS -and $nmtSummary['Total']) { & $emit 'nmt_rss_unaccounted' ($proc.VmRSS - $nmtSummary['Total']) 'MiB' }
        }
    } finally {
        if ($memJob) { Stop-Job $memJob; Remove-Job $memJob -Force }
        docker rm -f $container 2>$null | Out-Null
        Remove-Item $summaryFile -ErrorAction SilentlyContinue
    }
}

function Get-Shuffled($items, $rng) {
    # Fisher-Yates shuffle with the seeded generator
    $a = @($items)
    for ($i = $a.Count - 1; $i -gt 0; $i--) { $j = $rng.Next($i + 1); $t = $a[$i]; $a[$i] = $a[$j]; $a[$j] = $t }
    return , $a
}

# Join-Path twice instead of '..\..', so the path also works on Linux (GitHub Actions)
$repoRoot = (Resolve-Path (Join-Path (Join-Path $PSScriptRoot '..') '..')).Path
Push-Location $repoRoot
try {
    if ($Phases -contains 'build') {
        Write-Host '== build: pulling base images and building the single-stage build image once (not timed)'
        foreach ($b in $baseImages) { docker pull -q $b | Out-Null }
        # single starts FROM this image (Temurin 21.0.13 JDK + Maven), so it shares OS and JDK with multi-jdk
        docker build -q -t $singleBuildImage (Join-Path $PSScriptRoot 'maven-temurin') | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "could not build $singleBuildImage" }
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
                docker builder prune -af 2>$null | Out-Null   # disk space: --no-cache never reuses it; the image itself is kept
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
    if ($Phases -contains 'load' -or $Phases -contains 'soak') {
        $k6Script = Join-Path $PSScriptRoot 'load-test.js'
        if (-not (Test-Path $K6)) {
            Write-Warning "k6 not found at $K6 - load and soak phases skipped (put k6.exe there or pass -K6 <path>)"
        } else {
            # company-service is needed for seeding: POST /api/scores fetches the companies from it
            # (reachable as company-service:8081 on the compose network, = COMPANY_SERVICE_URL).
            docker compose up -d --wait postgres rabbitmq company-service 2>$null | Out-Null
            $loadPort = 10000 + $port['esg-score-service']   # 18082, same as in Measure-Startup
            $loadBase = "http://localhost:$loadPort"
            # GC log (gc+init also logs the collector and the CPUs the JVM sees); NMT only on request
            $loadFlags = "$JvmFlags -Xlog:gc,gc+init:file=/tmp/gc.log:uptime"
            if ($Nmt) { $loadFlags += ' -XX:NativeMemoryTracking=summary -XX:+UnlockDiagnosticVMOptions -XX:+PrintNMTStatistics' }
            $diagDir = Join-Path $OutDir "diagnostics-$prefix"
            New-Item -ItemType Directory -Force $diagDir | Out-Null
            $rng = New-Object Random $Seed
            $cpuText = if ($Cpus) { "$Cpus CPUs" } else { 'no CPU limit' }

            if ($Phases -contains 'load') {
                $cells = @(foreach ($l in $LoadLevels) { foreach ($v in $LoadVariants) { [pscustomobject] @{ Level = $l; Variant = $v } } })
                Write-Host ("== load: {0} users x {1} x {2} runs, {3} each after {4} warm-up, {5}, random order (seed {6})" -f `
                    ($LoadLevels -join '/'), ($LoadVariants -join ', '), $LoadRepeats, $LoadDuration, $LoadWarmup, $cpuText, $Seed)
                for ($r = 1; $r -le $LoadRepeats; $r++) {
                    # every repeat runs all level x variant cells once, in a new random order
                    $i = 0
                    foreach ($c in (Get-Shuffled $cells $rng)) {
                        $i++
                        Write-Host "-- $(Get-Date -Format T) run $r/$LoadRepeats, $i/$($cells.Count): $($c.Variant) @ $($c.Level) users"
                        Invoke-LoadRun 'load' $c.Variant $c.Level $LoadDuration $r
                    }
                }
            }

            if ($Phases -contains 'soak') {
                Write-Host "== soak: $SoakLevel users for $SoakDuration, $($LoadVariants -join ', ') x $SoakRepeats runs, $cpuText"
                for ($r = 1; $r -le $SoakRepeats; $r++) {
                    foreach ($v in (Get-Shuffled $LoadVariants $rng)) {
                        Write-Host "-- $(Get-Date -Format T) soak run $r/${SoakRepeats}: $v"
                        Invoke-LoadRun 'soak' $v $SoakLevel $SoakDuration $r
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
$summary = $rows | Group-Object metric, service, variant, level | ForEach-Object {
    $first = $_.Group[0]
    $vals = @($_.Group | ForEach-Object { [double]::Parse($_.value, $inv) })
    $stats = $vals | Measure-Object -Average -Minimum -Maximum
    $sd = 0
    if ($vals.Count -gt 1) {
        $sq = ($vals | ForEach-Object { ($_ - $stats.Average) * ($_ - $stats.Average) } | Measure-Object -Sum).Sum
        $sd = [math]::Sqrt($sq / ($vals.Count - 1))
    }
    [pscustomobject] @{
        metric = $first.metric; service = $first.service; variant = $first.variant; level = $first.level; n = $vals.Count
        mean = & $fmt $stats.Average; sd = & $fmt $sd; min = & $fmt $stats.Minimum; max = & $fmt $stats.Maximum
        unit = $first.unit
    }
}
$summaryCsv = Join-Path $OutDir "summary-$prefix.csv"
$summary | Export-Csv $summaryCsv -NoTypeInformation -Encoding UTF8
$summary | Format-Table -AutoSize
Write-Host "Measurements: $csv"
Write-Host "Summary:      $summaryCsv"
