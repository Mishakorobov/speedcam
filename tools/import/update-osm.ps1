# =============================================================================
#  update-osm.ps1  -  data-side work for update-osm.bat
#
#  Split out because batch cannot express the two things this needs: conditional
#  per-district logic and comparison of digests. The .bat owns orchestration,
#  preflight and reporting; this file owns fetching and the change manifest.
#
#  ASCII only, on purpose. Windows PowerShell 5.1 reads a BOM-less .ps1 using the
#  system code page, so a stray non-ASCII character in a log string becomes mojibake
#  in the middle of an otherwise readable report.
#
#  Exits 0 and prints UP-TO-DATE on stdout when nothing changed and every district
#  is already present; the .bat uses that to skip the import entirely.
# =============================================================================

param(
    # Defaults to the project's own osm\ directory, resolved from this script's location so
    # the folder moves with the checkout. update-osm.bat always passes it explicitly.
    [string]$OsmDir = (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'osm'),
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$curl = if (Get-Command curl.exe -ErrorAction SilentlyContinue) { 'curl.exe' } else { 'curl' }
$base = 'https://download.geofabrik.de/russia'

# Name -> approximate size in MB from the Geofabrik index. Used as a cheap
# completeness check before spending time on a full MD5.
$districts = [ordered]@{
    'kaliningrad'                = 28
    'crimean-fed-district'        = 39
    'north-caucasus-fed-district' = 123
    'south-fed-district'          = 301
    'far-eastern-fed-district'    = 373
    'ural-fed-district'           = 378
    'siberian-fed-district'       = 560
    'northwestern-fed-district'   = 625
    'volga-fed-district'          = 740
    'central-fed-district'        = 840
}

function Log($m) { Write-Host "    $m" }

$manifestPath = Join-Path $OsmDir 'imported.txt'

# ---------------------------------------------------------------- fetching ---

function Get-RemoteMd5([string]$name) {
    $url = "$base/$name-latest.osm.pbf.md5"

    # A transient timeout must not abort the whole run. $ErrorActionPreference is Stop, and a
    # native command exiting non-zero raises, so the probe is isolated and retries. Returning
    # $null means "unknown", which the caller treats as "keep what we have".
    try {
        $out = & $curl -sS -L --retry 3 --retry-delay 3 --connect-timeout 20 --max-time 90 $url 2>$null
        if (-not $out) { return $null }
        return (($out -split '\s+')[0]).ToLower()
    } catch {
        return $null
    }
}

function Test-Local([string]$name, [string]$md5, [int]$wantMB) {
    $file = Join-Path $OsmDir "$name-latest.osm.pbf"
    if (-not (Test-Path $file)) { return $false }

    $mb = (Get-Item $file).Length / 1MB
    if ($mb -lt ($wantMB * 0.98)) {
        Log ("{0} is incomplete ({1} of {2} MB), will refetch" -f $name, [math]::Round($mb), $wantMB)
        return $false
    }
    return ((Get-FileHash $file -Algorithm MD5).Hash.ToLower() -eq $md5)
}

function Get-District([string]$name, [string]$md5) {
    $file = "$name-latest.osm.pbf"
    $target = Join-Path $OsmDir $file

    # --- Decide whether an existing file may be resumed -------------------------
    # `curl -C -` asks the server to continue from the local byte offset. That is only
    # valid when the remote file is byte-identical to what is already on disk. Geofabrik
    # rebuilds every extract in one daily batch, so "yesterday's partial" and "today's
    # build" are different files: splicing them yields a file of roughly the right size
    # with a completely wrong digest. That corruption is silent and was observed here.
    #
    # A partial file is therefore only resumed when the digest it was started against is
    # recorded alongside it and still matches upstream. Anything else is discarded.
    $marker = "$target.partial"
    $canResume = $false
    $have = (Test-Path $target)

    if ($have -and (Test-Path $marker)) {
        $started = (Get-Content $marker -Raw).Trim().ToLower()
        $canResume = ($started -eq $md5)
        if (-not $canResume) {
            Log ("{0} restarting: the partial file was begun against a different upstream build" -f $name)
            Remove-Item $target -Force -ErrorAction SilentlyContinue
        }
    } elseif ($have) {
        # Complete-looking but with no provenance: verify rather than trust.
        $mb = (Get-Item $target).Length / 1MB
        if ($mb -ge ($districts[$name] * 0.98)) {
            $actual = (Get-FileHash $target -Algorithm MD5).Hash.ToLower()
            if ($actual -eq $md5) {
                Log ("{0} already current on disk" -f $name)
                return $true
            }
            Log ("{0} restarting: digest does not match upstream" -f $name)
        }
        Remove-Item $target -Force -ErrorAction SilentlyContinue
    }

    if ($canResume) {
        Log ("{0} resuming a partial transfer" -f $name)
    }

    # Record what this transfer is against, so an interruption can be resumed safely.
    Set-Content -Path $marker -Value $md5 -Encoding ascii

    for ($attempt = 1; $attempt -le 4; $attempt++) {
        # $ErrorActionPreference is Stop, and a native command exiting non-zero raises in
        # PowerShell. Without this the first curl failure aborts the script instead of
        # reaching the next attempt, which is how the retry loop was being skipped.
        #
        # The speed floor is deliberately low. Geofabrik throttles when several districts
        # are fetched back to back, and a 2 KB/s floor killed transfers that were still
        # making progress.
        try {
            & $curl -sS -L --fail $(if ($canResume) { '-C -' } else { '' }) `
                --retry 3 --retry-delay 5 --speed-time 180 --speed-limit 512 --max-time 7200 `
                -o $target "$base/$file" 2>&1 | Out-Null
        } catch {
            Log ("{0} transfer interrupted ({1}/4), retrying" -f $name, $attempt)
        }

        if (Test-Path $target) {
            $actual = (Get-FileHash $target -Algorithm MD5).Hash.ToLower()
            if ($actual -eq $md5) {
                Remove-Item $marker -Force -ErrorAction SilentlyContinue
                Log ("{0} fetched, {1} MB" -f $name, [math]::Round((Get-Item $target).Length / 1MB))
                return $true
            }
            Log ("{0} checksum mismatch, retrying ({1}/4)" -f $name, $attempt)
            # Never retry from bytes we could not verify; start clean next time.
            $canResume = $false
            Remove-Item $target -Force -ErrorAction SilentlyContinue
        }
        Start-Sleep -Seconds 5
    }

    Remove-Item $marker -Force -ErrorAction SilentlyContinue
    Log ("{0} FAILED after 4 attempts" -f $name)
    return $false
}

# -------------------------------------------------------------------- main ---

New-Item -ItemType Directory -Force -Path $OsmDir | Out-Null

# What was successfully imported last time.
$imported = @{}
if (Test-Path $manifestPath) {
    Get-Content $manifestPath | ForEach-Object {
        if ($_ -match '^([^=]+)=(\S+)$') { $imported[$Matches[1]] = $Matches[2] }
    }
}

$fetched = @{}
$changed = @()
$failed = @()
$unreachable = @()

foreach ($name in $districts.Keys) {
    $md5 = Get-RemoteMd5 $name
    if (-not $md5) {
        # Not the same as a failed update: we simply could not ask. Keep the local copy.
        $unreachable += $name
        continue
    }

    if ((-not $Force) -and $imported[$name] -eq $md5 -and (Test-Local $name $md5 $districts[$name])) {
        Log ("{0} unchanged" -f $name)
        continue
    }

    if ($Force) {
        Log ("{0} refetching (forced)" -f $name)
    } else {
        Log ("{0} changed upstream" -f $name)
    }

    if (Get-District $name $md5) {
        $fetched[$name] = $md5
        $changed += $name
    } else {
        $failed += $name
    }
}

if ($unreachable.Count -gt 0) {
    Log ("could not reach Geofabrik for: {0}" -f ($unreachable -join ', '))
    if ($unreachable.Count -eq $districts.Count) {
        Log 'Geofabrik appears to be down. Nothing was changed.'
        Write-Output 'UNREACHABLE'
        exit 2
    }
}

if ($failed.Count -gt 0) {
    # A district that would not update is not fatal on its own: the previous copy
    # is still on disk and was already imported. Its digest is carried forward
    # unchanged so the next run retries it instead of treating it as current.
    Log ("could not update: {0} - keeping the previously imported copy" -f ($failed -join ', '))
}

if ($changed.Count -eq 0) {
    if ($unreachable.Count -eq 0 -and $imported.Count -eq $districts.Count -and $failed.Count -eq 0) {
        Write-Output 'UP-TO-DATE'
        exit 0
    }
    Log 'nothing changed upstream, but the local set is incomplete - importing anyway'
}

foreach ($name in $districts.Keys) {
    if ($fetched.ContainsKey($name)) { $imported[$name] = $fetched[$name] }
}

$districts.Keys |
    Where-Object { $imported.ContainsKey($_) } |
    ForEach-Object { "$_=$($imported[$_])" } |
    Set-Content -Path $manifestPath -Encoding ascii

Write-Output ('CHANGED:' + ($changed -join ','))
exit 0