param(
    [ValidateSet("compile", "build", "runClient")]
    [string]$Mode = "compile",
    [string]$MinecraftVersion,
    [ValidateSet("all", "legacy", "modern")]
    [string]$Band = "all",
    [ValidateRange(0, 5)]
    [int]$Retries = 0,
    [switch]$DryRun,
    [switch]$DiscoverOnly,
    [switch]$StopOnFirstFailure
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

function Get-MetadataVersions {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Url
    )

    [xml]$xml = (Invoke-WebRequest -UseBasicParsing $Url).Content
    $versions = @($xml.metadata.versioning.versions.version)
    return @($versions | ForEach-Object { $_.ToString() })
}

function Get-LatestStableNeoforgePerMinor {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Versions
    )

    $best = @{}
    foreach ($v in $Versions) {
        if ($v -notmatch '^21\.(\d+)\.(\d+)$') {
            continue
        }

        $minor = [int]$Matches[1]
        $patch = [int]$Matches[2]

        if (-not $best.ContainsKey($minor) -or $patch -gt $best[$minor].Patch) {
            $best[$minor] = [pscustomobject]@{
                Minor   = $minor
                Patch   = $patch
                Version = $v
            }
        }
    }

    return @($best.Values | Sort-Object Minor)
}

function Get-LatestNeoFormForMinecraft {
    param(
        [Parameter(Mandatory = $true)]
        [string]$MinecraftVersion,
        [Parameter(Mandatory = $true)]
        [string[]]$NeoFormVersions
    )

    $prefix = '^' + [regex]::Escape($MinecraftVersion) + '-\d{8}\.\d+$'
    $candidates = @($NeoFormVersions | Where-Object { $_ -match $prefix })
    if ($candidates.Count -eq 0) {
        return $null
    }

    return ($candidates | Sort-Object -Descending | Select-Object -First 1)
}

function Get-LatestCommonVersion {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Left,
        [Parameter(Mandatory = $true)]
        [string[]]$Right
    )

    $rightSet = New-Object 'System.Collections.Generic.HashSet[string]'
    foreach ($v in $Right) {
        [void]$rightSet.Add($v)
    }

    $shared = @()
    foreach ($v in $Left) {
        if ($rightSet.Contains($v)) {
            $shared += $v
        }
    }

    if ($shared.Count -eq 0) {
        return $null
    }

    return ($shared | Sort-Object -Descending | Select-Object -First 1)
}

function Get-MinecraftPatch {
    param(
        [Parameter(Mandatory = $true)]
        [string]$MinecraftVersion
    )

    if ($MinecraftVersion -eq "1.21") {
        return 0
    }

    if ($MinecraftVersion -match '^1\.21\.(\d+)$') {
        return [int]$Matches[1]
    }

    return 999
}

function Sort-MatrixEntries {
    param(
        [Parameter(Mandatory = $true)]
        [object[]]$Entries
    )

    return @($Entries | Sort-Object @{ Expression = { Get-MinecraftPatch $_.minecraft_version } })
}

function Filter-EntriesByBand {
    param(
        [Parameter(Mandatory = $true)]
        [object[]]$Entries,
        [Parameter(Mandatory = $true)]
        [string]$Band
    )

    if ($Band -eq "all") {
        return (Sort-MatrixEntries -Entries $Entries)
    }

    if ($Band -eq "legacy") {
        return (Sort-MatrixEntries -Entries @($Entries | Where-Object { (Get-MinecraftPatch $_.minecraft_version) -le 3 }))
    }

    return (Sort-MatrixEntries -Entries @($Entries | Where-Object { (Get-MinecraftPatch $_.minecraft_version) -ge 4 }))
}

