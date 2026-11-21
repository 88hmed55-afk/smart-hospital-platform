# Phase 6 functional regression: outbox hardening, notifications and live delivery.
# Covers TC-134..TC-139, C10 and TC-176 (JMS queue, MDB, dead-letter queue).
# Usage: powershell -ExecutionPolicy Bypass -File tools\test-phase6.ps1
# The script is ASCII-only on purpose: PowerShell 5.1 reads -File scripts as ANSI.
$ErrorActionPreference = 'Stop'
$Base = 'http://localhost:8080/api/v1'
$MySql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$Db = 'smart_hospital'
[System.Net.ServicePointManager]::DefaultConnectionLimit = 32
[System.Net.ServicePointManager]::Expect100Continue = $false

$script:Pass = 0
$script:Fail = 0
$script:Skipped = 0
$script:Failures = @()
$script:AdminPublicId = $null

# ---------------------------------------------------------------- database

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
    if ($proc.ExitCode -ne 0) {
        throw "mysql failed: $err"
    }
    return $out.Trim()
}

function Sql-Scalar {
    param([string]$Sql)
    $out = Invoke-Sql $Sql
    if ($null -eq $out -or $out -eq '') { return $null }
    return ($out -split "`n")[0].Trim()
}

# ---------------------------------------------------------------- harness

function New-Session {
    param([string]$Username, [string]$Password)
    $cookies = New-Object System.Net.CookieContainer
    $req = [System.Net.HttpWebRequest]::Create("$Base/auth/login")
    $req.Method = 'POST'
    $req.CookieContainer = $cookies
    $req.Timeout = 60000
    $req.ReadWriteTimeout = 60000
    $req.Accept = 'application/json'
    $json = @{ username = $Username; password = $Password } | ConvertTo-Json -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    $req.ContentType = 'application/json; charset=utf-8'
    $req.ContentLength = $bytes.Length
    $stream = $req.GetRequestStream()
    $stream.Write($bytes, 0, $bytes.Length)
    $stream.Close()
    $resp = $req.GetResponse()
    $reader = New-Object System.IO.StreamReader($resp.GetResponseStream(), [System.Text.Encoding]::UTF8)
    $raw = $reader.ReadToEnd()
    $reader.Close()
    $resp.Close()
    $parsed = $raw | ConvertFrom-Json
    if (-not $parsed.csrfToken) { throw "Login failed for $Username :: $raw" }
    return [pscustomobject]@{
        Cookies = $cookies
        Csrf    = $parsed.csrfToken
        User    = $parsed.user
    }
}

function Invoke-Api {
    param(
        $Session,
        [string]$Method = 'GET',
        [string]$Path,
        $Body = $null,
        [switch]$AllowError
    )
    $req = [System.Net.HttpWebRequest]::Create("$Base$Path")
    $req.Method = $Method
    $req.CookieContainer = $Session.Cookies
    $req.Timeout = 60000
    $req.ReadWriteTimeout = 60000
    $req.Accept = 'application/json'
    if ($Session.Csrf) { $req.Headers.Add('X-CSRF-Token', $Session.Csrf) }
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 12 -Compress
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
        $req.ContentType = 'application/json; charset=utf-8'
        $req.ContentLength = $bytes.Length
        $stream = $req.GetRequestStream()
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Close()
    }
    $status = 0
    $raw = ''
    try {
        $resp = $req.GetResponse()
        $status = [int]$resp.StatusCode
        $reader = New-Object System.IO.StreamReader($resp.GetResponseStream(), [System.Text.Encoding]::UTF8)
        $raw = $reader.ReadToEnd()
        $reader.Close()
        $resp.Close()
    } catch [System.Net.WebException] {
        $we = $_.Exception
        if ($null -ne $we.Response) {
            $status = [int]$we.Response.StatusCode
            $reader = New-Object System.IO.StreamReader($we.Response.GetResponseStream(), [System.Text.Encoding]::UTF8)
            $raw = $reader.ReadToEnd()
            $reader.Close()
            $we.Response.Close()
        } else {
            if (-not $AllowError) { throw "HTTP failure on $Method $Path :: $($we.Message)" }
            $raw = $we.Message
        }
    }
    $parsed = $null
    if ($raw) {
        try { $parsed = $raw | ConvertFrom-Json } catch { $parsed = $raw }
    }
    $result = [pscustomobject]@{ Status = $status; Body = $parsed; Raw = $raw }
    if (-not $AllowError -and $result.Status -ge 400) {
        throw "HTTP $($result.Status) on $Method $Path :: $($result.Raw)"
    }
    return $result
}

