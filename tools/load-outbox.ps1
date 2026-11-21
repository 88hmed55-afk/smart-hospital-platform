# Smart Hospital - Phase 6 outbox load check (1,000 events)
# ---------------------------------------------------------------------------
# Enqueues 1,000 synthetic outbox events, waits for the background dispatcher
# to drain them through the JMS queue and the MDB, and asserts that every event
# was delivered exactly once:
#   * outbox_events: 1,000 SENT, 0 PENDING/PROCESSING/DEAD
#   * notifications: 1,000 rows, 1,000 distinct dedupe keys
#   * every event attempted exactly once (no redelivery)
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\load-outbox.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\load-outbox.ps1 -Keep
#
# The script is ASCII-only on purpose (PowerShell 5.1 reads -File as ANSI).
# It deletes its own rows unless -Keep is passed. Never run on production.

param(
    [int]$Count = 1000,
    [int]$TimeoutSeconds = 900,
    [switch]$Keep
)

$ErrorActionPreference = 'Stop'
$MySql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$Db = 'smart_hospital'
$Base = 'http://localhost:8080/api/v1'
$Tag = 'LOAD6'

function Invoke-Sql {
    param([string]$Sql)
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $MySql
    $psi.Arguments = "-u root -P 3307 -h 127.0.0.1 -N -B --default-character-set=utf8mb4 $Db"
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8
    $proc = [System.Diagnostics.Process]::Start($psi)
    $proc.StandardInput.Write($Sql)
    $proc.StandardInput.Close()
    $out = $proc.StandardOutput.ReadToEnd()
    $err = $proc.StandardError.ReadToEnd()
    $proc.WaitForExit()
    if ($proc.ExitCode -ne 0) { throw "mysql failed: $err" }
    return $out.Trim()
}

function Sql-Scalar {
    param([string]$Sql)
    $out = Invoke-Sql $Sql
    if ($null -eq $out -or $out -eq '') { return $null }
    return ($out -split "`n")[0].Trim()
}

function Fail {
    param([string]$Message)
    Write-Host "FAIL  $Message" -ForegroundColor Red
    exit 1
}

# ---------------------------------------------------------------- preflight

Write-Host "Phase 6 load check: $Count outbox events" -ForegroundColor Cyan

$health = $null
try {
    $health = Invoke-WebRequest "$Base/health" -UseBasicParsing -TimeoutSec 10
} catch {
    Fail "API not reachable at $Base/health :: $($_.Exception.Message)"
}
if ($health.StatusCode -ne 200) { Fail "health returned $($health.StatusCode)" }

$adminPublicId = Sql-Scalar "SELECT public_id FROM users WHERE username = 'admin' AND status = 'ACTIVE' LIMIT 1;"
if (-not $adminPublicId) { Fail 'active admin user not found' }

$pendingBefore = [int](Sql-Scalar "SELECT COUNT(*) FROM outbox_events WHERE status IN ('PENDING','PROCESSING');")
if ($pendingBefore -gt 0) {
    Write-Host "WARN  outbox already has $pendingBefore pending rows; they will be drained too" -ForegroundColor Yellow
}

# ---------------------------------------------------------------- enqueue

Write-Host "Enqueuing $Count events for user $adminPublicId ..."
$startedAt = Get-Date
$rows = New-Object System.Text.StringBuilder
for ($i = 1; $i -le $Count; $i++) {
    $eventId = $Tag + $i.ToString('000000000000000000000')
    $key = "load6-$i"
    $payload = '{"notifications":[{"users":["' + $adminPublicId + '"],"type":"LAB_LOAD","titleEn":"Load test ' + $i + '","bodyEn":"Synthetic load event ' + $i + '","severity":"INFO","dedupeKey":"' + $key + '"}]}'
    if ($i -gt 1) { [void]$rows.Append(',') }
    [void]$rows.Append("('$eventId','LAB_LOAD','LOAD_TEST','$payload','PENDING')")
}
$sql = "INSERT INTO outbox_events (event_id, event_type, aggregate_type, payload_json, status) VALUES " + $rows.ToString() + ";"
Invoke-Sql $sql | Out-Null

$enqueued = [int](Sql-Scalar "SELECT COUNT(*) FROM outbox_events WHERE event_type = 'LAB_LOAD';")
if ($enqueued -ne $Count) { Fail "expected $Count events, found $enqueued" }
Write-Host "Enqueued $enqueued events in $([int]((Get-Date) - $startedAt).TotalSeconds)s"