function Resolve-MatrixEntries {
    $neoforgeMetaUrl = "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml"
    $neoformMetaUrl = "https://maven.neoforged.net/releases/net/neoforged/neoform/maven-metadata.xml"
    $jeiBase = "https://maven.blamejared.com/mezz/jei"
    $geckoBase = "https://dl.cloudsmith.io/public/geckolib3/geckolib/maven/software/bernie/geckolib"

    $neoforgeVersions = Get-MetadataVersions -Url $neoforgeMetaUrl
    $neoformVersions = Get-MetadataVersions -Url $neoformMetaUrl
    $stablePerMinor = Get-LatestStableNeoforgePerMinor -Versions $neoforgeVersions

    $entries = @()
    foreach ($line in $stablePerMinor) {
        $minor = [int]$line.Minor
        $mc = if ($minor -eq 0) { "1.21" } else { "1.21.$minor" }
        $nf = $line.Version
        $neoform = Get-LatestNeoFormForMinecraft -MinecraftVersion $mc -NeoFormVersions $neoformVersions

        if (-not $neoform) {
            $entries += [pscustomobject]@{
                minecraft_version = $mc
                neoforge_version = $nf
                status = "skip"
                reason = "No matching neo_form_version on Maven."
            }
            continue
        }

        $jeiApiMeta = "$jeiBase/jei-$mc-neoforge-api/maven-metadata.xml"
        $jeiRuntimeMeta = "$jeiBase/jei-$mc-neoforge/maven-metadata.xml"
        $jeiMinecraftVersion = $mc
        if ($mc -eq "1.21.3") {
            $jeiMinecraftVersion = "1.21.1"
            $jeiApiMeta = "$jeiBase/jei-$jeiMinecraftVersion-neoforge-api/maven-metadata.xml"
            $jeiRuntimeMeta = "$jeiBase/jei-$jeiMinecraftVersion-neoforge/maven-metadata.xml"
        }
        $geckoNeoMeta = "$geckoBase/geckolib-neoforge-$mc/maven-metadata.xml"
        $geckoCommonMeta = "$geckoBase/geckolib-common-$mc/maven-metadata.xml"

        try {
            $jeiApiVersions = Get-MetadataVersions -Url $jeiApiMeta
            $jeiRuntimeVersions = Get-MetadataVersions -Url $jeiRuntimeMeta
            $jeiVersion = Get-LatestCommonVersion -Left $jeiApiVersions -Right $jeiRuntimeVersions
            if (-not $jeiVersion) {
                throw "No shared JEI version between API/runtime artifacts."
            }

            $geckoNeoVersions = Get-MetadataVersions -Url $geckoNeoMeta
            $geckoCommonVersions = Get-MetadataVersions -Url $geckoCommonMeta
            $geckoVersion = Get-LatestCommonVersion -Left $geckoNeoVersions -Right $geckoCommonVersions
            if (-not $geckoVersion) {
                throw "No shared GeckoLib version between common/neoforge artifacts."
            }

            $entries += [pscustomobject]@{
                minecraft_version = $mc
                neoforge_version = $nf
                neo_form_version = $neoform
                jei_minecraft_version = $jeiMinecraftVersion
                jei_version = $jeiVersion
                geckolib_version = $geckoVersion
                status = "ready"
                reason = ""
            }
        }
        catch {
            $entries += [pscustomobject]@{
                minecraft_version = $mc
                neoforge_version = $nf
                status = "skip"
                reason = $_.Exception.Message
            }
        }
    }

    return (Sort-MatrixEntries -Entries $entries)
}

function New-GradlePropertyArgs {
    param(
        [Parameter(Mandatory = $true)]
        [pscustomobject]$Entry
    )

    return @(
        "-Pskip_fabric=true",
        "-Pskip_forge=true",
        "-Pminecraft_version=$($Entry.minecraft_version)",
        "-Pneoforge_version=$($Entry.neoforge_version)",
        "-Pneo_form_version=$($Entry.neo_form_version)",
        "-Pdep_minecraft_version=$($Entry.minecraft_version)",
        "-Pjei_minecraft_version=$($Entry.jei_minecraft_version)",
        "-Pgeckolib_minecraft_version=$($Entry.minecraft_version)",
        "-Pjei_version=$($Entry.jei_version)",
        "-Pgeckolib_version=$($Entry.geckolib_version)"
    )
}

function Invoke-GradleWithRetries {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Args,
        [Parameter(Mandatory = $true)]
        [int]$Retries,
        [Parameter(Mandatory = $true)]
        [string]$Label
    )

    $attempt = 0
    do {
        $attempt++
        if ($attempt -gt 1) {
            Write-Host "Retry $attempt/$($Retries + 1) for $Label"
        }

        $oldErrorActionPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = "Continue"
            & ".\\gradlew.bat" @Args | Out-Host
            $exitCode = $LASTEXITCODE
        }
        finally {
            $ErrorActionPreference = $oldErrorActionPreference
        }

        if ($exitCode -eq 0) {
            return [pscustomobject]@{ Success = $true; Attempts = $attempt }
        }
    } while ($attempt -le $Retries)

    return [pscustomobject]@{ Success = $false; Attempts = $attempt }
}

function Copy-BuildArtifacts {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RepoRoot,
        [Parameter(Mandatory = $true)]
        [string]$MinecraftVersion,
        [Parameter(Mandatory = $true)]
        [datetime]$BuildStartUtc
    )

    $libsDir = Join-Path $RepoRoot "neoforge/build/libs"
    if (-not (Test-Path $libsDir)) {
        return @()
    }

    $artifactToken = "-neoforge-$MinecraftVersion-"

    $candidates = @(
        Get-ChildItem -Path $libsDir -File -Filter "*.jar" |
            Where-Object {
                $_.Name -notmatch '(sources|javadoc|dev|shadow|plain)\.jar$' -and
                $_.Name -like "*$artifactToken*" -and
                $_.LastWriteTimeUtc -ge $BuildStartUtc.AddSeconds(-2)
            }
    )

    if ($candidates.Count -eq 0) {
        $candidates = @(
            Get-ChildItem -Path $libsDir -File -Filter "*.jar" |
                Where-Object {
                    $_.Name -notmatch '(sources|javadoc|dev|shadow|plain)\.jar$' -and
                    $_.Name -like "*$artifactToken*"
                }
        )
    }

    if ($candidates.Count -eq 0) {
        return @()
    }

    $candidates = @($candidates | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1)

    $outputDir = Join-Path $RepoRoot "build/matrix-jars/$MinecraftVersion"
    New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
    Get-ChildItem -Path $outputDir -File -Filter "*.jar" -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue

    $copied = @()
    foreach ($jar in $candidates) {
        $target = Join-Path $outputDir $jar.Name
        Copy-Item -Path $jar.FullName -Destination $target -Force
        $copied += $target
    }

    return $copied
}

