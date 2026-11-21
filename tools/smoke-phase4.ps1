# Phase 4 API smoke test — verifies every new endpoint responds with the expected shape.
# Usage: powershell -ExecutionPolicy Bypass -File tools\smoke-phase4.ps1
$ErrorActionPreference = 'Stop'
$Base = 'http://localhost:8080/api/v1'

$script:Cookies = New-Object System.Net.CookieContainer
$script:Csrf = $null
$script:Pass = 0
$script:Fail = 0
$script:Failures = @()

function Invoke-Api {
    param(
        [string]$Method = 'GET',
        [string]$Path,
        $Body = $null,
        [switch]$AllowError
    )
    $req = [System.Net.HttpWebRequest]::Create("$Base$Path")
    $req.Method = $Method
    $req.CookieContainer = $script:Cookies
    $req.Timeout = 60000
    $req.ReadWriteTimeout = 60000
    $req.Accept = 'application/json'
    if ($script:Csrf) { $req.Headers.Add('X-CSRF-Token', $script:Csrf) }
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 12 -Compress
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
        $req.ContentType = 'application/json; charset=utf-8'
        $req.ContentLength = $bytes.Length
        $stream = $req.GetRequestStream()
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Close()
    }
    $resp = $null
    try {
        $resp = $req.GetResponse()
    } catch [System.Net.WebException] {
        if ($_.Exception.Response) { $resp = $_.Exception.Response } else { throw }
    }
    $status = [int]$resp.StatusCode
    $reader = New-Object System.IO.StreamReader($resp.GetResponseStream(), [System.Text.Encoding]::UTF8)
    $raw = $reader.ReadToEnd()
    $reader.Close()
    $resp.Close()
    $parsed = $null
    if ($raw) {
        try { $parsed = $raw | ConvertFrom-Json } catch { $parsed = $raw }
    }
    $result = [pscustomobject]@{
        Status = $status
        Body   = $parsed
        Raw    = $raw
    }
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

function Assert-True {
    param($Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

Write-Host "`n=== Phase 4 smoke test ===" -ForegroundColor Cyan

# ---------------------------------------------------------------- login
Check 'login' {
    $r = Invoke-Api -Method POST -Path '/auth/login' -Body @{ username = 'admin'; password = 'Admin@2026Secure' }
    Assert-True ($r.Status -eq 200) "status $($r.Status)"
    $script:Csrf = $r.Body.csrfToken
    Assert-True ([bool]$script:Csrf) 'csrfToken missing'
    $r.Body.user.username
}

# ---------------------------------------------------------------- ER
Check 'GET /er/triage-levels' {
    $r = Invoke-Api -Path '/er/triage-levels'
    Assert-True ($r.Body.Count -ge 5) "expected >=5 triage levels, got $($r.Body.Count)"
    "$($r.Body.Count) levels"
}

Check 'GET /er/board' {
    $r = Invoke-Api -Path '/er/board'
    Assert-True ($null -ne $r.Body) 'empty board payload'
    'ok'
}

# ---------------------------------------------------------------- ADT
Check 'GET /wards' {
    $r = Invoke-Api -Path '/wards'
    Assert-True ($r.Body.Count -ge 2) "expected >=2 wards, got $($r.Body.Count)"
    "$($r.Body.Count) wards"
}

Check 'GET /beds/board' {
    $r = Invoke-Api -Path '/beds/board'
    Assert-True ($null -ne $r.Body) 'empty bed board'
    'ok'
}

Check 'GET /admissions' {
    $r = Invoke-Api -Path '/admissions?page=0&size=10'
    Assert-True ($null -ne $r.Body.items) 'items missing'
    "$($r.Body.total) total"
}

# ---------------------------------------------------------------- NUR
Check 'GET /nursing/tasks' {
    $r = Invoke-Api -Path '/nursing/tasks?page=0&size=10'
    Assert-True ($null -ne $r.Body.items) 'items missing'
    "$($r.Body.total) total"
}

# ---------------------------------------------------------------- LAB
Check 'GET /lab/tests' {
    $r = Invoke-Api -Path '/lab/tests'
    Assert-True ($r.Body.Count -ge 10) "expected >=10 tests, got $($r.Body.Count)"
    "$($r.Body.Count) tests"
}

Check 'GET /lab/orders' {
    $r = Invoke-Api -Path '/lab/orders?page=0&size=10'
    Assert-True ($null -ne $r.Body.items) 'items missing'
    "$($r.Body.total) total"
}

Check 'GET /lab/worklist' {
    $r = Invoke-Api -Path '/lab/worklist?page=0&size=10'
    Assert-True ($null -ne $r.Body.items) 'items missing'
    Assert-True ($null -ne $r.Body.counts) 'counts missing'
    "$($r.Body.items.Count) rows"
}

Check 'GET /lab/alerts' {
    $r = Invoke-Api -Path '/lab/alerts?limit=10'
    Assert-True ($null -ne $r.Body.items) 'items missing'
    "$($r.Body.count) alerts"
}

# ---------------------------------------------------------------- PHR
Check 'GET /pharmacy/drugs' {
    $r = Invoke-Api -Path '/pharmacy/drugs?page=0&size=10'
    Assert-True ($null -ne $r.Body.items) 'items missing'
    "$($r.Body.total) drugs"
}

Check 'GET /pharmacy/queue' {
    $r = Invoke-Api -Path '/pharmacy/queue?page=0&size=10'
    Assert-True ($null -ne $r.Body.items) 'items missing'
    "$($r.Body.total) queued"
}

Check 'GET /stock/batches' {
    $r = Invoke-Api -Path '/stock/batches?page=0&size=10'
    Assert-True ($null -ne $r.Body.items) 'items missing'
    "$($r.Body.total) batches"
}

Check 'GET /stock/movements' {
    $r = Invoke-Api -Path '/stock/movements?page=0&size=10'
    Assert-True ($null -ne $r.Body.items) 'items missing'
    "$($r.Body.total) movements"
}

Check 'GET /stock/alerts' {
    $r = Invoke-Api -Path '/stock/alerts?limit=10'
    Assert-True ($null -ne $r.Body.lowStock) 'lowStock missing'
    Assert-True ($null -ne $r.Body.expiring) 'expiring missing'
    "low=$($r.Body.lowStock.Count) expiring=$($r.Body.expiring.Count)"
}

# ---------------------------------------------------------------- NOT
Check 'GET /notifications' {
    $r = Invoke-Api -Path '/notifications?page=0&size=10'
    Assert-True ($null -ne $r.Body.items) 'items missing'
    "$($r.Body.total) total"
}

Check 'GET /notifications/unread-count' {
    $r = Invoke-Api -Path '/notifications/unread-count'
    Assert-True ($null -ne $r.Body.unread) 'unread missing'
    "unread=$($r.Body.unread)"
}

# ---------------------------------------------------------------- unauthorised
Check 'CSRF is enforced on writes' {
    $saved = $script:Csrf
    $script:Csrf = $null
    try {
        $r = Invoke-Api -Method POST -Path '/nursing/tasks' -Body @{ title = 'x'; patientPublicId = 'x' } -AllowError
        Assert-True ($r.Status -eq 403) "expected 403, got $($r.Status)"
        'blocked'
    } finally {
        $script:Csrf = $saved
    }
}

Write-Host "`n=== $script:Pass passed, $script:Fail failed ===" -ForegroundColor $(if ($script:Fail -eq 0) { 'Green' } else { 'Red' })
if ($script:Fail -gt 0) {
    $script:Failures | ForEach-Object { Write-Host " - $_" -ForegroundColor Red }
    exit 1
}
exit 0