# ---------------------------------------------------------------- drain

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$lastReport = Get-Date
$remaining = $Count
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 5
    $remaining = [int](Sql-Scalar "SELECT COUNT(*) FROM outbox_events WHERE event_type = 'LAB_LOAD' AND status IN ('PENDING','PROCESSING');")
    if ($remaining -eq 0) { break }
    if (((Get-Date) - $lastReport).TotalSeconds -ge 20) {
        $sent = [int](Sql-Scalar "SELECT COUNT(*) FROM outbox_events WHERE event_type = 'LAB_LOAD' AND status = 'SENT';")
        Write-Host ("  ... {0} sent, {1} remaining, {2}s elapsed" -f $sent, $remaining, [int]((Get-Date) - $startedAt).TotalSeconds)
        $lastReport = Get-Date
    }
}

# The dispatcher marks an event SENT as soon as the broker accepted it, so the
# inbox rows written by the MDB can trail the last SENT by a few seconds.
$consumerDeadline = (Get-Date).AddSeconds(180)
while ((Get-Date) -lt $consumerDeadline) {
    $delivered = [int](Sql-Scalar "SELECT COUNT(*) FROM notifications WHERE dedupe_key LIKE 'load6-%';")
    if ($delivered -ge $Count) { break }
    Start-Sleep -Seconds 2
}

$elapsed = [int]((Get-Date) - $startedAt).TotalSeconds
$sentCount = [int](Sql-Scalar "SELECT COUNT(*) FROM outbox_events WHERE event_type = 'LAB_LOAD' AND status = 'SENT';")
$deadCount = [int](Sql-Scalar "SELECT COUNT(*) FROM outbox_events WHERE event_type = 'LAB_LOAD' AND status = 'DEAD';")
$retried = [int](Sql-Scalar "SELECT COUNT(*) FROM outbox_events WHERE event_type = 'LAB_LOAD' AND attempts <> 1;")
$notifRows = [int](Sql-Scalar "SELECT COUNT(*) FROM notifications WHERE dedupe_key LIKE 'load6-%';")
$notifDistinct = [int](Sql-Scalar "SELECT COUNT(DISTINCT dedupe_key) FROM notifications WHERE dedupe_key LIKE 'load6-%';")
$leftoverClaims = [int](Sql-Scalar "SELECT COUNT(*) FROM outbox_events WHERE event_type = 'LAB_LOAD' AND (claim_token IS NOT NULL OR claimed_at IS NOT NULL);")

Write-Host ''
Write-Host ("sent={0} dead={1} pending={2} retried={3} notifications={4} distinct={5} leftoverClaims={6} elapsed={7}s" -f `
    $sentCount, $deadCount, $remaining, $retried, $notifRows, $notifDistinct, $leftoverClaims, $elapsed)

$failed = $false
if ($remaining -ne 0) { Write-Host "FAIL  $remaining events never left PENDING/PROCESSING" -ForegroundColor Red; $failed = $true }
if ($sentCount -ne $Count) { Write-Host "FAIL  expected $Count SENT, got $sentCount" -ForegroundColor Red; $failed = $true }
if ($deadCount -ne 0) { Write-Host "FAIL  $deadCount events became DEAD" -ForegroundColor Red; $failed = $true }
if ($retried -ne 0) { Write-Host "FAIL  $retried events were attempted more than once" -ForegroundColor Red; $failed = $true }
if ($notifRows -ne $Count) { Write-Host "FAIL  expected $Count inbox rows, got $notifRows" -ForegroundColor Red; $failed = $true }
if ($notifDistinct -ne $Count) { Write-Host "FAIL  expected $Count distinct dedupe keys, got $notifDistinct" -ForegroundColor Red; $failed = $true }
if ($leftoverClaims -ne 0) { Write-Host "FAIL  $leftoverClaims events still hold a claim token" -ForegroundColor Red; $failed = $true }

# ---------------------------------------------------------------- cleanup

if (-not $Keep) {
    Invoke-Sql "DELETE FROM notifications WHERE dedupe_key LIKE 'load6-%'; DELETE FROM outbox_events WHERE event_type = 'LAB_LOAD';" | Out-Null
    Write-Host 'Cleaned up synthetic rows.'
} else {
    Write-Host 'Kept synthetic rows (-Keep).'
}

if ($failed) { exit 1 }
Write-Host ''
Write-Host ("RESULT: {0}/{0} events delivered exactly once in {1}s (throughput {2}/s)" -f $Count, $elapsed, [math]::Round($Count / [math]::Max(1, $elapsed), 2)) -ForegroundColor Green
exit 0