$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot
try {
    $env:GRADLE_USER_HOME = Join-Path $repoRoot ".gradle-user-home"
    if (-not (Test-Path $env:GRADLE_USER_HOME)) {
        New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME | Out-Null
    }

    $entries = Resolve-MatrixEntries

    Write-Host ""
    Write-Host "Resolved NeoForge 1.21.x matrix:"
    $entries | Format-Table minecraft_version, neoforge_version, neo_form_version, jei_version, geckolib_version, status, reason -AutoSize

    if ($DiscoverOnly) {
        exit 0
    }

    if ($Mode -eq "runClient") {
        if ([string]::IsNullOrWhiteSpace($MinecraftVersion)) {
            throw "-MinecraftVersion is required when -Mode runClient is used."
        }

        $selected = @($entries | Where-Object { $_.minecraft_version -eq $MinecraftVersion })
        if ($selected.Count -eq 0) {
            throw "Minecraft version '$MinecraftVersion' was not discovered in the current 1.21.x NeoForge matrix."
        }

        $entry = $selected[0]
        if ($entry.status -ne "ready") {
            throw "Cannot run client for ${MinecraftVersion}: $($entry.reason)"
        }

        Write-Host ""
        Write-Host "Launching NeoForge client for mc=$($entry.minecraft_version), neoforge=$($entry.neoforge_version)"

        $args = @(
            ":neoforge:runClient",
            "--no-daemon",
            "--configure-on-demand"
        ) + (New-GradlePropertyArgs -Entry $entry)
        if ($DryRun) {
            $args += "--dry-run"
        }

        & ".\\gradlew.bat" @args
        exit $LASTEXITCODE
    }

    $ready = @($entries | Where-Object { $_.status -eq "ready" })
    $ready = Filter-EntriesByBand -Entries $ready -Band $Band

    if ($ready.Count -eq 0) {
        throw "No ready matrix entries found for band '$Band'."
    }

    $results = @()
    foreach ($entry in $ready) {
        $mc = $entry.minecraft_version
        $nf = $entry.neoforge_version

        Write-Host ""
        Write-Host "==> Running mode=$Mode for mc=$mc, neoforge=$nf"

        $taskArgs = if ($Mode -eq "build") {
            @(
                ":common:compileJava",
                ":neoforge:build"
            )
        }
        else {
            @(
                ":common:compileJava",
                ":neoforge:compileJava"
            )
        }

        $gradleArgs = $taskArgs + @(
            "--no-daemon",
            "--configure-on-demand"
        ) + (New-GradlePropertyArgs -Entry $entry)
        if ($DryRun) {
            $gradleArgs += "--dry-run"
        }

        $buildStartUtc = (Get-Date).ToUniversalTime()
        $run = Invoke-GradleWithRetries -Args $gradleArgs -Retries $Retries -Label "mc=$mc"

        $artifacts = @()
        if ($run.Success -and $Mode -eq "build") {
            $artifacts = @(Copy-BuildArtifacts -RepoRoot $repoRoot -MinecraftVersion $mc -BuildStartUtc $buildStartUtc)
        }

        $results += [pscustomobject]@{
            minecraft_version = $mc
            neoforge_version = $nf
            neo_form_version = $entry.neo_form_version
            jei_version = $entry.jei_version
            geckolib_version = $entry.geckolib_version
            attempts = $run.Attempts
            artifacts = if ($artifacts.Count -gt 0) { ($artifacts -join "; ") } else { "" }
            result = if ($run.Success) { "PASS" } else { "FAIL" }
        }

        if ((-not $run.Success) -and $StopOnFirstFailure) {
            break
        }
    }

    Write-Host ""
    Write-Host "Matrix $Mode results (band=$Band):"
    if ($Mode -eq "build") {
        $results | Format-Table minecraft_version, neoforge_version, result, attempts, artifacts -AutoSize
    }
    else {
        $results | Format-Table minecraft_version, neoforge_version, result, attempts -AutoSize
    }

    $failed = @($results | Where-Object { $_.result -eq "FAIL" })
    if ($failed.Count -gt 0) {
        exit 1
    }

    exit 0
}
finally {
    Pop-Location
}