function Check {
    param([string]$Name, [scriptblock]$Test)
    try {
        $detail = & $Test
        $script:Pass++
        Write-Host ("  PASS  {0} {1}" -f $Name, $(if ($detail) { "($detail)" } else { '' })) -ForegroundColor Green
    } catch {
        $script:Fail++
        $script:Failures += "$Name :: $($_.Exception.Message)"
        Write-Host ("  FAIL  {0} :: {1}" -f $Name, $_.Exception.Message) -ForegroundColor Red
    }
}

function Skip {
    param([string]$Name, [string]$Reason)
    $script:Skipped++
    Write-Host ("  SKIP  {0} :: {1}" -f $Name, $Reason) -ForegroundColor Yellow
}

function Assert-True {
    param($Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Section {
    param([string]$Title)
    Write-Host "`n=== $Title ===" -ForegroundColor Cyan
}

function Wait-Until {
    param([scriptblock]$Test, [int]$TimeoutSeconds = 30, [string]$Label = 'condition')
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (& $Test) { return $true }
        Start-Sleep -Milliseconds 600
    }
    throw "Timed out after ${TimeoutSeconds}s waiting for $Label"
}

# ---------------------------------------------------------------- fixtures

function New-EventId {
    return ('01' + [guid]::NewGuid().ToString('N').Substring(0, 24)).ToUpper()
}

function Add-OutboxEvent {
    param(
        [string]$EventType,
        [string]$DedupeKey,
        [string]$Severity = 'INFO',
        [string]$UserPublicId = $null,
        [string]$RawPayload = $null,
        [int]$Priority = 0
    )
    $eventId = New-EventId
    if ($RawPayload) {
        $payload = $RawPayload
    } else {
        $user = if ($UserPublicId) { $UserPublicId } else { $script:AdminPublicId }
        $payload = @{
            notifications = @(
                @{
                    users      = @($user)
                    type       = $EventType
                    titleAr    = "Phase6 $DedupeKey"
                    titleEn    = "Phase6 $DedupeKey"
                    bodyAr     = 'phase6 harness'
                    bodyEn     = 'phase6 harness'
                    severity   = $Severity
                    dedupeKey  = $DedupeKey
                }
            )
        } | ConvertTo-Json -Depth 8 -Compress
    }
    $safe = $payload.Replace("'", "''")
    Invoke-Sql "INSERT INTO outbox_events (event_id, event_type, aggregate_type, payload_json, status, priority) VALUES ('$eventId', '$EventType', 'TEST', '$safe', 'PENDING', $Priority);" | Out-Null
    return $eventId
}

function Get-EventStatus {
    param([string]$EventId)
    return Sql-Scalar "SELECT status FROM outbox_events WHERE event_id = '$EventId';"
}

function Get-EventId {
    param([string]$EventId)
    return Sql-Scalar "SELECT id FROM outbox_events WHERE event_id = '$EventId';"
}

function Get-NotificationCount {
    param([string]$DedupeKey)
    return [int](Sql-Scalar "SELECT COUNT(*) FROM notifications WHERE dedupe_key = '$DedupeKey';")
}

function Get-DlqCount {
    # imqcmd writes a passfile warning to stderr and exits 1 on success, so run
    # it through cmd.exe and read the captured file instead of the pipeline.
    $imq = 'C:\glassfish7\mq\bin\imqcmd.exe'
    $passfile = Join-Path $env:TEMP 'sh-tc176-imq-pass.txt'
    $outfile = Join-Path $env:TEMP 'sh-tc176-imq-out.txt'
    Set-Content -Path $passfile -Value 'imq.imqcmd.password=admin' -Encoding ASCII
    cmd /c "`"$imq`" list dst -t q -b localhost:7676 -u admin -passfile `"$passfile`" > `"$outfile`" 2>&1"
    foreach ($line in (Get-Content $outfile)) {
        if ($line -match '^NotificationDLQ\s') {
            $parts = $line.Trim() -split '\s+'
            return [int]$parts[6]
        }
    }
    return 0
}

function Wait-EventStatus {
    param([string]$EventId, [string]$Status, [int]$TimeoutSeconds = 40)
    $id = $EventId
    $target = $Status
    Wait-Until -TimeoutSeconds $TimeoutSeconds -Label "event $id -> $target" -Test {
        (Get-EventStatus $id) -eq $target
    } | Out-Null
}

# ---------------------------------------------------------------- start

Write-Host 'Phase 6 regression: outbox + JMS + notifications + SSE' -ForegroundColor White
$admin = New-Session 'admin' 'Admin@2026Secure'
$script:AdminPublicId = Sql-Scalar "SELECT public_id FROM users WHERE username = 'admin';"
if (-not $script:AdminPublicId) { throw 'Admin public id not found' }

# Clean any residue from a previous run.
Invoke-Sql "DELETE FROM notifications WHERE dedupe_key LIKE 'tc6-%'; DELETE FROM outbox_events WHERE event_type LIKE 'TC6_%' OR event_type LIKE 'LAB_TC6_%';" | Out-Null

Section 'TC-134  Outbox claim and dispatch'

Check 'TC-134 event claimed, dispatched and inbox row created' {
    $key = 'tc6-134-dispatch'
    $eventId = Add-OutboxEvent -EventType 'TC6_DISPATCH' -DedupeKey $key
    Wait-EventStatus -EventId $eventId -Status 'SENT'
    $count = Get-NotificationCount $key
    Assert-True ($count -eq 1) "expected 1 notification, got $count"
    $attempts = Sql-Scalar "SELECT attempts FROM outbox_events WHERE event_id = '$eventId';"
    Assert-True ([int]$attempts -ge 1) "expected attempts >= 1, got $attempts"
    "status=SENT attempts=$attempts rows=$count"
}

Check 'TC-134 lease metadata is exposed by the operations API' {
    $res = Invoke-Api $admin GET '/admin/outbox/events?limit=5'
    Assert-True ($res.Status -eq 200) "expected 200, got $($res.Status)"
    $first = $res.Body.items[0]
    Assert-True ($null -ne $first.PSObject.Properties['claimedAt']) 'claimedAt missing'
    Assert-True ($null -ne $first.PSObject.Properties['eventId']) 'eventId missing'
    Assert-True ($null -ne $first.PSObject.Properties['createdAt']) 'createdAt missing'
    "keys=$($first.PSObject.Properties.Name -join ',')"
}

Section 'TC-135  Dedupe suppression'

Check 'TC-135 duplicate dedupe key creates no second inbox row' {
    $key = 'tc6-135-dedupe'
    $first = Add-OutboxEvent -EventType 'TC6_DEDUPE' -DedupeKey $key
    Wait-EventStatus -EventId $first -Status 'SENT'
    $second = Add-OutboxEvent -EventType 'TC6_DEDUPE' -DedupeKey $key
    Wait-EventStatus -EventId $second -Status 'SENT'
    $count = Get-NotificationCount $key
    Assert-True ($count -eq 1) "expected 1 notification after duplicate, got $count"
    "events=2 rows=$count"
}

Section 'TC-136  Poison event becomes DEAD and can be retried'

Check 'TC-136 invalid payload parks the event as DEAD' {
    Invoke-Sql "UPDATE settings SET setting_value = '1' WHERE setting_key = 'outbox.max.attempts';" | Out-Null
    try {
        # Valid JSON, invalid contract: notifications must be an array.
        $eventId = Add-OutboxEvent -EventType 'TC6_POISON' -DedupeKey 'tc6-136-poison' -RawPayload '{"notifications":"boom"}'
        Wait-EventStatus -EventId $eventId -Status 'DEAD' -TimeoutSeconds 45
        $attempts = Sql-Scalar "SELECT attempts FROM outbox_events WHERE event_id = '$eventId';"
        $errorText = Sql-Scalar "SELECT last_error FROM outbox_events WHERE event_id = '$eventId';"
        Assert-True ($errorText) 'expected a recorded failure message'
        return "attempts=$attempts error=$errorText"
    } finally {
        Invoke-Sql "UPDATE settings SET setting_value = '8' WHERE setting_key = 'outbox.max.attempts';" | Out-Null
    }
}

Check 'TC-136 retry endpoint requeues a DEAD event' {
    $eventId = Sql-Scalar "SELECT event_id FROM outbox_events WHERE event_type = 'TC6_POISON' ORDER BY id DESC LIMIT 1;"
    Assert-True ($eventId) 'poison event not found'
    $id = Get-EventId $eventId
    $res = Invoke-Api $admin POST "/admin/outbox/$id/retry"
    Assert-True ($res.Status -eq 200) "expected 200, got $($res.Status) :: $($res.Raw)"
    Assert-True ($res.Body.status -eq 'PENDING') "expected PENDING, got $($res.Body.status)"
    Assert-True ([int]$res.Body.attempts -eq 0) "expected attempts reset to 0, got $($res.Body.attempts)"
    "id=$id status=$($res.Body.status)"
}

Section 'TC-137  Per-user preferences'

Check 'TC-137 disabled category suppresses delivery' {
    $res = Invoke-Api $admin PUT '/notifications/preferences' @{
        categories = @(@{ category = 'LAB'; enabled = $false; minSeverity = 'INFO' })
    }
    Assert-True ($res.Status -eq 200) "expected 200, got $($res.Status) :: $($res.Raw)"
    try {
        $key = 'tc6-137-disabled'
        $eventId = Add-OutboxEvent -EventType 'LAB_TC6_OFF' -DedupeKey $key -Severity 'CRITICAL'
        Wait-EventStatus -EventId $eventId -Status 'SENT'
        $count = Get-NotificationCount $key
        Assert-True ($count -eq 0) "expected suppression, got $count rows"
        return 'event SENT, inbox row suppressed'
    } finally {
        Invoke-Api $admin PUT '/notifications/preferences' @{
            categories = @(@{ category = 'LAB'; enabled = $true; minSeverity = 'INFO' })
        } | Out-Null
    }
}

Check 'TC-137 minimum severity filters low-priority notifications' {
    $res = Invoke-Api $admin PUT '/notifications/preferences' @{
        categories = @(@{ category = 'LAB'; enabled = $true; minSeverity = 'WARNING' })
    }
    Assert-True ($res.Status -eq 200) "expected 200, got $($res.Status) :: $($res.Raw)"
    try {
        $lowKey = 'tc6-137-info'
        $low = Add-OutboxEvent -EventType 'LAB_TC6_INFO' -DedupeKey $lowKey -Severity 'INFO'
        Wait-EventStatus -EventId $low -Status 'SENT'
        $highKey = 'tc6-137-critical'
        $high = Add-OutboxEvent -EventType 'LAB_TC6_CRITICAL' -DedupeKey $highKey -Severity 'CRITICAL'
        Wait-EventStatus -EventId $high -Status 'SENT'
        $lowCount = Get-NotificationCount $lowKey
        $highCount = Get-NotificationCount $highKey
        Assert-True ($lowCount -eq 0) "expected INFO suppressed, got $lowCount"
        Assert-True ($highCount -eq 1) "expected CRITICAL delivered, got $highCount"
        return "info=$lowCount critical=$highCount"
    } finally {
        Invoke-Api $admin PUT '/notifications/preferences' @{
            categories = @(@{ category = 'LAB'; enabled = $true; minSeverity = 'INFO' })
        } | Out-Null
    }
}

Check 'TC-137 preferences endpoint returns every category' {
    $res = Invoke-Api $admin GET '/notifications/preferences'
    Assert-True ($res.Status -eq 200) "expected 200, got $($res.Status)"
    $names = @($res.Body.categories | ForEach-Object { $_.category })
    Assert-True ($names.Count -eq 7) "expected 7 categories, got $($names.Count)"
    foreach ($expected in @('CLINICAL', 'LAB', 'PHARMACY', 'BILLING', 'INVENTORY', 'SECURITY', 'SYSTEM')) {
        Assert-True ($names -contains $expected) "missing category $expected"
    }
    Assert-True (@($res.Body.severities).Count -eq 3) 'expected 3 severities'
    "categories=$($names -join ',')"
}

Section 'TC-138  Live stream (SSE)'

Check 'TC-138 stream responds 200 with text/event-stream and a ready event' {
    $req = [System.Net.HttpWebRequest]::Create("$Base/notifications/stream")
    $req.Method = 'GET'
    $req.CookieContainer = $admin.Cookies
    $req.Accept = 'text/event-stream'
    $req.Timeout = 20000
    $req.ReadWriteTimeout = 20000
    $req.KeepAlive = $false
    $resp = $req.GetResponse()
    try {
        $contentType = $resp.ContentType
        $reader = New-Object System.IO.StreamReader($resp.GetResponseStream(), [System.Text.Encoding]::UTF8)
        $lines = @()
        for ($i = 0; $i -lt 6; $i++) {
            $line = $reader.ReadLine()
            if ($null -eq $line) { break }
            $lines += $line
        }
        $reader.Close()
        Assert-True ($contentType -like 'text/event-stream*') "unexpected content type '$contentType'"
        $joined = $lines -join "`n"
        Assert-True ($joined -match 'connected|ready') "no ready frame: $joined"
        return "content-type=$contentType frames=$($lines.Count)"
    } finally {
        $resp.Close()
    }
}

Section 'TC-139  Monitoring'

Check 'TC-139 stats expose counts, backlog and dead samples' {
    Invoke-Sql "UPDATE settings SET setting_value = '1' WHERE setting_key = 'outbox.max.attempts';" | Out-Null
    try {
        $deadEvent = Add-OutboxEvent -EventType 'TC6_DEAD_STATS' -DedupeKey 'tc6-139-dead' -RawPayload '{"notifications":"boom"}'
        Wait-EventStatus -EventId $deadEvent -Status 'DEAD' -TimeoutSeconds 45
    } finally {
        Invoke-Sql "UPDATE settings SET setting_value = '8' WHERE setting_key = 'outbox.max.attempts';" | Out-Null
    }
    $res = Invoke-Api $admin GET '/admin/outbox/stats'
    Assert-True ($res.Status -eq 200) "expected 200, got $($res.Status)"
    Assert-True ($null -ne $res.Body.counts) 'counts missing'
    foreach ($status in @('PENDING', 'PROCESSING', 'SENT', 'FAILED', 'DEAD')) {
        Assert-True ($null -ne $res.Body.counts.PSObject.Properties[$status]) "counts.$status missing"
    }
    Assert-True ($null -ne $res.Body.backlog.PSObject.Properties['oldestAgeSeconds']) 'oldestAgeSeconds missing'
    Assert-True ($null -ne $res.Body.PSObject.Properties['deadSamples']) 'deadSamples missing'
    Assert-True ($null -ne $res.Body.PSObject.Properties['pushConnections']) 'pushConnections missing'
    Assert-True ([int]$res.Body.dead -ge 1) "expected a DEAD event, got $($res.Body.dead)"
    Assert-True (@($res.Body.deadSamples).Count -ge 1) 'expected at least one dead sample'
    "sent=$($res.Body.sent) dead=$($res.Body.dead) backlog=$($res.Body.backlog.total)"
}

Check 'TC-139 event listing rejects an unknown status' {
    $res = Invoke-Api $admin GET '/admin/outbox/events?status=NOPE' -AllowError
    Assert-True ($res.Status -eq 400) "expected 400, got $($res.Status)"
    Assert-True ($res.Body.code -eq 'OUTBOX_STATUS_INVALID') "unexpected code $($res.Body.code)"
    'code=OUTBOX_STATUS_INVALID'
}

Section 'C10  Concurrent dispatch'

Check 'C10 eight concurrent events each deliver exactly once' {
    $keys = @()
    $ids = @()
    for ($i = 1; $i -le 8; $i++) {
        $key = "tc6-c10-$i"
        $keys += $key
        $ids += (Add-OutboxEvent -EventType 'TC6_CONCURRENT' -DedupeKey $key)
    }
    foreach ($id in $ids) {
        Wait-EventStatus -EventId $id -Status 'SENT' -TimeoutSeconds 60
    }
    $total = 0
    foreach ($key in $keys) {
        $count = Get-NotificationCount $key
        Assert-True ($count -eq 1) "expected exactly 1 row for $key, got $count"
        $total += $count
    }
    "events=8 rows=$total"
}

Section 'TC-176  JMS outage after commit'

Check 'TC-176 a failed JMS send leaves the committed event PENDING and retries later' {
    $key = 'tc6-176-outage'
    Invoke-Sql "DELETE FROM notifications WHERE dedupe_key = '$key'; DELETE FROM outbox_events WHERE event_type = 'TC6_JMS_OUTAGE';" | Out-Null
    # The setting is a test-only chaos hook; it makes the producer throw exactly
    # like an unreachable broker. It is removed in the finally block.
    Invoke-Sql "INSERT INTO settings (setting_key, setting_value, value_type, category) VALUES ('outbox.jms.simulate.failure', 'true', 'BOOL', 'PLATFORM') ON DUPLICATE KEY UPDATE setting_value = 'true';" | Out-Null
    $id = $null
    $attempts = 0
    try {
        $id = Add-OutboxEvent -EventType 'TC6_JMS_OUTAGE' -DedupeKey $key
        Wait-Until -TimeoutSeconds 60 -Label 'outbox event returned to PENDING after the failed send' -Test {
            (Get-EventStatus $id) -eq 'PENDING' -and
            [int](Sql-Scalar "SELECT attempts FROM outbox_events WHERE event_id = '$id';") -ge 1
        } | Out-Null
        $status = Get-EventStatus $id
        $attempts = [int](Sql-Scalar "SELECT attempts FROM outbox_events WHERE event_id = '$id';")
        $lastError = Sql-Scalar "SELECT last_error FROM outbox_events WHERE event_id = '$id';"
        $rows = Get-NotificationCount $key
        $stored = [int](Sql-Scalar "SELECT COUNT(*) FROM outbox_events WHERE event_id = '$id';")
        Assert-True ($status -eq 'PENDING') "expected PENDING after the JMS failure, got $status"
        Assert-True ($attempts -ge 1) "expected at least one attempt, got $attempts"
        Assert-True ($lastError -like '*Simulated JMS outage*') "unexpected last_error: $lastError"
        Assert-True ($rows -eq 0) "no inbox row may exist while JMS is down, got $rows"
        Assert-True ($stored -eq 1) 'the committed outbox event must survive the outage'
    } finally {
        Invoke-Sql "DELETE FROM settings WHERE setting_key = 'outbox.jms.simulate.failure';" | Out-Null
    }
    Wait-EventStatus -EventId $id -Status 'SENT' -TimeoutSeconds 60
    Wait-Until -TimeoutSeconds 30 -Label 'inbox row after JMS recovery' -Test {
        (Get-NotificationCount $key) -eq 1
    } | Out-Null
    "status=PENDING during outage, then SENT; attempts=$attempts; rows=1"
}

Check 'TC-176 an undeliverable message is parked on the DLQ and can be retried' {
    $key = 'tc6-176-dlq'
    $dlqBefore = Get-DlqCount
    Invoke-Sql "DELETE FROM notifications WHERE dedupe_key = '$key'; DELETE FROM outbox_events WHERE event_type = 'TC6_JMS_DLQ';" | Out-Null
    Invoke-Sql "INSERT INTO settings (setting_key, setting_value, value_type, category) VALUES ('outbox.jms.simulate.delivery-failure', 'true', 'BOOL', 'PLATFORM') ON DUPLICATE KEY UPDATE setting_value = 'true';" | Out-Null
    $id = $null
    try {
        $id = Add-OutboxEvent -EventType 'TC6_JMS_DLQ' -DedupeKey $key
        Wait-EventStatus -EventId $id -Status 'DEAD' -TimeoutSeconds 90 | Out-Null
        $error = Sql-Scalar "SELECT last_error FROM outbox_events WHERE event_id = '$id';"
        $rows = Get-NotificationCount $key
        Assert-True ($error -like '*Queue delivery failed*') "unexpected last_error: $error"
        Assert-True ($rows -eq 0) "no inbox row may exist after a failed delivery, got $rows"
    } finally {
        Invoke-Sql "DELETE FROM settings WHERE setting_key = 'outbox.jms.simulate.delivery-failure';" | Out-Null
    }
    $dlqCount = Get-DlqCount
    Assert-True ($dlqCount -gt $dlqBefore) "expected a new parked DLQ message (before=$dlqBefore, after=$dlqCount)"
    $numericId = Get-EventId $id
    $retry = Invoke-Api $admin POST "/admin/outbox/$numericId/retry"
    Assert-True ($retry.Status -eq 200) "retry returned HTTP $($retry.Status) :: $($retry.Raw)"
    Wait-EventStatus -EventId $id -Status 'SENT' -TimeoutSeconds 60 | Out-Null
    Wait-Until -TimeoutSeconds 30 -Label 'inbox row after DLQ retry' -Test {
        (Get-NotificationCount $key) -eq 1
    } | Out-Null
    "dlq=$dlqCount, retried to SENT, rows=1"
}

Section 'TC-134  Priority-aware claiming'

Check 'TC-134 priority: a CRITICAL event jumps a long INFO backlog' {
    $critKey = 'tc6-140-critical'
    Invoke-Sql "DELETE FROM notifications WHERE dedupe_key LIKE 'tc6-140-%'; DELETE FROM outbox_events WHERE event_type IN ('TC6_PRIO_INFO','TC6_PRIO_CRITICAL');" | Out-Null
    # Pause the poller so the backlog is built before any claim happens, then
    # give an in-flight tick time to finish before inserting.
    Invoke-Sql "INSERT INTO settings (setting_key, setting_value, value_type, category) VALUES ('outbox.dispatch.paused', 'true', 'BOOL', 'PLATFORM') ON DUPLICATE KEY UPDATE setting_value = 'true';" | Out-Null
    Start-Sleep -Seconds 7
    $crit = $null
    try {
        $rows = @()
        for ($i = 1; $i -le 60; $i++) {
            $eventId = New-EventId
            $dedupe = "tc6-140-info-$i"
            $payload = @{
                notifications = @(@{
                    users = @($script:AdminPublicId); type = 'TC6_PRIO_INFO'
                    titleAr = "Prio $dedupe"; titleEn = "Prio $dedupe"
                    bodyAr = 'backlog'; bodyEn = 'backlog'
                    severity = 'INFO'; dedupeKey = $dedupe
                })
            } | ConvertTo-Json -Depth 8 -Compress
            $rows += "('$eventId','TC6_PRIO_INFO','TEST','$payload','PENDING',0,0,NOW(6))"
        }
        Invoke-Sql ("INSERT INTO outbox_events (event_id, event_type, aggregate_type, payload_json, status, attempts, priority, next_attempt_at) VALUES " + ($rows -join ',') + ";") | Out-Null
        $crit = Add-OutboxEvent -EventType 'TC6_PRIO_CRITICAL' -DedupeKey $critKey -Severity 'CRITICAL' -Priority 100
    } finally {
        Invoke-Sql "DELETE FROM settings WHERE setting_key = 'outbox.dispatch.paused';" | Out-Null
    }
    Wait-EventStatus -EventId $crit -Status 'SENT' -TimeoutSeconds 90
    $remaining = [int](Sql-Scalar "SELECT COUNT(*) FROM outbox_events WHERE event_type = 'TC6_PRIO_INFO' AND status = 'PENDING';")
    Assert-True ($remaining -ge 10) "expected a remaining INFO backlog, got $remaining"
    "critical SENT while $remaining INFO events still PENDING"
}

Section 'TC-135  Notification templates'

Check 'TC-135 template: a template renders parameters and drives severity/priority' {
    $key = 'tc6-141-template'
    Invoke-Sql "DELETE FROM notifications WHERE dedupe_key = '$key'; DELETE FROM outbox_events WHERE event_type = 'TC6_TEMPLATE';" | Out-Null
    $payload = @{
        notifications = @(@{
            users = @($script:AdminPublicId)
            type = 'LAB_TC6_TEMPLATE'
            templateCode = 'LAB_CRITICAL'
            params = @{ test = 'CBC'; patient = 'P-001' }
            dedupeKey = $key
        })
    } | ConvertTo-Json -Depth 8 -Compress
    $id = Add-OutboxEvent -EventType 'TC6_TEMPLATE' -DedupeKey $key -RawPayload $payload
    Wait-EventStatus -EventId $id -Status 'SENT'
    $severity = Sql-Scalar "SELECT severity FROM notifications WHERE dedupe_key = '$key';"
    $priority = Sql-Scalar "SELECT priority FROM notifications WHERE dedupe_key = '$key';"
    $body = Sql-Scalar "SELECT body_ar FROM notifications WHERE dedupe_key = '$key';"
    $title = Sql-Scalar "SELECT title_ar FROM notifications WHERE dedupe_key = '$key';"
    Assert-True ($severity -eq 'CRITICAL') "expected template severity CRITICAL, got $severity"
    Assert-True ([int]$priority -eq 100) "expected priority 100 from CRITICAL, got $priority"
    Assert-True ($body -like '*CBC*') "rendered body missing the test parameter: $body"
    Assert-True ($body -like '*P-001*') "rendered body missing the patient parameter: $body"
    Assert-True ($body -notlike '*{test}*') "placeholder was not substituted: $body"
    Assert-True ($title) 'template title must not be empty'
    "severity=$severity priority=$priority"
}

Check 'TC-135 template: catalogue is exposed and validated' {
    $res = Invoke-Api $admin GET '/admin/outbox/templates'
    Assert-True ($res.Status -eq 200) "expected 200, got $($res.Status)"
    $codes = @($res.Body.items | ForEach-Object { $_.code })
    Assert-True ($codes.Count -ge 7) "expected at least 7 templates, got $($codes.Count)"
    Assert-True ($codes -contains 'LAB_CRITICAL') 'LAB_CRITICAL template missing'
    $missing = Invoke-Api $admin PUT '/admin/outbox/templates/NOPE_CODE' @{
        titleAr = 'x'; titleEn = 'x'; bodyAr = 'x'; bodyEn = 'x'
    } -AllowError
    Assert-True ($missing.Status -eq 404) "expected 404 for an unknown template, got $($missing.Status)"
    "templates=$($codes.Count) unknown=404"
}

Section 'TC-139  Priority inbox ordering'

Check 'TC-139 inbox: orders by priority before recency' {
    $keys = @('tc6-142-critical', 'tc6-142-warning', 'tc6-142-info')
    Invoke-Sql "DELETE FROM notifications WHERE dedupe_key IN ('tc6-142-critical','tc6-142-warning','tc6-142-info'); DELETE FROM outbox_events WHERE event_type = 'TC6_ORDER';" | Out-Null
    # Deliberately insert the least important row last: recency alone would put
    # INFO first, priority ordering must keep CRITICAL at the top.
    foreach ($spec in @(
        @{ key = 'tc6-142-critical'; severity = 'CRITICAL' },
        @{ key = 'tc6-142-warning'; severity = 'WARNING' },
        @{ key = 'tc6-142-info'; severity = 'INFO' }
    )) {
        $id = Add-OutboxEvent -EventType 'TC6_ORDER' -DedupeKey $spec.key -Severity $spec.severity
        Wait-EventStatus -EventId $id -Status 'SENT'
        Start-Sleep -Milliseconds 120
    }
    $res = Invoke-Api $admin GET '/notifications?size=100'
    Assert-True ($res.Status -eq 200) "expected 200, got $($res.Status)"
    $all = @($res.Body.items | ForEach-Object { $_.dedupeKey })
    $ci = [array]::IndexOf($all, 'tc6-142-critical')
    $wi = [array]::IndexOf($all, 'tc6-142-warning')
    $ii = [array]::IndexOf($all, 'tc6-142-info')
    Assert-True ($ci -ge 0 -and $wi -ge 0 -and $ii -ge 0) "missing rows: crit=$ci warn=$wi info=$ii"
    Assert-True ($ci -lt $wi) "CRITICAL ($ci) must precede WARNING ($wi)"
    Assert-True ($wi -lt $ii) "WARNING ($wi) must precede INFO ($ii)"
    "order=critical($ci) < warning($wi) < info($ii)"
}

Section 'TC-137  Stream lifecycle'

Check 'TC-137 stream: sends proxy-safe headers' {
    $req = [System.Net.HttpWebRequest]::Create("$Base/notifications/stream")
    $req.Method = 'GET'
    $req.CookieContainer = $admin.Cookies
    $req.Accept = 'text/event-stream'
    $req.Timeout = 20000
    $req.ReadWriteTimeout = 20000
    $req.KeepAlive = $false
    $resp = $req.GetResponse()
    try {
        $cache = $resp.Headers['Cache-Control']
        $accel = $resp.Headers['X-Accel-Buffering']
        Assert-True ($cache -like '*no-transform*') "Cache-Control missing no-transform: $cache"
        Assert-True ($accel -eq 'no') "X-Accel-Buffering must be no, got $accel"
        "cache-control=$cache x-accel-buffering=$accel"
    } finally {
        $resp.Close()
    }
}

Check 'TC-137 stream: closes after the session is revoked' {
    $admin2 = New-Session 'admin' 'Admin@2026Secure'
    $sessionId = Sql-Scalar "SELECT MAX(s.id) FROM user_sessions s JOIN users u ON u.id = s.user_id WHERE u.username = 'admin' AND s.ended_at IS NULL;"
    Assert-True ($sessionId) 'second session row not found'
    $req = [System.Net.HttpWebRequest]::Create("$Base/notifications/stream")
    $req.Method = 'GET'
    $req.CookieContainer = $admin2.Cookies
    $req.Accept = 'text/event-stream'
    $req.Timeout = 30000
    $req.ReadWriteTimeout = 70000
    $req.KeepAlive = $false
    $resp = $req.GetResponse()
    $reader = New-Object System.IO.StreamReader($resp.GetResponseStream(), [System.Text.Encoding]::UTF8)
    try {
        Assert-True ($resp.StatusCode -eq 200) "expected 200, got $($resp.StatusCode)"
        $null = $reader.ReadLine()   # connected comment
        $null = $reader.ReadLine()   # blank line
        $null = $reader.ReadLine()   # ready event
        $revoke = Invoke-Api $admin DELETE "/admin/sessions/$sessionId"
        Assert-True ($revoke.Status -eq 204) "revoke returned HTTP $($revoke.Status)"
        $closed = $false
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        while ($sw.Elapsed.TotalSeconds -lt 60) {
            try {
                $line = $reader.ReadLine()
            } catch {
                break
            }
            if ($null -eq $line) { $closed = $true; break }
        }
        Assert-True $closed 'stream stayed open after the session was revoked'
        "stream closed after $([int]$sw.Elapsed.TotalSeconds)s"
    } finally {
        $reader.Close()
        $resp.Close()
    }
}

# ---------------------------------------------------------------- cleanup

Invoke-Sql "DELETE FROM notifications WHERE dedupe_key LIKE 'tc6-%'; DELETE FROM outbox_events WHERE event_type LIKE 'TC6_%' OR event_type LIKE 'LAB_TC6_%';" | Out-Null
Write-Host ''
Write-Host ('RESULT: {0} PASS / {1} FAIL / {2} SKIP' -f $script:Pass, $script:Fail, $script:Skipped) -ForegroundColor White
if ($script:Fail -gt 0) {
    Write-Host 'Failures:' -ForegroundColor Red
    foreach ($failure in $script:Failures) { Write-Host "  - $failure" -ForegroundColor Red }
    exit 1
}
exit 0
