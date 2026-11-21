# Phase 4 functional regression: ER + ADT + NUR + LAB + PHR + concurrency.
# Covers TC-051..TC-083, TC-090..TC-101, TC-167/168/171/172/173.
# Usage: powershell -ExecutionPolicy Bypass -File tools\test-phase4.ps1
# The script is ASCII-only on purpose: PowerShell 5.1 reads -File scripts as ANSI.
$ErrorActionPreference = 'Stop'
$Base = 'http://localhost:8080/api/v1'
[System.Net.ServicePointManager]::DefaultConnectionLimit = 64
[System.Net.ServicePointManager]::Expect100Continue = $false

$script:Pass = 0
$script:Fail = 0
$script:Skipped = 0
$script:Failures = @()
$script:Worker = $null

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
            throw "Login failed for $Username :: $($we.Message)"
        }
    }
    $parsed = $raw | ConvertFrom-Json
    if ($status -ne 200 -or -not $parsed.csrfToken) {
        throw "Login failed for $Username :: HTTP $status $raw"
    }
    return [pscustomobject]@{
        Cookies = $cookies
        Csrf    = $parsed.csrfToken
        User    = $parsed.user
    }
}

function New-ReadySession {
    param([string]$Username, [string]$Password, [string]$NewPassword)
    $session = New-Session $Username $Password
    $change = Invoke-Api $session POST '/auth/change-password' @{
        currentPassword = $Password
        newPassword     = $NewPassword
    } -AllowError
    if ($change.Status -ne 200) {
        throw "Password change failed for $Username :: HTTP $($change.Status) $($change.Raw)"
    }
    return New-Session $Username $NewPassword
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

function Assert-Code {
    param($Result, [int]$Status, [string]$Code = $null)
    Assert-True ($Result.Status -eq $Status) "expected HTTP $Status, got $($Result.Status) :: $($Result.Raw)"
    if ($Code) {
        Assert-True ($Result.Body.code -eq $Code) "expected code $Code, got $($Result.Body.code) :: $($Result.Raw)"
    }
}

function Section {
    param([string]$Title)
    Write-Host "`n=== $Title ===" -ForegroundColor Cyan
}

# ---------------------------------------------------------------- parallel harness

$script:Worker = {
    param($job)
    if ($job.StartAt) {
        $delay = ($job.StartAt - (Get-Date)).TotalMilliseconds
        if ($delay -gt 0) { Start-Sleep -Milliseconds ([int]$delay) }
    }
    $req = [System.Net.HttpWebRequest]::Create($job.Url)
    $req.Method = $job.Method
    $req.Timeout = 60000
    $req.ReadWriteTimeout = 60000
    $req.Accept = 'application/json'
    $req.Headers.Add('Cookie', $job.Cookie)
    if ($job.Csrf) { $req.Headers.Add('X-CSRF-Token', $job.Csrf) }
    if ($null -ne $job.Body) {
        $json = $job.Body | ConvertTo-Json -Depth 12 -Compress
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
            $raw = $we.Message
        }
    }
    $parsed = $null
    if ($raw) {
        try { $parsed = $raw | ConvertFrom-Json } catch { $parsed = $raw }
    }
    return [pscustomobject]@{ Status = $status; Body = $parsed; Raw = $raw }
}

function New-Job {
    param($Session, [string]$Method, [string]$Path, $Body = $null, $StartAt = $null)
    return @{
        Url     = "$Base$Path"
        Method  = $Method
        Body    = $Body
        Cookie  = $Session.Cookies.GetCookieHeader([uri]$Base)
        Csrf    = $Session.Csrf
        StartAt = $StartAt
    }
}

function Invoke-Parallel {
    param($Jobs)
    $pool = [runspacefactory]::CreateRunspacePool(1, [Math]::Max(1, @($Jobs).Count))
    $pool.Open()
    $runs = @()
    foreach ($job in @($Jobs)) {
        $ps = [powershell]::Create()
        $ps.RunspacePool = $pool
        [void]$ps.AddScript($script:Worker).AddArgument($job)
        $runs += [pscustomobject]@{ PS = $ps; Handle = $ps.BeginInvoke() }
    }
    $out = @()
    foreach ($run in $runs) {
        $res = @($run.PS.EndInvoke($run.Handle))
        if ($res.Count -gt 0) { $out += $res[$res.Count - 1] }
        $run.PS.Dispose()
    }
    $pool.Close()
    $pool.Dispose()
    return $out
}

# ---------------------------------------------------------------- domain helpers

function New-Patient {
    param([string]$Label)
    $r = Invoke-Api $script:Admin POST '/patients' @{
        fullName = "QA $Label $script:RunId"
        gender   = 'FEMALE'
    }
    Assert-True ($r.Status -eq 201) "patient create failed: $($r.Raw)"
    return $r.Body
}

function New-Encounter {
    param([string]$PatientPublicId, [string]$Type = 'OUTPATIENT')
    $r = Invoke-Api $script:Admin POST '/encounters' @{
        patientPublicId = $PatientPublicId
        type            = $Type
        chiefComplaint  = 'QA phase4 regression'
    }
    Assert-True ($r.Status -eq 201) "encounter create failed: $($r.Raw)"
    return $r.Body
}

function New-EncounterId {
    param([string]$PatientPublicId, [string]$Type = 'OUTPATIENT')
    return (New-Encounter $PatientPublicId $Type).encounter.publicId
}

function New-Admission {
    param([string]$PatientPublicId, [string]$EncounterPublicId, [string]$Reason)
    $r = Invoke-Api $script:Admin POST '/admissions' @{
        patientPublicId = $PatientPublicId
        encounterPublicId = $EncounterPublicId
        reason          = $Reason
    }
    Assert-True ($r.Status -eq 201) "admission request failed: $($r.Raw)"
    return $r.Body.admission
}

function Approve-Admission {
    param($Admission)
    $r = Invoke-Api $script:Admin POST "/admissions/$($Admission.publicId)/approve" @{ version = $Admission.version }
    Assert-True ($r.Status -eq 200) "approve failed: $($r.Raw)"
    return $r.Body.admission
}

function Assign-Bed {
    param($Admission, [string]$BedPublicId)
    $r = Invoke-Api $script:Admin POST "/admissions/$($Admission.publicId)/assign-bed" @{
        bedPublicId = $BedPublicId
        version     = $Admission.version
    }
    Assert-True ($r.Status -eq 200) "assign bed failed: $($r.Raw)"
    return $r.Body.admission
}

function Discharge-Admission {
    param($Admission, [string]$Type = 'HOME')
    $r = Invoke-Api $script:Admin POST "/admissions/$($Admission.publicId)/discharge" @{
        dischargeType = $Type
        version       = $Admission.version
    }
    Assert-True ($r.Status -eq 200) "discharge failed: $($r.Raw)"
    return $r.Body.admission
}

function Get-Board {
    return (Invoke-Api $script:Admin GET '/beds/board').Body
}

function Find-BedById {
    param($Board, [string]$Id)
    foreach ($w in @($Board.wards)) {
        foreach ($bed in @($w.beds)) {
            if ($bed.publicId -eq $Id) { return $bed }
        }
    }
    return $null
}

function Find-BedByCode {
    param($Board, [string]$Code)
    foreach ($w in @($Board.wards)) {
        foreach ($bed in @($w.beds)) {
            if ($bed.code -eq $Code) { return $bed }
        }
    }
    return $null
}

function Find-AvailableBed {
    param($Board, [string]$PreferWard = 'WARD-MED-A')
    $fallback = $null
    foreach ($w in @($Board.wards)) {
        foreach ($bed in @($w.beds)) {
            if ($bed.status -eq 'AVAILABLE') {
                if ($w.code -eq $PreferWard) { return $bed }
                if ($null -eq $fallback) { $fallback = $bed }
            }
        }
    }
    return $fallback
}

function Release-Bed {
    param([string]$BedPublicId)
    $board = Get-Board
    $bed = Find-BedById $board $BedPublicId
    if ($null -eq $bed) { return }
    if ($bed.status -eq 'CLEANING' -or $bed.status -eq 'MAINTENANCE' -or $bed.status -eq 'RESERVED') {
        [void](Invoke-Api $script:Admin POST "/beds/$BedPublicId/status" @{
            status = 'AVAILABLE'
            version = $bed.version
        } -AllowError)
    }
}

function Find-AdmissionByPatient {
    param([string]$PatientPublicId, [string]$Status = 'REQUESTED')
    $r = Invoke-Api $script:Admin GET "/admissions?status=$Status&size=100"
    foreach ($item in @($r.Body.items)) {
        if ($item.patient.publicId -eq $PatientPublicId) { return $item }
    }
    return $null
}

function Get-Prescription {
    param([string]$PublicId)
    return (Invoke-Api $script:Admin GET "/prescriptions/$PublicId/pharmacy").Body
}

function Issue-Prescription {
    param([string]$EncounterPublicId, [string]$PrescriptionPublicId)
    $r = Invoke-Api $script:Admin POST "/encounters/$EncounterPublicId/prescriptions/$PrescriptionPublicId/issue"
    Assert-True ($r.Status -eq 200) "issue failed: $($r.Raw)"
    return $r.Body
}

function New-Prescription {
    param([string]$EncounterPublicId, $Items)
    $r = Invoke-Api $script:Admin POST "/encounters/$EncounterPublicId/prescriptions" @{ items = $Items }
    Assert-True ($r.Status -eq 201) "prescription create failed: $($r.Raw)"
    return $r.Body
}

function Find-LabResult {
    param($OrderBody, [string]$TestCode)
    foreach ($res in @($OrderBody.results)) {
        if ($res.testCode -eq $TestCode) { return $res }
    }
    return $null
}

function Find-LabItem {
    param($OrderBody, [string]$TestCode)
    foreach ($item in @($OrderBody.items)) {
        if ($item.testCode -eq $TestCode) { return $item }
    }
    return $null
}

function Get-DrugByCode {
    param([string]$Code)
    $r = Invoke-Api $script:Admin GET "/pharmacy/drugs?q=$Code&size=50"
    foreach ($item in @($r.Body.items)) {
        if ($item.code -eq $Code) { return $item }
    }
    return $null
}

function Get-ActiveBatches {
    param([string]$DrugPublicId)
    $r = Invoke-Api $script:Admin GET "/stock/batches?drug=$DrugPublicId&status=ACTIVE&size=100"
    return @($r.Body.items)
}

function Set-Setting {
    param([string]$Key, [string]$Value)
    return Invoke-Api $script:Admin PUT "/admin/settings/$Key" @{ value = $Value }
}

function Set-Bool-Setting {
    param([string]$Key, [bool]$Value)
    return Set-Setting $Key $(if ($Value) { 'true' } else { 'false' })
}

function Wait-Notification {
    param($Session, [string]$Type, [string]$EntityPublicId, [int]$Seconds = 60)
    $filterByEntity = -not [string]::IsNullOrEmpty($EntityPublicId)
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        $r = Invoke-Api $Session GET '/notifications?unreadOnly=true&size=100'
        $matches = @($r.Body.items | Where-Object {
            $_.type -eq $Type -and (-not $filterByEntity -or $_.entityPublicId -eq $EntityPublicId)
        })
        if ($matches.Count -gt 0) { return ,$matches }
        Start-Sleep -Seconds 3
    }
    return ,@()
}

function Invoke-Db {
    param([string]$Sql)
    $props = @{}
    $propsPath = Join-Path $PSScriptRoot '..\db\db.properties'
    foreach ($line in Get-Content $propsPath) {
        if ($line -match '^\s*([^#=]+)=(.*)$') { $props[$matches[1].Trim()] = $matches[2].Trim() }
    }
    $port = '3306'
    if ($props['db.url'] -match ':(\d+)/') { $port = $matches[1] }
    $mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $oldPassword = $env:MYSQL_PWD
    $env:MYSQL_PWD = $props['db.password']
    try {
        $output = & $mysql "--host=127.0.0.1" "--port=$port" "--user=$($props['db.user'])" '--skip-column-names' '--default-character-set=utf8mb4' '-e' $Sql 2>&1
        $exit = $LASTEXITCODE
    } finally {
        $env:MYSQL_PWD = $oldPassword
        $ErrorActionPreference = $previous
    }
    $lines = @($output | Where-Object { $_ -notmatch '^mysql: \[Warning\]' -and $_ -notmatch '^mysql: Switching' })
    if ($exit -ne 0) { throw "mysql failed ($exit): $($lines -join ' ')" }
    return $lines
}

function Set-AdmissionBillingCleared {
    param([string]$PublicId, [bool]$Cleared)
    $value = if ($Cleared) { 1 } else { 0 }
    [void](Invoke-Db "UPDATE smart_hospital.admissions SET billing_cleared = $value WHERE public_id = '$PublicId';")
}

# Restores the ADT board to a clean slate. Repeated regression runs leave beds
# OCCUPIED/CLEANING and admissions ADMITTED, which starves later runs of free
# beds. This is QA-only reset SQL; production never runs it.
function Reset-AdtState {
    $sql = @'
UPDATE smart_hospital.bed_assignments
   SET ended_at = NOW(6), end_reason = 'QA_RESET', active_flag = NULL
 WHERE active_flag = 1;
UPDATE smart_hospital.admissions
   SET status = 'DISCHARGED', discharge_type = 'OTHER',
       discharged_at = COALESCE(discharged_at, NOW(6)),
       version = version + 1
 WHERE status IN ('REQUESTED', 'APPROVED', 'ADMITTED', 'TRANSFERRED', 'DISCHARGE_PENDING');
UPDATE smart_hospital.beds
   SET status = 'AVAILABLE', version = version + 1
 WHERE status <> 'AVAILABLE';
'@
    [void](Invoke-Db $sql)
}

# ---------------------------------------------------------------- setup

$script:RunId = (Get-Date -Format 'MMddHHmmss')

Write-Host "`n=== Phase 4 functional regression (run $script:RunId) ===" -ForegroundColor Cyan

Section 'Setup'
Check 'SETUP login as admin' {
    $script:Admin = New-Session 'admin' 'Admin@2026Secure'
    Assert-True ([bool]$script:Admin.Csrf) 'csrfToken missing'
    $script:Admin.User.username
}
Check 'SETUP login as second admin session' {
    $script:Admin2 = New-Session 'admin' 'Admin@2026Secure'
    Assert-True ([bool]$script:Admin2.Csrf) 'csrfToken missing'
    'ok'
}
Check 'SETUP create reception user (no ADT:APPROVE)' {
    $name = "qa.rec.$script:RunId"
    $r = Invoke-Api $script:Admin POST '/admin/users' @{
        username     = $name
        displayNameAr = 'QA Reception'
        password     = 'QaPass2026x!!'
        roleCodes    = @('RECEPTION')
    }
    Assert-True ($r.Status -eq 201) "create user failed: $($r.Raw)"
    $script:Rec = New-ReadySession $name 'QaPass2026x!!' 'QaPass2026y!!'
    $script:Rec.User.username
}
Check 'SETUP create nurse user (receives EWS notifications)' {
    $name = "qa.nurse.$script:RunId"
    $r = Invoke-Api $script:Admin POST '/admin/users' @{
        username     = $name
        displayNameAr = 'QA Nurse'
        password     = 'QaPass2026x!!'
        roleCodes    = @('NURSE')
    }
    Assert-True ($r.Status -eq 201) "create user failed: $($r.Raw)"
    $script:Nurse = New-ReadySession $name 'QaPass2026x!!' 'QaPass2026y!!'
    $script:Nurse.User.username
}
Check 'SETUP health' {
    $r = Invoke-Api $script:Admin GET '/health'
    Assert-True ($r.Body.status -eq 'UP') "health $($r.Body.status)"
    $r.Body.status
}
Check 'SETUP reset ADT bed board from earlier runs' {
    Reset-AdtState
    $board = Get-Board
    $available = 0
    foreach ($w in @($board.wards)) {
        foreach ($bed in @($w.beds)) {
            if ($bed.status -eq 'AVAILABLE') { $available++ }
        }
    }
    Assert-True ($available -ge 2) "only $available beds available"
    "$available beds available"
}

# ================================================================ ER

Section 'ER - TC-051..TC-056'

$script:ErPatient = New-Patient 'ER'
$script:ErVisit = $null
$script:ErEncounter = $null

Check 'TC-051 minimal ER arrival creates visit and encounter' {
    $r = Invoke-Api $script:Admin POST '/er/visits' @{ patientPublicId = $script:ErPatient.publicId }
    Assert-Code $r 201
    Assert-True ($r.Body.status -eq 'ARRIVED') "status $($r.Body.status)"
    Assert-True ([bool]$r.Body.encounterPublicId) 'encounterPublicId missing'
    $script:ErVisit = $r.Body
    $script:ErEncounter = $r.Body.encounterPublicId
    $r.Body.publicId
}
Check 'TC-051 duplicate silent ER visit is rejected' {
    $r = Invoke-Api $script:Admin POST '/er/visits' @{ patientPublicId = $script:ErPatient.publicId } -AllowError
    Assert-Code $r 409 'ER_VISIT_EXISTS'
    'rejected'
}
Check 'TC-052 triage levels are seeded from the template' {
    $r = Invoke-Api $script:Admin GET '/er/triage-levels'
    Assert-True (@($r.Body).Count -ge 3) "levels $($r.Body.Count)"
    "$(@($r.Body).Count) levels"
}
Check 'TC-052 unknown triage level is rejected' {
    $r = Invoke-Api $script:Admin POST "/er/visits/$($script:ErVisit.publicId)/triage" @{
        triageLevelCode = 'PURPLE'
        version         = $script:ErVisit.version
    } -AllowError
    Assert-Code $r 404 'TRIAGE_LEVEL_NOT_FOUND'
    'rejected'
}
Check 'TC-052 triage is saved and reflected in the visit' {
    $r = Invoke-Api $script:Admin POST "/er/visits/$($script:ErVisit.publicId)/triage" @{
        triageLevelCode = 'YELLOW'
        chiefComplaint  = 'Chest pain'
        version         = $script:ErVisit.version
    }
    Assert-Code $r 200
    Assert-True ($r.Body.status -eq 'TRIAGED') "status $($r.Body.status)"
    Assert-True ($r.Body.triageLevel.code -eq 'YELLOW') "level $($r.Body.triageLevel.code)"
    $script:ErVisit = $r.Body
    $r.Body.triageLevel.code
}
Check 'TC-053 wait time is computed and never negative' {
    Assert-True ($null -ne $script:ErVisit.waitMinutes) 'waitMinutes missing'
    Assert-True ([double]$script:ErVisit.waitMinutes -ge 0) "waitMinutes $($script:ErVisit.waitMinutes)"
    Assert-True ($null -eq $script:ErVisit.assessmentMinutes) 'assessmentMinutes should be null before assessment'
    "$($script:ErVisit.waitMinutes) min"
}
Check 'TC-053 assessment time starts from the arrival timestamp' {
    $r = Invoke-Api $script:Admin POST "/er/visits/$($script:ErVisit.publicId)/assess" @{ version = $script:ErVisit.version }
    Assert-Code $r 200
    Assert-True ($r.Body.status -eq 'IN_TREATMENT') "status $($r.Body.status)"
    Assert-True ($null -ne $r.Body.assessmentMinutes) 'assessmentMinutes missing'
    Assert-True ([double]$r.Body.assessmentMinutes -ge 0) "assessmentMinutes $($r.Body.assessmentMinutes)"
    $script:ErVisit = $r.Body
    "$($r.Body.assessmentMinutes) min"
}
Check 'TC-054 board shows the live state and consistent counts' {
    $r = Invoke-Api $script:Admin GET '/er/board'
    $waiting = @($r.Body.waiting)
    $treatment = @($r.Body.inTreatment)
    Assert-True ($null -ne $r.Body.generatedAt) 'generatedAt missing'
    Assert-True (@($waiting | Where-Object { $_.publicId -eq $script:ErVisit.publicId }).Count -eq 0) 'visit still in waiting'
    Assert-True (@($treatment | Where-Object { $_.publicId -eq $script:ErVisit.publicId }).Count -eq 1) 'visit missing from inTreatment'
    Assert-True ($r.Body.counts.waiting -eq $waiting.Count) "waiting count $($r.Body.counts.waiting) vs $($waiting.Count)"
    Assert-True ($r.Body.counts.inTreatment -eq $treatment.Count) "inTreatment count $($r.Body.counts.inTreatment) vs $($treatment.Count)"
    "waiting=$($waiting.Count) treatment=$($treatment.Count)"
}
Check 'TC-055 invalid disposition destination is rejected' {
    $r = Invoke-Api $script:Admin POST "/er/visits/$($script:ErVisit.publicId)/disposition" @{
        disposition = 'NOWHERE'
        version     = $script:ErVisit.version
    } -AllowError
    Assert-Code $r 400 'VALIDATION_FAILED'
    'rejected'
}
Check 'TC-055 admission disposition requires a reason' {
    $r = Invoke-Api $script:Admin POST "/er/visits/$($script:ErVisit.publicId)/disposition" @{
        disposition = 'ADMIT'
        version     = $script:ErVisit.version
    } -AllowError
    Assert-Code $r 400 'VALIDATION_FAILED'
    'rejected'
}
Check 'TC-055 ER admission creates a linked request without losing the encounter' {
    $r = Invoke-Api $script:Admin POST "/er/visits/$($script:ErVisit.publicId)/disposition" @{
        disposition = 'ADMIT'
        reason      = 'Needs inpatient monitoring'
        version     = $script:ErVisit.version
    }
    Assert-Code $r 200
    Assert-True ($r.Body.status -eq 'DISPOSED') "status $($r.Body.status)"
    Assert-True ($r.Body.disposition -eq 'ADMIT') "disposition $($r.Body.disposition)"
    Assert-True ($r.Body.encounterPublicId -eq $script:ErEncounter) 'encounter changed'
    $script:ErVisit = $r.Body
    $erAdmission = Find-AdmissionByPatient $script:ErPatient.publicId 'REQUESTED'
    Assert-True ($null -ne $erAdmission) 'admission request not found'
    Assert-True ($erAdmission.encounterPublicId -eq $script:ErEncounter) 'admission encounter mismatch'
    Assert-True ($erAdmission.reason -eq 'Needs inpatient monitoring') "reason $($erAdmission.reason)"
    $rej = Invoke-Api $script:Admin POST "/admissions/$($erAdmission.publicId)/reject" @{
        version = $erAdmission.version
        reason  = 'QA closed the duplicate ER request'
    }
    Assert-Code $rej 200
    $erAdmission.publicId
}
Check 'TC-056 LWBS requires a reason' {
    $script:ErLwbsPatient = New-Patient 'ER-LWBS'
    $v = Invoke-Api $script:Admin POST '/er/visits' @{ patientPublicId = $script:ErLwbsPatient.publicId }
    Assert-Code $v 201
    $script:ErLwbsVisit = $v.Body
    $r = Invoke-Api $script:Admin POST "/er/visits/$($script:ErLwbsVisit.publicId)/disposition" @{
        disposition = 'LWBS'
        version     = $script:ErLwbsVisit.version
    } -AllowError
    Assert-Code $r 400 'VALIDATION_FAILED'
    'rejected'
}
Check 'TC-056 LWBS after assessment is rejected' {
    $a = Invoke-Api $script:Admin POST "/er/visits/$($script:ErLwbsVisit.publicId)/assess" @{ version = $script:ErLwbsVisit.version }
    Assert-Code $a 200
    $script:ErLwbsVisit = $a.Body
    $r = Invoke-Api $script:Admin POST "/er/visits/$($script:ErLwbsVisit.publicId)/disposition" @{
        disposition = 'LWBS'
        reason      = 'Left before triage'
        version     = $script:ErLwbsVisit.version
    } -AllowError
    Assert-Code $r 409 'LWBS_AFTER_ASSESSMENT'
    'rejected'
}
Check 'TC-056 LWBS is recorded and excluded from treatment indicators' {
    $script:ErLwbs2Patient = New-Patient 'ER-LWBS2'
    $v = Invoke-Api $script:Admin POST '/er/visits' @{ patientPublicId = $script:ErLwbs2Patient.publicId }
    Assert-Code $v 201
    $r = Invoke-Api $script:Admin POST "/er/visits/$($v.Body.publicId)/disposition" @{
        disposition = 'LWBS'
        reason      = 'Left without being seen'
        version     = $v.Body.version
    }
    Assert-Code $r 200
    Assert-True ($r.Body.status -eq 'LWBS') "status $($r.Body.status)"
    Assert-True ([bool]$r.Body.lwbsReason) 'lwbsReason missing'
    $enc = Invoke-Api $script:Admin GET "/encounters/$($r.Body.encounterPublicId)"
    Assert-True ($enc.Body.encounter.status -eq 'CANCELLED') "encounter status $($enc.Body.encounter.status)"
    $board = Invoke-Api $script:Admin GET '/er/board'
    Assert-True (@($board.Body.waiting | Where-Object { $_.publicId -eq $r.Body.publicId }).Count -eq 0) 'LWBS still waiting'
    Assert-True (@($board.Body.inTreatment | Where-Object { $_.publicId -eq $r.Body.publicId }).Count -eq 0) 'LWBS still in treatment'
    Assert-True (@($board.Body.recent | Where-Object { $_.publicId -eq $r.Body.publicId }).Count -eq 1) 'LWBS missing from recent'
    'recorded'
}

# ================================================================ ADT

Section 'ADT - TC-057..TC-065'

$script:AdtPatient = New-Patient 'ADT'
$script:AdtEncounter = New-EncounterId $script:AdtPatient.publicId 'INPATIENT'
$script:AdtAdmission = $null
$script:AdtBed = $null
$script:AdtBed2 = $null

Check 'TC-057 admission request requires an encounter' {
    $r = Invoke-Api $script:Admin POST '/admissions' @{
        patientPublicId = $script:AdtPatient.publicId
        reason          = 'Observation'
    } -AllowError
    Assert-Code $r 400 'VALIDATION_FAILED'
    'rejected'
}
Check 'TC-057 admission request requires a reason' {
    $r = Invoke-Api $script:Admin POST '/admissions' @{
        patientPublicId   = $script:AdtPatient.publicId
        encounterPublicId = $script:AdtEncounter
    } -AllowError
    Assert-Code $r 400 'VALIDATION_FAILED'
    'rejected'
}
Check 'TC-057 admission request is created as REQUESTED and linked' {
    $r = Invoke-Api $script:Admin POST '/admissions' @{
        patientPublicId   = $script:AdtPatient.publicId
        encounterPublicId = $script:AdtEncounter
        reason            = 'Post-operative observation'
    }
    Assert-Code $r 201
    Assert-True ($r.Body.admission.status -eq 'REQUESTED') "status $($r.Body.admission.status)"
    Assert-True ($r.Body.admission.encounterPublicId -eq $script:AdtEncounter) 'encounter mismatch'
    $script:AdtAdmission = $r.Body.admission
    $r.Body.admission.publicId
}
Check 'TC-058 an unauthorized user cannot approve an admission' {
    $r = Invoke-Api $script:Rec POST "/admissions/$($script:AdtAdmission.publicId)/approve" @{
        version = $script:AdtAdmission.version
    } -AllowError
    Assert-Code $r 403
    'forbidden'
}
Check 'TC-058 authorized approval records the approver and time' {
    $r = Invoke-Api $script:Admin POST "/admissions/$($script:AdtAdmission.publicId)/approve" @{
        version = $script:AdtAdmission.version
    }
    Assert-Code $r 200
    Assert-True ($r.Body.admission.status -eq 'APPROVED') "status $($r.Body.admission.status)"
    Assert-True ([bool]$r.Body.admission.approvedAt) 'approvedAt missing'
    Assert-True ([bool]$r.Body.admission.approvedBy.nameAr) 'approvedBy missing'
    $script:AdtAdmission = $r.Body.admission
    $r.Body.admission.approvedBy.nameAr
}
Check 'TC-059 bed board exposes all five statuses and matching counts' {
    $board = Get-Board
    $all = @()
    foreach ($w in @($board.wards)) { $all += @($w.beds) }
    Assert-True ($board.total -eq $all.Count) "total $($board.total) vs $($all.Count)"
    foreach ($status in @('AVAILABLE', 'RESERVED', 'OCCUPIED', 'CLEANING', 'MAINTENANCE')) {
        $expected = @($all | Where-Object { $_.status -eq $status }).Count
        $actual = $board.counts.$($status.ToLower())
        Assert-True ($actual -eq $expected) "count $status $actual vs $expected"
    }
    "$($board.total) beds"
}
Check 'TC-061 transfer requires a reason' {
    $script:AdtBed = Find-AvailableBed (Get-Board) 'WARD-MED-A'
    Assert-True ($null -ne $script:AdtBed) 'no available bed'
    $script:AdtAdmission = Assign-Bed $script:AdtAdmission $script:AdtBed.publicId
    $r = Invoke-Api $script:Admin POST "/admissions/$($script:AdtAdmission.publicId)/transfer" @{
        toBedPublicId = $script:AdtBed.publicId
        version       = $script:AdtAdmission.version
    } -AllowError
    Assert-Code $r 400 'VALIDATION_FAILED'
    'rejected'
}
Check 'TC-061 transfer ends the old bed and starts the new one' {
    $board = Get-Board
    $script:AdtBed2 = Find-AvailableBed $board 'WARD-MED-A'
    Assert-True ($null -ne $script:AdtBed2) 'no second available bed'
    $r = Invoke-Api $script:Admin POST "/admissions/$($script:AdtAdmission.publicId)/transfer" @{
        toBedPublicId = $script:AdtBed2.publicId
        reason        = 'Closer to nursing station'
        version       = $script:AdtAdmission.version
    }
    Assert-Code $r 200
    Assert-True ($r.Body.admission.status -eq 'ADMITTED') "status $($r.Body.admission.status)"
    Assert-True ($r.Body.admission.bed.publicId -eq $script:AdtBed2.publicId) 'new bed not active'
    $board2 = Get-Board
    $oldBed = Find-BedById $board2 $script:AdtBed.publicId
    $newBed = Find-BedById $board2 $script:AdtBed2.publicId
    Assert-True ($oldBed.status -eq 'CLEANING') "old bed $($oldBed.status)"
    Assert-True ($newBed.status -eq 'OCCUPIED') "new bed $($newBed.status)"
    Assert-True (@($r.Body.bedHistory).Count -ge 2) 'bed history incomplete'
    $script:AdtAdmission = $r.Body.admission
    $r.Body.admission.bed.code
}
Check 'TC-062 discharge sets DISCHARGED and frees the bed to CLEANING' {
    $r = Invoke-Api $script:Admin POST "/admissions/$($script:AdtAdmission.publicId)/discharge" @{
        dischargeType = 'HOME'
        notes         = 'Recovered'
        version       = $script:AdtAdmission.version
    }
    Assert-Code $r 200
    Assert-True ($r.Body.admission.status -eq 'DISCHARGED') "status $($r.Body.admission.status)"
    Assert-True ($null -ne $r.Body.admission.dischargedAt) 'dischargedAt missing'
    $script:AdtAdmission = $r.Body.admission
    $bed = Find-BedById (Get-Board) $script:AdtBed2.publicId
    Assert-True ($bed.status -eq 'CLEANING') "bed $($bed.status)"
    $bed.status
}
Check 'TC-062 a bed in CLEANING cannot be assigned' {
    $p = New-Patient 'ADT-BLOCK'
    $enc = New-EncounterId $p.publicId 'INPATIENT'
    $adm = New-Admission $p.publicId $enc 'Needs a clean bed'
    $adm = Approve-Admission $adm
    $r = Invoke-Api $script:Admin POST "/admissions/$($adm.publicId)/assign-bed" @{
        bedPublicId = $script:AdtBed2.publicId
        version     = $adm.version
    } -AllowError
    Assert-Code $r 409 'BED_NOT_AVAILABLE'
    'rejected'
}
Check 'TC-064 length of stay is computed without negative values' {
    $sum = Invoke-Api $script:Admin GET "/admissions/$($script:AdtAdmission.publicId)/summary"
    Assert-True ($null -ne $sum.Body.los) 'los missing'
    Assert-True ([double]$sum.Body.los.hours -ge 0) "hours $($sum.Body.los.hours)"
    Assert-True ([double]$sum.Body.los.days -ge 0) "days $($sum.Body.los.days)"
    Assert-True ($sum.Body.los.active -eq $false) 'los should be inactive after discharge'
    "hours=$($sum.Body.los.hours)"
}
Check 'TC-063 discharge summary requires diagnoses and instructions' {
    $r = Invoke-Api $script:Admin POST "/admissions/$($script:AdtAdmission.publicId)/summary" @{
        diagnosesText = 'Post-operative recovery'
    } -AllowError
    Assert-Code $r 400 'VALIDATION_FAILED'
    'rejected'
}
Check 'TC-063 discharge summary is stored and readable' {
    $r = Invoke-Api $script:Admin POST "/admissions/$($script:AdtAdmission.publicId)/summary" @{
        diagnosesText    = 'Post-operative recovery'
        medicationsText  = 'Paracetamol 500 mg PRN'
        instructionsText = 'Wound care and follow-up in 7 days'
        followUpText     = 'Surgical clinic'
    }
    Assert-Code $r 200
    Assert-True ($null -ne $r.Body.stored) 'stored summary missing'
    Assert-True ($r.Body.stored.diagnosesText -eq 'Post-operative recovery') "stored $($r.Body.stored.diagnosesText)"
    Assert-True (@($r.Body.medications).Count -ge 0) 'medications missing'
    'stored'
}
Check 'TC-062 housekeeping returns the discharged bed to AVAILABLE' {
    $bed = Find-BedById (Get-Board) $script:AdtBed2.publicId
    $r = Invoke-Api $script:Admin POST "/beds/$($script:AdtBed2.publicId)/status" @{
        status  = 'AVAILABLE'
        version = $bed.version
        reason  = 'Cleaned'
    }
    Assert-Code $r 200
    Assert-True ($r.Body.status -eq 'AVAILABLE') "status $($r.Body.status)"
    Release-Bed $script:AdtBed.publicId
    $r.Body.status
}
Check 'TC-065 billing clearance blocks discharge when enabled' {
    $p = New-Patient 'ADT-BILL'
    $enc = New-EncounterId $p.publicId 'INPATIENT'
    $adm = New-Admission $p.publicId $enc 'Billing clearance test'
    $adm = Approve-Admission $adm
    $bed = Find-AvailableBed (Get-Board) 'WARD-MED-A'
    Assert-True ($null -ne $bed) 'no available bed'
    $adm = Assign-Bed $adm $bed.publicId
    $previous = $null
    try {
        $settings = Invoke-Api $script:Admin GET '/admin/settings'
        foreach ($s in @($settings.Body)) {
            if ($s.key -eq 'adt.discharge.require_billing_clear') { $previous = $s.value }
        }
        $set = Set-Bool-Setting 'adt.discharge.require_billing_clear' $true
        Assert-Code $set 200
        Set-AdmissionBillingCleared $adm.publicId $false
        $r = Invoke-Api $script:Admin POST "/admissions/$($adm.publicId)/discharge" @{
            dischargeType = 'HOME'
            version       = $adm.version
        } -AllowError
        Assert-Code $r 409 'BILLING_NOT_CLEARED'
    } finally {
        $restore = Set-Bool-Setting 'adt.discharge.require_billing_clear' $false
        Assert-Code $restore 200
    }
    Set-AdmissionBillingCleared $adm.publicId $true
    $done = Invoke-Api $script:Admin POST "/admissions/$($adm.publicId)/discharge" @{
        dischargeType = 'HOME'
        version       = $adm.version
    }
    Assert-Code $done 200
    Release-Bed $bed.publicId
    'blocked then allowed'
}

# ================================================================ NUR

Section 'NUR - TC-066..TC-071'

$script:NurPatient = New-Patient 'NUR'
$script:NurEncounter = New-EncounterId $script:NurPatient.publicId 'INPATIENT'
$script:NurAdmission = $null
$script:NurBed = $null

Check 'TC-066 ward patient list only shows the ward inpatients' {
    $adm = New-Admission $script:NurPatient.publicId $script:NurEncounter 'Nursing observation'
    $adm = Approve-Admission $adm
    $script:NurBed = Find-AvailableBed (Get-Board) 'WARD-MED-A'
    Assert-True ($null -ne $script:NurBed) 'no available bed'
    $script:NurAdmission = Assign-Bed $adm $script:NurBed.publicId
    $board = Get-Board
    $ward = $null
    foreach ($w in @($board.wards)) {
        foreach ($bed in @($w.beds)) {
            if ($bed.publicId -eq $script:NurBed.publicId) { $ward = $w }
        }
    }
    Assert-True ($null -ne $ward) 'ward not found'
    $r = Invoke-Api $script:Admin GET "/wards/$($ward.publicId)/patients"
    $row = @($r.Body.patients | Where-Object { $_.patientPublicId -eq $script:NurPatient.publicId })[0]
    Assert-True ($null -ne $row) 'patient missing from ward'
    Assert-True ($row.bedPublicId -eq $script:NurBed.publicId) 'wrong bed'
    foreach ($item in @($r.Body.patients)) {
        Assert-True ($item.bedPublicId -ne $null) 'patient without bed in ward list'
    }
    $r.Body.count
}
Check 'TC-067 vitals are stored in order and the latest is exposed' {
    $first = Invoke-Api $script:Admin POST "/nursing/admissions/$($script:NurAdmission.publicId)/vitals" @{
        temperatureC   = 37.2
        heartRate      = 88
        respiratoryRate = 18
        systolic       = 120
        diastolic      = 80
        spo2           = 98
    }
    Assert-Code $first 201
    $second = Invoke-Api $script:Admin POST "/nursing/admissions/$($script:NurAdmission.publicId)/vitals" @{
        temperatureC   = 39.2
        heartRate      = 132
        respiratoryRate = 30
        systolic       = 185
        diastolic      = 100
        spo2           = 88
    }
    Assert-Code $second 201
    $over = Invoke-Api $script:Admin GET "/nursing/admissions/$($script:NurAdmission.publicId)/overview"
    $vitals = @($over.Body.vitals)
    Assert-True ($vitals.Count -ge 2) "vitals $($vitals.Count)"
    Assert-True ($over.Body.admission.publicId -eq $script:NurAdmission.publicId) 'admission mismatch'
    $firstAt = [datetime]$vitals[0].recordedAt
    $lastAt = [datetime]$vitals[$vitals.Count - 1].recordedAt
    Assert-True ($firstAt -ge $lastAt) 'vitals not newest-first'
    Assert-True ($null -ne $over.Body.ews) 'ews missing'
    "$($vitals.Count) records"
}
Check 'TC-071 EWS score and risk come from hospital thresholds' {
    $over = Invoke-Api $script:Admin GET "/nursing/admissions/$($script:NurAdmission.publicId)/overview"
    $ews = $over.Body.ews
    Assert-True ($null -ne $ews.score) 'score missing'
    Assert-True ($ews.riskLevel -eq 'HIGH') "risk $($ews.riskLevel)"
    Assert-True (@($ews.parameters).Count -eq 5) "parameters $(@($ews.parameters).Count)"
    $abnormal = @($ews.parameters | Where-Object { $_.abnormal }).Count
    Assert-True ($ews.score -eq $abnormal) "score $($ews.score) vs abnormal $abnormal"
    $settings = Invoke-Api $script:Admin GET '/admin/settings'
    $threshold = @($settings.Body | Where-Object { $_.key -eq 'nur.ews.thresholds' })[0]
    Assert-True ($null -ne $threshold) 'thresholds setting missing'
    "score=$($ews.score) risk=$($ews.riskLevel)"
}
Check 'TC-071 high EWS raises a critical notification to nurses' {
    $found = Wait-Notification $script:Nurse 'EWS_HIGH' $null 60
    Assert-True ($found.Count -ge 1) 'EWS_HIGH notification not delivered'
    Assert-True ($found[0].severity -eq 'CRITICAL') "severity $($found[0].severity)"
    "$($found.Count) notification(s)"
}
Check 'TC-068 task is created, assigned, started and completed' {
    $r = Invoke-Api $script:Admin POST '/nursing/tasks' @{
        taskType          = 'VITALS'
        priority          = 'URGENT'
        title             = 'QA vital signs check'
        description       = 'Regression task'
        admissionPublicId = $script:NurAdmission.publicId
        patientPublicId   = $script:NurPatient.publicId
        encounterPublicId = $script:NurEncounter
        assignedToPublicId = $script:Admin.User.publicId
    }
    Assert-Code $r 201
    $task = $r.Body
    Assert-True ($task.status -eq 'ASSIGNED') "status $($task.status)"
    $a = Invoke-Api $script:Admin POST "/nursing/tasks/$($task.publicId)/assign" @{
        assignedToPublicId = $script:Admin.User.publicId
        version            = $task.version
    }
    Assert-Code $a 200
    $task = $a.Body
    $s = Invoke-Api $script:Admin POST "/nursing/tasks/$($task.publicId)/start" @{ version = $task.version }
    Assert-Code $s 200
    Assert-True ($s.Body.status -eq 'IN_PROGRESS') "status $($s.Body.status)"
    $task = $s.Body
    $forbidden = Invoke-Api $script:Rec POST "/nursing/tasks/$($task.publicId)/complete" @{
        completionNote = 'Should be rejected'
        version        = $task.version
    } -AllowError
    Assert-Code $forbidden 403
    $c = Invoke-Api $script:Admin POST "/nursing/tasks/$($task.publicId)/complete" @{
        completionNote = 'Completed by QA'
        version        = $task.version
    }
    Assert-Code $c 200
    Assert-True ($c.Body.status -eq 'COMPLETED') "status $($c.Body.status)"
    Assert-True ([bool]$c.Body.completedAt) 'completedAt missing'
    $c.Body.status
}
Check 'TC-069 signed nursing note cannot be edited directly' {
    $r = Invoke-Api $script:Admin POST "/encounters/$($script:NurEncounter)/notes" @{
        noteType   = 'NURSING'
        subjective = 'Patient reports pain'
        objective  = 'Vitals recorded'
        assessment = 'Stable'
        plan       = 'Continue observation'
    }
    Assert-Code $r 201
    $note = $r.Body
    $s = Invoke-Api $script:Admin POST "/encounters/$($script:NurEncounter)/notes/$($note.publicId)/sign"
    Assert-Code $s 200
    Assert-True ($s.Body.status -eq 'SIGNED') "status $($s.Body.status)"
    $script:NurNote = $s.Body
    $u = Invoke-Api $script:Admin PATCH "/encounters/$($script:NurEncounter)/notes/$($note.publicId)" @{
        subjective = 'Attempted edit after signing'
    } -AllowError
    Assert-True ($u.Status -ge 400) "edit allowed with $($u.Status)"
    'blocked'
}
Check 'TC-069 amendment creates a new version and keeps the signed one' {
    $r = Invoke-Api $script:Admin POST "/encounters/$($script:NurEncounter)/notes/$($script:NurNote.publicId)/amend" @{
        reason     = 'QA correction after review'
        subjective = 'Patient reports mild pain'
        objective  = 'Vitals recorded'
        assessment = 'Stable'
        plan       = 'Continue observation'
    }
    Assert-Code $r 200
    Assert-True ($r.Body.status -eq 'AMENDED') "status $($r.Body.status)"
    Assert-True ([int]$r.Body.versionNo -ge 2) "versionNo $($r.Body.versionNo)"
    $v = Invoke-Api $script:Admin GET "/encounters/$($script:NurEncounter)/notes/$($script:NurNote.publicId)/versions"
    Assert-True (@($v.Body).Count -ge 2) "versions $(@($v.Body).Count)"
    "versionNo=$($r.Body.versionNo)"
}
Check 'TC-070 medication barcode mismatch blocks administration' {
    $amox = Get-DrugByCode 'AMOX-500'
    Assert-True ($null -ne $amox) 'AMOX-500 not found'
    $script:Amox = $amox
    $rx = New-Prescription $script:NurEncounter @(
        @{ drugPublicId = $amox.publicId; dose = '500 mg'; route = 'PO'; frequency = 'BID'; durationDays = 5; quantity = 2 }
    )
    [void](Issue-Prescription $script:NurEncounter $rx.publicId)
    $view = Get-Prescription $rx.publicId
    $item = @($view.items)[0]
    $script:NurRxItem = $item
    $missing = Invoke-Api $script:Admin POST '/medication-administrations' @{
        prescriptionItemPublicId = $item.publicId
        status                   = 'GIVEN'
        admissionPublicId        = $script:NurAdmission.publicId
    } -AllowError
    Assert-Code $missing 422 'BARCODE_MISMATCH'
    $wrong = Invoke-Api $script:Admin POST '/medication-administrations' @{
        prescriptionItemPublicId = $item.publicId
        status                   = 'GIVEN'
        admissionPublicId        = $script:NurAdmission.publicId
        barcodePatient           = 'WRONG-MRN'
        barcodeDrug              = 'AMOX-500'
        barcodeBatch             = 'AMX-2602'
    } -AllowError
    Assert-Code $wrong 422 'BARCODE_MISMATCH'
    'blocked'
}
Check 'TC-070 matching barcodes record all five rights and the batch' {
    $batches = Get-ActiveBatches $script:Amox.publicId
    Assert-True ($batches.Count -ge 1) 'no AMOX batches'
    $batch = $batches[0]
    $before = [int]$batch.quantityAvailable
    $r = Invoke-Api $script:Admin POST '/medication-administrations' @{
        prescriptionItemPublicId = $script:NurRxItem.publicId
        status                   = 'GIVEN'
        admissionPublicId        = $script:NurAdmission.publicId
        barcodePatient           = $script:NurPatient.medicalNumber
        barcodeDrug              = 'AMOX-500'
        barcodeBatch             = $batch.batchNumber
        batchPublicId            = $batch.publicId
    }
    Assert-Code $r 201
    Assert-True ($r.Body.status -eq 'GIVEN') "status $($r.Body.status)"
    Assert-True ($r.Body.verified.patient -eq $true) 'patient not verified'
    Assert-True ($r.Body.verified.drug -eq $true) 'drug not verified'
    Assert-True ($r.Body.verified.batch -eq $true) 'batch not verified'
    Assert-True ($null -ne $r.Body.stockMovement) 'stock movement missing'
    $after = [int](Get-ActiveBatches $script:Amox.publicId | Where-Object { $_.publicId -eq $batch.publicId })[0].quantityAvailable
    Assert-True ($after -eq ($before - 1)) "stock $before -> $after"
    "$($batch.batchNumber) $before->$after"
}
Check 'TC-070 refused medication is recorded without changing stock' {
    $batches = Get-ActiveBatches $script:Amox.publicId
    $batch = $batches[0]
    $before = [int]$batch.quantityAvailable
    $r = Invoke-Api $script:Admin POST '/medication-administrations' @{
        prescriptionItemPublicId = $script:NurRxItem.publicId
        status                   = 'REFUSED'
        admissionPublicId        = $script:NurAdmission.publicId
        notes                    = 'Patient refused the dose'
    }
    Assert-Code $r 201
    $after = [int](Get-ActiveBatches $script:Amox.publicId | Where-Object { $_.publicId -eq $batch.publicId })[0].quantityAvailable
    Assert-True ($after -eq $before) "stock changed $before -> $after"
    'no stock change'
}
Check 'TC-071 nursing admission is discharged and the bed released' {
    $cur = (Invoke-Api $script:Admin GET "/admissions/$($script:NurAdmission.publicId)").Body.admission
    if ($cur.status -eq 'ADMITTED' -or $cur.status -eq 'TRANSFERRED' -or $cur.status -eq 'DISCHARGE_PENDING') {
        $done = Discharge-Admission $cur 'HOME'
        Assert-True ($done.status -eq 'DISCHARGED') "status $($done.status)"
    }
    Release-Bed $script:NurBed.publicId
    'cleaned'
}

# ================================================================ LAB

Section 'LAB - TC-072..TC-083'

$script:LabPatient = New-Patient 'LAB'
$script:LabEncounter = New-EncounterId $script:LabPatient.publicId 'OUTPATIENT'
$script:LabOrder = $null
$script:LabResultPublicId = $null
$script:LabCollectAt = $null

Check 'TC-072 lab test catalog saves unit, range and TAT' {
    $code = "QAT$script:RunId"
    $r = Invoke-Api $script:Admin POST '/lab/tests' @{
        code              = $code
        nameAr            = "QA Test $script:RunId"
        nameEn            = "QA Test $script:RunId"
        specimenType      = 'BLOOD'
        unit              = 'mg/L'
        resultType        = 'NUMERIC'
        refLow            = 1.5
        refHigh           = 7.5
        criticalLow       = 0.5
        criticalHigh      = 20
        targetTatMinutes  = 45
    }
    Assert-Code $r 201
    Assert-True ($r.Body.unit -eq 'mg/L') "unit $($r.Body.unit)"
    Assert-True ([double]$r.Body.refLow -eq 1.5) "refLow $($r.Body.refLow)"
    Assert-True ([double]$r.Body.refHigh -eq 7.5) "refHigh $($r.Body.refHigh)"
    Assert-True ([int]$r.Body.targetTatMinutes -eq 45) "tat $($r.Body.targetTatMinutes)"
    Assert-True ([bool]$r.Body.specimenType) 'specimenType missing'
    $dup = Invoke-Api $script:Admin POST '/lab/tests' @{
        code   = $code
        nameAr = 'Duplicate'
    } -AllowError
    Assert-Code $dup 409 'TEST_CODE_EXISTS'
    $r.Body.code
}
Check 'TC-073 lab order links multiple items to the encounter' {
    $r = Invoke-Api $script:Admin POST '/lab/orders' @{
        encounterPublicId = $script:LabEncounter
        priority          = 'URGENT'
        clinicalNotes     = 'QA regression order'
        items             = @('GLU', 'UA')
    }
    Assert-Code $r 201
    Assert-True ($r.Body.status -eq 'ORDERED') "status $($r.Body.status)"
    Assert-True ($r.Body.encounterPublicId -eq $script:LabEncounter) 'encounter mismatch'
    Assert-True (@($r.Body.items).Count -eq 2) "items $(@($r.Body.items).Count)"
    foreach ($item in @($r.Body.items)) { Assert-True ([bool]$item.testCode) 'item without test' }
    $script:LabOrder = $r.Body
    $bad = Invoke-Api $script:Admin POST '/lab/orders' @{
        encounterPublicId = $script:LabEncounter
        items             = @('NOT-A-TEST')
    } -AllowError
    Assert-Code $bad 404 'LAB_TEST_NOT_FOUND'
    $r.Body.publicId
}
Check 'TC-073 charge capture is out of the lab order endpoint (documented)' {
    $enc = Invoke-Api $script:Admin GET "/encounters/$($script:LabEncounter)"
    Assert-True (@($enc.Body.charges).Count -eq 0) 'unexpected charge rows created by lab order'
    'no charge rows (partial: charge capture belongs to billing)'
}
Check 'TC-074 specimen is collected with a barcode and audit fields' {
    $barcode = "BC-$script:RunId"
    $r = Invoke-Api $script:Admin POST "/lab/orders/$($script:LabOrder.publicId)/collect" @{
        version      = $script:LabOrder.version
        specimenType = 'BLOOD'
        barcode      = $barcode
        notes        = 'Collected by QA'
    }
    Assert-Code $r 200
    Assert-True ($r.Body.status -eq 'COLLECTED') "status $($r.Body.status)"
    $spec = @($r.Body.specimens)[0]
    $script:LabOrder = $r.Body
    $script:LabCollectAt = $spec.collectedAt
    $script:LabBarcode = $barcode
    Assert-True ($spec.barcode -eq $barcode) "barcode $($spec.barcode)"
    Assert-True ([bool]$spec.collectedAt) 'collectedAt missing'
    Assert-True ($null -ne $spec.collectedBy) 'collectedBy missing'
    Assert-True ([bool]@($spec.events)[0].actorAr) 'collect event actor missing'
    $barcode
}
Check 'TC-074 duplicate specimen barcode is rejected' {
    $extra = Invoke-Api $script:Admin POST '/lab/orders' @{
        encounterPublicId = $script:LabEncounter
        items             = @('GLU')
    }
    Assert-Code $extra 201
    $r = Invoke-Api $script:Admin POST "/lab/orders/$($extra.Body.publicId)/collect" @{
        version = $extra.Body.version
        barcode = $script:LabBarcode
    } -AllowError
    Assert-Code $r 409 'BARCODE_EXISTS'
    $cancel = Invoke-Api $script:Admin POST "/encounters/$($script:LabEncounter)/orders/$($extra.Body.publicId)" @{} -AllowError
    # The extra order is left in ORDERED on purpose; cancellation is not part of Phase 4.
    'rejected'
}
Check 'TC-075 specimen moves COLLECTED -> RECEIVED -> IN_PROCESS' {
    $spec = @($script:LabOrder.specimens)[0]
    $rec = Invoke-Api $script:Admin POST "/lab/specimens/$($spec.publicId)/receive" @{
        version = $spec.version
        notes   = 'Received in lab'
    }
    Assert-Code $rec 200
    Assert-True ($rec.Body.status -eq 'RECEIVED') "status $($rec.Body.status)"
    $spec2 = @($rec.Body.specimens)[0]
    Assert-True ($spec2.status -eq 'RECEIVED') "specimen $($spec2.status)"
    Assert-True (@($spec2.events).Count -ge 2) "events $(@($spec2.events).Count)"
    $again = Invoke-Api $script:Admin POST "/lab/specimens/$($spec.publicId)/receive" @{
        version = $spec2.version
    } -AllowError
    Assert-Code $again 409 'INVALID_STATE'
    $proc = Invoke-Api $script:Admin POST "/lab/orders/$($script:LabOrder.publicId)/process" @{
        version = $rec.Body.version
    }
    Assert-Code $proc 200
    Assert-True ($proc.Body.status -eq 'IN_PROCESS') "status $($proc.Body.status)"
    $script:LabOrder = $proc.Body
    $script:LabSpecimen = $spec2
    'advanced'
}
Check 'TC-075 invalid reverse transitions are rejected' {
    $rej = Invoke-Api $script:Admin POST "/lab/specimens/$($script:LabSpecimen.publicId)/reject" @{
        version = $script:LabSpecimen.version
        reason  = 'Cannot reject after receipt'
    } -AllowError
    Assert-Code $rej 409 'INVALID_STATE'
    $again = Invoke-Api $script:Admin POST "/lab/orders/$($script:LabOrder.publicId)/process" @{
        version = $script:LabOrder.version
    } -AllowError
    Assert-True ($again.Status -eq 409) "process repeated with $($again.Status)"
    'rejected'
}
Check 'TC-076 numeric result flags NORMAL / HIGH / CRITICAL_HIGH' {
    $order = $script:LabOrder
    $normal = Invoke-Api $script:Admin POST "/lab/orders/$($order.publicId)/result" @{
        testCode     = 'GLU'
        valueNumeric = 95
    }
    Assert-Code $normal 200
    $res = Find-LabResult $normal.Body 'GLU'
    Assert-True ($res.flag -eq 'NORMAL') "flag $($res.flag)"
    $high = Invoke-Api $script:Admin POST "/lab/orders/$($order.publicId)/result" @{
        testCode     = 'GLU'
        valueNumeric = 150
    }
    Assert-Code $high 200
    $res = Find-LabResult $high.Body 'GLU'
    Assert-True ($res.flag -eq 'HIGH') "flag $($res.flag)"
    $crit = Invoke-Api $script:Admin POST "/lab/orders/$($order.publicId)/result" @{
        testCode     = 'GLU'
        valueNumeric = 600
    }
    Assert-Code $crit 200
    $res = Find-LabResult $crit.Body 'GLU'
    Assert-True ($res.flag -eq 'CRITICAL_HIGH') "flag $($res.flag)"
    Assert-True ($res.criticalFlag -eq $true) 'criticalFlag missing'
    $script:LabOrder = $crit.Body
    $script:GlucoseResult = $res
    $script:LabResultPublicId = $res.publicId
    $alerts = Invoke-Api $script:Admin GET '/lab/alerts?status=OPEN'
    $alert = @($alerts.Body.items | Where-Object { $_.resultPublicId -eq $res.publicId })[0]
    Assert-True ($null -ne $alert) 'critical alert not created'
    $script:LabAlert = $alert
    $res.flag
}
Check 'TC-076 text result is flagged ABNORMAL against the reference text' {
    $r = Invoke-Api $script:Admin POST "/lab/orders/$($script:LabOrder.publicId)/result" @{
        testCode  = 'UA'
        valueText = 'Positive'
    }
    Assert-Code $r 200
    $res = Find-LabResult $r.Body 'UA'
    Assert-True ($res.flag -eq 'ABNORMAL') "flag $($res.flag)"
    $script:LabOrder = $r.Body
    $script:UrineResult = $res
    $res.flag
}
Check 'TC-076 non-numeric value for a numeric test is rejected' {
    $r = Invoke-Api $script:Admin POST "/lab/orders/$($script:LabOrder.publicId)/result" @{
        testCode  = 'GLU'
        valueText = 'not a number'
    } -AllowError
    Assert-Code $r 400 'VALIDATION_FAILED'
    'rejected'
}
Check 'TC-077 authorized verification succeeds once' {
    $v = Invoke-Api $script:Admin POST "/lab/results/$($script:GlucoseResult.publicId)/verify" @{
        version = $script:GlucoseResult.version
    }
    Assert-Code $v 200
    Assert-True ($v.Body.status -eq 'VERIFIED') "status $($v.Body.status)"
    Assert-True ([bool]$v.Body.verifiedAt) 'verifiedAt missing'
    $script:GlucoseResult = $v.Body
    $double = Invoke-Api $script:Admin POST "/lab/results/$($script:GlucoseResult.publicId)/verify" @{
        version = $script:GlucoseResult.version
    } -AllowError
    Assert-True ($double.Status -eq 409) "double verify $($double.Status)"
    $u = Invoke-Api $script:Admin POST "/lab/results/$($script:UrineResult.publicId)/verify" @{
        version = $script:UrineResult.version
    }
    Assert-Code $u 200
    $script:UrineResult = $u.Body
    $order = Invoke-Api $script:Admin GET "/lab/orders/$($script:LabOrder.publicId)"
    Assert-True ($order.Body.status -eq 'VERIFIED') "order $($order.Body.status)"
    'verified'
}
Skip 'TC-077 self-verification policy' 'No self-verify policy exists in v1; double verification rejection is proven instead.'
Check 'TC-078 amendment requires a reason' {
    $r = Invoke-Api $script:Admin POST "/lab/results/$($script:GlucoseResult.publicId)/amend" @{
        version      = $script:GlucoseResult.version
        valueNumeric = 610
    } -AllowError
    Assert-Code $r 400 'VALIDATION_FAILED'
    'rejected'
}
Check 'TC-078 amendment creates a corrected version and keeps the original' {
    $r = Invoke-Api $script:Admin POST "/lab/results/$($script:GlucoseResult.publicId)/amend" @{
        version      = $script:GlucoseResult.version
        reason       = 'Recalibration correction'
        valueNumeric = 610
        comment      = 'Corrected after recalibration'
    }
    Assert-Code $r 200
    Assert-True ($r.Body.status -eq 'CORRECTED') "status $($r.Body.status)"
    Assert-True ([double]$r.Body.valueNumeric -eq 610) "value $($r.Body.valueNumeric)"
    $script:GlucoseResult = $r.Body
    $report = Invoke-Api $script:Admin GET "/lab/orders/$($script:LabOrder.publicId)/report"
    $corrected = @($report.Body.results | Where-Object { $_.publicId -eq $script:GlucoseResult.publicId })[0]
    Assert-True ($corrected.status -eq 'CORRECTED') "report status $($corrected.status)"
    'corrected'
}
Check 'TC-079 released result notifies the ordering provider exactly once' {
    $g = Invoke-Api $script:Admin POST "/lab/results/$($script:GlucoseResult.publicId)/verify" @{
        version = $script:GlucoseResult.version
    }
    Assert-Code $g 200
    $script:GlucoseResult = $g.Body
    $rel = Invoke-Api $script:Admin POST "/lab/results/$($script:GlucoseResult.publicId)/release" @{
        version = $script:GlucoseResult.version
    }
    Assert-Code $rel 200
    Assert-True ($rel.Body.status -eq 'RELEASED') "status $($rel.Body.status)"
    $script:GlucoseResult = $rel.Body
    $u = Invoke-Api $script:Admin POST "/lab/results/$($script:UrineResult.publicId)/release" @{
        version = $script:UrineResult.version
    }
    Assert-Code $u 200
    $script:UrineResult = $u.Body
    $order = Invoke-Api $script:Admin GET "/lab/orders/$($script:LabOrder.publicId)"
    Assert-True ($order.Body.status -eq 'RELEASED') "order $($order.Body.status)"
    $found = Wait-Notification $script:Admin 'LAB_RESULT_RELEASED' $script:GlucoseResult.publicId 60
    Assert-True ($found.Count -eq 1) "notifications $($found.Count)"
    Assert-True ($found[0].severity -eq 'INFO') "severity $($found[0].severity)"
    $script:ReleasedNotification = $found[0]
    'delivered once'
}
Check 'TC-080 direct edit after release is rejected' {
    $item = Find-LabItem (Invoke-Api $script:Admin GET "/lab/orders/$($script:LabOrder.publicId)").Body 'GLU'
    Assert-True ($null -ne $item) 'GLU item missing'
    $r = Invoke-Api $script:Admin POST "/lab/orders/$($script:LabOrder.publicId)/result" @{
        itemPublicId = $item.publicId
        valueNumeric = 1
    } -AllowError
    Assert-Code $r 409 'RESULT_VERIFIED'
    'rejected'
}
Check 'TC-081 Arabic report exposes header, units and reference ranges' {
    $r = Invoke-Api $script:Admin GET "/lab/orders/$($script:LabOrder.publicId)/report"
    Assert-Code $r 200
    $nameAr = [string]$r.Body.header.nameAr
    Assert-True ($nameAr.Length -gt 0) 'nameAr empty'
    $arabic = $false
    foreach ($ch in $nameAr.ToCharArray()) {
        $code = [int]$ch
        if ($code -ge 0x0600 -and $code -le 0x06FF) { $arabic = $true; break }
    }
    Assert-True $arabic 'header.nameAr is not Arabic'
    Assert-True ([bool]$r.Body.header.reportTitleAr) 'reportTitleAr missing'
    Assert-True ([bool]$r.Body.printedAt) 'printedAt missing'
    $glu = @($r.Body.results | Where-Object { $_.testCode -eq 'GLU' })[0]
    Assert-True ([bool]$glu.unit) 'unit missing'
    Assert-True ($null -ne $glu.refLow -and $null -ne $glu.refHigh) 'reference range missing'
    Assert-True ($null -ne $glu.valueNumeric) 'result value missing'
    'arabic header present'
}
Check 'TC-082 TAT is non-negative and the target is exposed' {
    $order = Invoke-Api $script:Admin GET "/lab/orders/$($script:LabOrder.publicId)"
    $item = Find-LabItem $order.Body 'GLU'
    Assert-True ($null -ne $item.targetTatMinutes) 'targetTatMinutes missing'
    $collected = [datetime]$script:LabCollectAt
    $verified = [datetime]$script:GlucoseResult.verifiedAt
    $tat = ($verified - $collected).TotalMinutes
    Assert-True ($tat -ge 0) "TAT $tat"
    "TAT=$([math]::Round($tat,2)) min target=$($item.targetTatMinutes)"
}
Skip 'TC-082 TAT dashboard for overdue orders' 'No dedicated TAT dashboard endpoint in Phase 4; TAT is computed from collection and verification timestamps (dashboard is Phase 6 analytics).'
Skip 'TC-083 analyzer adapter isolation' 'No analyzer adapter exists in v1; FR-LAB-012/SF-06 are classified Future in 11-scope-classification.md.'
Check 'TC-076 critical alert can be acknowledged' {
    $r = Invoke-Api $script:Admin POST "/lab/alerts/$($script:LabAlert.publicId)/acknowledge" @{
        version = $script:LabAlert.version
        notes   = 'Reviewed by QA'
    }
    Assert-Code $r 200
    Assert-True ($r.Body.status -eq 'ACKNOWLEDGED') "status $($r.Body.status)"
    'acknowledged'
}

# ================================================================ PHR

Section 'PHR - TC-090..TC-101'

$script:PhrPatient = New-Patient 'PHR'
$script:PhrEncounter = New-EncounterId $script:PhrPatient.publicId 'OUTPATIENT'

Check 'TC-090 drug catalog saves all fields and rejects a duplicate code' {
    $code = "QAD$script:RunId"
    $r = Invoke-Api $script:Admin POST '/pharmacy/drugs' @{
        code         = $code
        nameAr       = "QA Drug $script:RunId"
        nameEn       = "QA Drug $script:RunId"
        form         = 'TABLET'
        strength     = '250 mg'
        category     = 'ANALGESIC'
        reorderLevel = 5
        unitPrice    = 1.25
    }
    Assert-Code $r 201
    Assert-True ($r.Body.code -eq $code) "code $($r.Body.code)"
    Assert-True ($r.Body.form -eq 'TABLET') "form $($r.Body.form)"
    Assert-True ($r.Body.category -eq 'ANALGESIC') "category $($r.Body.category)"
    Assert-True ([int]$r.Body.reorderLevel -eq 5) "reorder $($r.Body.reorderLevel)"
    Assert-True ([double]$r.Body.unitPrice -eq 1.25) "price $($r.Body.unitPrice)"
    $dup = Invoke-Api $script:Admin POST '/pharmacy/drugs' @{
        code   = $code
        nameAr = 'Duplicate'
        form   = 'TABLET'
    } -AllowError
    Assert-Code $dup 409 'DRUG_CODE_EXISTS'
    $script:QaDrug = $r.Body
    $r.Body.code
}
Check 'TC-091 prescription items require drug, dose, route and frequency' {
    $amox = Get-DrugByCode 'AMOX-500'
    $para = Get-DrugByCode 'PARAC-500'
    Assert-True ($null -ne $amox -and $null -ne $para) 'seed drugs missing'
    $bad = Invoke-Api $script:Admin POST "/encounters/$($script:PhrEncounter)/prescriptions" @{
        items = @(@{ drugPublicId = $amox.publicId; route = 'PO'; frequency = 'BID' })
    } -AllowError
    Assert-True ($bad.Status -ge 400) "missing dose accepted with $($bad.Status)"
    $rx = New-Prescription $script:PhrEncounter @(
        @{ drugPublicId = $amox.publicId; dose = '500 mg'; route = 'PO'; frequency = 'TID'; durationDays = 7; quantity = 6 },
        @{ drugPublicId = $para.publicId; dose = '500 mg'; route = 'PO'; frequency = 'PRN'; durationDays = 3; quantity = 4 }
    )
    Assert-True (@($rx.items).Count -eq 2) "items $(@($rx.items).Count)"
    foreach ($item in @($rx.items)) {
        Assert-True ([bool]$item.drugCode) 'drug missing'
        Assert-True ([bool]$item.drugAr) 'drug name missing'
        Assert-True ([bool]$item.dose) 'dose missing'
        Assert-True ([bool]$item.route) 'route missing'
        Assert-True ([bool]$item.frequency) 'frequency missing'
        Assert-True ([int]$item.durationDays -gt 0) 'duration missing'
    }
    $view = Invoke-Api $script:Admin GET "/prescriptions/$($rx.publicId)"
    Assert-Code $view 200
    Assert-True ($view.Body.patient.publicId -eq $script:PhrPatient.publicId) 'prescription not linked to patient'
    $script:MainRx = $rx
    $rx.publicId
}
Check 'TC-092 review accepts an issued prescription' {
    [void](Issue-Prescription $script:PhrEncounter $script:MainRx.publicId)
    $view = Get-Prescription $script:MainRx.publicId
    Assert-True ($view.status -eq 'ISSUED') "status $($view.status)"
    $r = Invoke-Api $script:Admin POST "/prescriptions/$($script:MainRx.publicId)/review" @{
        version = $view.version
        notes   = 'Reviewed by pharmacy'
    }
    Assert-Code $r 200
    Assert-True ($r.Body.status -eq 'REVIEWED') "status $($r.Body.status)"
    Assert-True ($null -ne $r.Body.interactions) 'interactions missing'
    $script:MainRxView = $r.Body
    $r.Body.status
}
Check 'TC-092 rejection requires a reason and blocks dispensing' {
    $para = Get-DrugByCode 'PARAC-500'
    $rx = New-Prescription $script:PhrEncounter @(
        @{ drugPublicId = $para.publicId; dose = '500 mg'; route = 'PO'; frequency = 'PRN'; durationDays = 1; quantity = 1 }
    )
    [void](Issue-Prescription $script:PhrEncounter $rx.publicId)
    $view = Get-Prescription $rx.publicId
    $noReason = Invoke-Api $script:Admin POST "/prescriptions/$($rx.publicId)/reject" @{
        version = $view.version
    } -AllowError
    Assert-Code $noReason 400 'VALIDATION_FAILED'
    $rej = Invoke-Api $script:Admin POST "/prescriptions/$($rx.publicId)/reject" @{
        version = $view.version
        reason  = 'Duplicate therapy'
    }
    Assert-Code $rej 200
    Assert-True ($rej.Body.status -eq 'REJECTED') "status $($rej.Body.status)"
    $disp = Invoke-Api $script:Admin POST "/prescriptions/$($rx.publicId)/dispense" @{
        version = $rej.Body.version
    } -AllowError
    Assert-Code $disp 409 'INVALID_STATE'
    'rejected'
}
Check 'TC-093 FEFO dispenses the earliest-expiring batch first' {
    $drug = Invoke-Api $script:Admin POST '/pharmacy/drugs' @{
        code   = "QAF$script:RunId"
        nameAr = "QA FEFO $script:RunId"
        form   = 'TABLET'
    }
    Assert-Code $drug 201
    $early = Invoke-Api $script:Admin POST '/stock/batches' @{
        drugPublicId = $drug.Body.publicId
        batchNumber  = "FE-A-$script:RunId"
        expiryDate   = (Get-Date).AddDays(30).ToString('yyyy-MM-dd')
        quantity     = 10
        unitCost     = 0.10
    }
    Assert-Code $early 201
    $late = Invoke-Api $script:Admin POST '/stock/batches' @{
        drugPublicId = $drug.Body.publicId
        batchNumber  = "FE-B-$script:RunId"
        expiryDate   = (Get-Date).AddDays(365).ToString('yyyy-MM-dd')
        quantity     = 10
    }
    Assert-Code $late 201
    $rx = New-Prescription $script:PhrEncounter @(
        @{ drugPublicId = $drug.Body.publicId; dose = '1 tab'; route = 'PO'; frequency = 'OD'; durationDays = 5; quantity = 5 }
    )
    [void](Issue-Prescription $script:PhrEncounter $rx.publicId)
    $view = Get-Prescription $rx.publicId
    [void](Invoke-Api $script:Admin POST "/prescriptions/$($rx.publicId)/review" @{ version = $view.version })
    $view = Get-Prescription $rx.publicId
    $r = Invoke-Api $script:Admin POST "/prescriptions/$($rx.publicId)/dispense" @{ version = $view.version }
    Assert-Code $r 200
    $line = @($r.Body.items)[0]
    Assert-True ($line.batchNumber -eq "FE-A-$script:RunId") "batch $($line.batchNumber)"
    Assert-True ([int]$line.quantity -eq 5) "quantity $($line.quantity)"
    $batches = Get-ActiveBatches $drug.Body.publicId
    $earlyAfter = @($batches | Where-Object { $_.publicId -eq $early.Body.publicId })[0]
    $lateAfter = @($batches | Where-Object { $_.publicId -eq $late.Body.publicId })[0]
    Assert-True ([int]$earlyAfter.quantityAvailable -eq 5) "early $($earlyAfter.quantityAvailable)"
    Assert-True ([int]$lateAfter.quantityAvailable -eq 10) "late $($lateAfter.quantityAvailable)"
    $script:FefoDispensing = $r.Body
    $script:FefoDrug = $drug.Body
    $line.batchNumber
}
Check 'TC-093 expired batches are not received and never dispensed' {
    $drug = Get-DrugByCode "QAF$script:RunId"
    $r = Invoke-Api $script:Admin POST '/stock/batches' @{
        drugPublicId = $drug.publicId
        batchNumber  = "FE-X-$script:RunId"
        expiryDate   = (Get-Date).AddDays(-1).ToString('yyyy-MM-dd')
        quantity     = 10
    } -AllowError
    Assert-Code $r 422 'BATCH_EXPIRED'
    'rejected'
}
Check 'TC-094 dispensing more than the prescription remainder is rejected 422' {
    $drug = Get-DrugByCode "QAF$script:RunId"
    $rx = New-Prescription $script:PhrEncounter @(
        @{ drugPublicId = $drug.publicId; dose = '1 tab'; route = 'PO'; frequency = 'OD'; durationDays = 10; quantity = 10 }
    )
    [void](Issue-Prescription $script:PhrEncounter $rx.publicId)
    $view = Get-Prescription $rx.publicId
    [void](Invoke-Api $script:Admin POST "/prescriptions/$($rx.publicId)/review" @{ version = $view.version })
    $view = Get-Prescription $rx.publicId
    $item = @($view.items)[0]
    $r = Invoke-Api $script:Admin POST "/prescriptions/$($rx.publicId)/dispense" @{
        version = $view.version
        items   = @(@{ prescriptionItemPublicId = $item.publicId; quantity = 11 })
    } -AllowError
    Assert-Code $r 422 'QUANTITY_EXCEEDS_REMAINING'
    'rejected'
}
Check 'TC-094 insufficient stock leaves no negative balance and no partial movement' {
    $drug = Invoke-Api $script:Admin POST '/pharmacy/drugs' @{
        code   = "QAI$script:RunId"
        nameAr = "QA Insufficient $script:RunId"
        form   = 'TABLET'
    }
    Assert-Code $drug 201
    $batch = Invoke-Api $script:Admin POST '/stock/batches' @{
        drugPublicId = $drug.Body.publicId
        batchNumber  = "IN-A-$script:RunId"
        expiryDate   = (Get-Date).AddDays(120).ToString('yyyy-MM-dd')
        quantity     = 3
        unitCost     = 0.20
    }
    Assert-Code $batch 201
    $rx = New-Prescription $script:PhrEncounter @(
        @{ drugPublicId = $drug.Body.publicId; dose = '1 tab'; route = 'PO'; frequency = 'OD'; durationDays = 10; quantity = 10 }
    )
    [void](Issue-Prescription $script:PhrEncounter $rx.publicId)
    $view = Get-Prescription $rx.publicId
    [void](Invoke-Api $script:Admin POST "/prescriptions/$($rx.publicId)/review" @{ version = $view.version })
    $view = Get-Prescription $rx.publicId
    $previous = $null
    try {
        $settings = Invoke-Api $script:Admin GET '/admin/settings'
        foreach ($s in @($settings.Body)) { if ($s.key -eq 'phr.dispense.partial.allowed') { $previous = $s.value } }
        [void](Set-Bool-Setting 'phr.dispense.partial.allowed' $false)
        $r = Invoke-Api $script:Admin POST "/prescriptions/$($rx.publicId)/dispense" @{ version = $view.version } -AllowError
        Assert-Code $r 409 'NO_STOCK'
    } finally {
        $restore = -not ($previous -eq '0' -or $previous -eq 'false' -or $previous -eq $false)
        [void](Set-Bool-Setting 'phr.dispense.partial.allowed' $restore)
    }
    $batches = Get-ActiveBatches $drug.Body.publicId
    foreach ($b in $batches) { Assert-True ([int]$b.quantityAvailable -ge 0) "negative stock $($b.quantityAvailable)" }
    Assert-True ([int]$batches[0].quantityAvailable -eq 3) "stock changed to $($batches[0].quantityAvailable)"
    $moves = Invoke-Api $script:Admin GET "/stock/movements?drug=$($drug.Body.publicId)&type=DISPENSE&size=100"
    Assert-True (@($moves.Body.items).Count -eq 0) "dispense movements $(@($moves.Body.items).Count)"
    $after = Get-Prescription $rx.publicId
    Assert-True ($after.status -eq 'REVIEWED') "prescription status $($after.status)"
    'no partial movement'
}
Check 'TC-095 dispensing records batch-linked movements and balances' {
    $amox = Get-DrugByCode 'AMOX-500'
    $before = (Get-ActiveBatches $amox.publicId)[0].quantityAvailable
    $r = Invoke-Api $script:Admin POST "/prescriptions/$($script:MainRx.publicId)/dispense" @{
        version = $script:MainRxView.version
        notes   = 'Dispensed by QA'
    }
    Assert-Code $r 200
    Assert-True ($r.Body.status -eq 'COMMITTED') "status $($r.Body.status)"
    Assert-True ($r.Body.prescriptionStatus -eq 'DISPENSED') "prescription $($r.Body.prescriptionStatus)"
    Assert-True (@($r.Body.items).Count -eq 2) "items $(@($r.Body.items).Count)"
    foreach ($line in @($r.Body.items)) {
        Assert-True ([bool]$line.batchNumber) 'batchNumber missing'
        Assert-True ([bool]$line.expiryDate) 'expiryDate missing'
        Assert-True ([bool]$line.drug.code) 'drug missing'
    }
    $moves = Invoke-Api $script:Admin GET "/stock/movements?drug=$($amox.publicId)&type=DISPENSE&size=100"
    $move = @($moves.Body.items | Where-Object { $_.referencePublicId -eq $r.Body.publicId })[0]
    Assert-True ($null -ne $move) 'dispense movement missing'
    Assert-True ($move.batch.batchNumber -eq @($r.Body.items)[0].batchNumber) 'movement batch mismatch'
    $script:MainDispensing = $r.Body
    $script:AmoxBefore = [int]$before
    "dispensing=$($r.Body.publicId)"
}
Check 'TC-096 return is linked to the original dispensing and restores stock' {
    $line = @($script:MainDispensing.items)[0]
    $before = [int](Get-ActiveBatches $script:Amox.publicId | Where-Object { $_.batchNumber -eq $line.batchNumber })[0].quantityAvailable
    $r = Invoke-Api $script:Admin POST "/dispensings/$($script:MainDispensing.publicId)/return" @{
        reason = 'Patient returned one unit'
        items  = @(@{ dispensingItemPublicId = $line.publicId; quantity = 1 })
    }
    Assert-Code $r 200
    Assert-True ($r.Body.returnedQuantity -eq 1) "returned $($r.Body.returnedQuantity)"
    Assert-True ($r.Body.status -eq 'PARTIALLY_RETURNED') "status $($r.Body.status)"
    Assert-True (@($r.Body.returns).Count -ge 1) 'returns missing'
    $after = [int](Get-ActiveBatches $script:Amox.publicId | Where-Object { $_.batchNumber -eq $line.batchNumber })[0].quantityAvailable
    Assert-True ($after -eq ($before + 1)) "stock $before -> $after"
    $moves = Invoke-Api $script:Admin GET "/stock/movements?drug=$($script:Amox.publicId)&type=RETURN&size=100"
    $move = @($moves.Body.items | Where-Object { $_.referencePublicId -eq $script:MainDispensing.publicId })[0]
    Assert-True ($null -ne $move) 'return movement missing'
    $script:MainDispensing = $r.Body
    'returned'
}
Check 'TC-096 returning more than dispensed is rejected' {
    $line = @($script:MainDispensing.items)[0]
    $r = Invoke-Api $script:Admin POST "/dispensings/$($script:MainDispensing.publicId)/return" @{
        reason = 'Over-return attempt'
        items  = @(@{ dispensingItemPublicId = $line.publicId; quantity = 999 })
    } -AllowError
    Assert-Code $r 422 'RETURN_EXCEEDS_SOLD'
    $line2 = @($script:MainDispensing.items)[1]
    $all = Invoke-Api $script:Admin POST "/dispensings/$($script:MainDispensing.publicId)/return" @{
        reason = 'Return remaining units'
        items  = @(
            @{ dispensingItemPublicId = $line.publicId; quantity = ([int]$line.quantity - [int]$line.returnedQuantity) },
            @{ dispensingItemPublicId = $line2.publicId; quantity = [int]$line2.quantity }
        )
    }
    Assert-Code $all 200
    Assert-True ($all.Body.status -eq 'RETURNED') "status $($all.Body.status)"
    'rejected then fully returned'
}
Check 'TC-097 low-stock alert reports drug, balance and reorder level' {
    $drug = Invoke-Api $script:Admin POST '/pharmacy/drugs' @{
        code         = "QAL$script:RunId"
        nameAr       = "QA Low $script:RunId"
        form         = 'CAPSULE'
        reorderLevel = 10
    }
    Assert-Code $drug 201
    $batch = Invoke-Api $script:Admin POST '/stock/batches' @{
        drugPublicId = $drug.Body.publicId
        batchNumber  = "LOW-$script:RunId"
        expiryDate   = (Get-Date).AddDays(120).ToString('yyyy-MM-dd')
        quantity     = 2
    }
    Assert-Code $batch 201
    $alerts = Invoke-Api $script:Admin GET '/stock/alerts'
    $row = @($alerts.Body.lowStock | Where-Object { $_.publicId -eq $drug.Body.publicId })[0]
    Assert-True ($null -ne $row) 'drug missing from low stock'
    Assert-True ([int]$row.stockAvailable -eq 2) "stock $($row.stockAvailable)"
    Assert-True ([int]$row.reorderLevel -eq 10) "reorder $($row.reorderLevel)"
    Assert-True ($row.lowStock -eq $true) 'lowStock flag missing'
    $script:LowDrug = $drug.Body
    'low stock reported'
}
Check 'TC-098 expiring batch appears in the configurable expiry window' {
    $batch = Invoke-Api $script:Admin POST '/stock/batches' @{
        drugPublicId = $script:LowDrug.publicId
        batchNumber  = "EXP-$script:RunId"
        expiryDate   = (Get-Date).AddDays(10).ToString('yyyy-MM-dd')
        quantity     = 3
    }
    Assert-Code $batch 201
    $alerts = Invoke-Api $script:Admin GET '/stock/alerts'
    $row = @($alerts.Body.expiring | Where-Object { $_.publicId -eq $batch.Body.publicId })[0]
    Assert-True ($null -ne $row) 'batch missing from expiring list'
    Assert-True ([int]$row.daysToExpiry -ge 0) "days $($row.daysToExpiry)"
    Assert-True ([int]$row.daysToExpiry -le [int]$alerts.Body.expiryWarningDays) "days $($row.daysToExpiry) window $($alerts.Body.expiryWarningDays)"
    "$($row.daysToExpiry) days"
}
Check 'TC-099 pharmacy view exposes patient allergies and interactions' {
    $allergy = Invoke-Api $script:Admin POST "/patients/$($script:PhrPatient.publicId)/allergies" @{
        substance   = 'AMOX-500'
        allergyType = 'DRUG'
        severity    = 'SEVERE'
        reaction    = 'Rash'
    }
    Assert-Code $allergy 201
    $metf = Get-DrugByCode 'METF-500'
    $amox = Get-DrugByCode 'AMOX-500'
    Assert-True ($null -ne $metf) 'METF-500 missing'
    $rx = New-Prescription $script:PhrEncounter @(
        @{ drugPublicId = $metf.publicId; dose = '500 mg'; route = 'PO'; frequency = 'BID'; durationDays = 3; quantity = 6 },
        @{ drugPublicId = $amox.publicId; dose = '500 mg'; route = 'PO'; frequency = 'TID'; durationDays = 3; quantity = 9 }
    )
    [void](Issue-Prescription $script:PhrEncounter $rx.publicId)
    $view = Get-Prescription $rx.publicId
    $allergyRow = @($view.allergies | Where-Object { $_.substance -eq 'AMOX-500' })[0]
    Assert-True ($null -ne $allergyRow) 'allergy not exposed'
    Assert-True (@($view.interactions).Count -ge 1) "interactions $(@($view.interactions).Count)"
    $r = Invoke-Api $script:Admin POST "/prescriptions/$($rx.publicId)/review" @{
        version                = $view.version
        acknowledgeInteractions = $true
        notes                  = 'Acknowledged moderate interaction'
    }
    Assert-Code $r 200
    Assert-True (@($r.Body.interactions).Count -ge 1) 'review did not return interactions'
    'allergy + interaction shown'
}
Check 'TC-100 batch receipt records number, expiry and quantity' {
    $r = Invoke-Api $script:Admin POST '/stock/batches' @{
        drugPublicId = $script:LowDrug.publicId
        batchNumber  = "QA-B-$script:RunId"
        expiryDate   = (Get-Date).AddDays(200).ToString('yyyy-MM-dd')
        quantity     = 5
        unitCost     = 0.75
        supplier     = 'QA Supplier'
        lotNumber    = "LOT-$script:RunId"
    }
    Assert-Code $r 201
    Assert-True ($r.Body.batchNumber -eq "QA-B-$script:RunId") "batch $($r.Body.batchNumber)"
    Assert-True ([int]$r.Body.quantityAvailable -eq 5) "qty $($r.Body.quantityAvailable)"
    Assert-True ([bool]$r.Body.expiryDate) 'expiryDate missing'
    $dup = Invoke-Api $script:Admin POST '/stock/batches' @{
        drugPublicId = $script:LowDrug.publicId
        batchNumber  = "QA-B-$script:RunId"
        expiryDate   = (Get-Date).AddDays(200).ToString('yyyy-MM-dd')
        quantity     = 5
    } -AllowError
    Assert-Code $dup 409 'BATCH_EXISTS'
    $listed = Invoke-Api $script:Admin GET "/stock/batches?drug=$($script:LowDrug.publicId)&size=100"
    Assert-True (@($listed.Body.items | Where-Object { $_.publicId -eq $r.Body.publicId }).Count -eq 1) 'batch not listed'
    'registered'
}
Skip 'TC-100 dispensing from an unregistered batch' 'Dispense API never accepts a caller-supplied batch: FEFO selects only registered batches from the database.'
Check 'TC-101 movement totals match dispensing and returns without rounding drift' {
    $moves = Invoke-Api $script:Admin GET "/stock/movements?drug=$($script:Amox.publicId)&size=200"
    $dispensed = 0
    $returned = 0
    foreach ($m in @($moves.Body.items)) {
        if ($m.movementType -eq 'DISPENSE') { $dispensed += [math]::Abs([int]$m.quantityDelta) }
        if ($m.movementType -eq 'RETURN') { $returned += [int]$m.quantityDelta }
    }
    Assert-True ($dispensed -gt 0) 'no dispense movements'
    Assert-True ($returned -ge 0) 'negative returns'
    $today = (Get-Date).ToString('yyyy-MM-dd')
    $filtered = Invoke-Api $script:Admin GET "/stock/movements?drug=$($script:Amox.publicId)&type=DISPENSE&date=$today&size=200"
    Assert-True (@($filtered.Body.items).Count -ge 1) 'date/type filter returned nothing'
    foreach ($m in @($filtered.Body.items)) { Assert-True ($m.movementType -eq 'DISPENSE') 'type filter leaked' }
    "dispensed=$dispensed returned=$returned"
}

# ================================================================ concurrency

Section 'Concurrency - TC-060/168, TC-167, TC-171, TC-172, TC-173'

Check 'TC-060/168 two parallel bed assignments produce one winner' {
    $p1 = New-Patient 'CC-BED-1'
    $p2 = New-Patient 'CC-BED-2'
    $e1 = New-EncounterId $p1.publicId 'INPATIENT'
    $e2 = New-EncounterId $p2.publicId 'INPATIENT'
    $a1 = Approve-Admission (New-Admission $p1.publicId $e1 'Concurrency bed test')
    $a2 = Approve-Admission (New-Admission $p2.publicId $e2 'Concurrency bed test')
    $bed = Find-AvailableBed (Get-Board) 'WARD-MED-A'
    Assert-True ($null -ne $bed) 'no available bed for race'
    $script:RaceBed = $bed
    $go = (Get-Date).AddSeconds(3)
    $jobs = @(
        (New-Job $script:Admin 'POST' "/admissions/$($a1.publicId)/assign-bed" @{ bedPublicId = $bed.publicId; version = $a1.version } -StartAt $go),
        (New-Job $script:Admin2 'POST' "/admissions/$($a2.publicId)/assign-bed" @{ bedPublicId = $bed.publicId; version = $a2.version } -StartAt $go)
    )
    $results = Invoke-Parallel $jobs
    $ok = @($results | Where-Object { $_.Status -eq 200 })
    $conflict = @($results | Where-Object { $_.Status -eq 409 })
    Assert-True ($ok.Count -eq 1) "winners $($ok.Count) :: $($results | ForEach-Object { $_.Status } )"
    Assert-True ($conflict.Count -eq 1) "conflicts $($conflict.Count)"
    $board = Get-Board
    $after = Find-BedById $board $bed.publicId
    Assert-True ($after.status -eq 'OCCUPIED') "bed $($after.status)"
    Assert-True (@($board.wards | ForEach-Object { $_.beds } | Where-Object { $_.publicId -eq $bed.publicId -and $_.status -eq 'OCCUPIED' }).Count -eq 1) 'bed not uniquely occupied'
    $winner = $null
    if ($results[0].Status -eq 200) { $winner = $a1 } else { $winner = $a2 }
    $winnerNow = Invoke-Api $script:Admin GET "/admissions/$($winner.publicId)"
    Assert-True ($winnerNow.Body.admission.status -eq 'ADMITTED') "winner $($winnerNow.Body.admission.status)"
    $loser = $null
    if ($results[0].Status -eq 409) { $loser = $a1 } else { $loser = $a2 }
    $loserNow = Invoke-Api $script:Admin GET "/admissions/$($loser.publicId)"
    Assert-True ($loserNow.Body.admission.status -eq 'APPROVED') "loser $($loserNow.Body.admission.status)"
    $script:RaceWinner = $winnerNow.Body.admission
    $script:RaceLoser = $loserNow.Body.admission
    "winner=$($winner.publicId)"
}
Check 'TC-060/168 cleanup discharges the winner and frees the bed' {
    $done = Discharge-Admission $script:RaceWinner 'HOME'
    Assert-True ($done.status -eq 'DISCHARGED') "status $($done.status)"
    Release-Bed $script:RaceBed.publicId
    'cleaned'
}
Check 'TC-167 two parallel dispenses of the last stock produce one winner' {
    $drug = Invoke-Api $script:Admin POST '/pharmacy/drugs' @{
        code   = "QAC$script:RunId"
        nameAr = "QA Concurrency $script:RunId"
        form   = 'TABLET'
    }
    Assert-Code $drug 201
    $batch = Invoke-Api $script:Admin POST '/stock/batches' @{
        drugPublicId = $drug.Body.publicId
        batchNumber  = "CC-$script:RunId"
        expiryDate   = (Get-Date).AddDays(90).ToString('yyyy-MM-dd')
        quantity     = 10
    }
    Assert-Code $batch 201
    $rx1 = New-Prescription $script:PhrEncounter @(
        @{ drugPublicId = $drug.Body.publicId; dose = '1 tab'; route = 'PO'; frequency = 'OD'; durationDays = 10; quantity = 10 }
    )
    $rx2 = New-Prescription $script:PhrEncounter @(
        @{ drugPublicId = $drug.Body.publicId; dose = '1 tab'; route = 'PO'; frequency = 'OD'; durationDays = 10; quantity = 10 }
    )
    [void](Issue-Prescription $script:PhrEncounter $rx1.publicId)
    [void](Issue-Prescription $script:PhrEncounter $rx2.publicId)
    $v1 = Get-Prescription $rx1.publicId
    $v2 = Get-Prescription $rx2.publicId
    [void](Invoke-Api $script:Admin POST "/prescriptions/$($rx1.publicId)/review" @{ version = $v1.version })
    [void](Invoke-Api $script:Admin POST "/prescriptions/$($rx2.publicId)/review" @{ version = $v2.version })
    $v1 = Get-Prescription $rx1.publicId
    $v2 = Get-Prescription $rx2.publicId
    $go = (Get-Date).AddSeconds(3)
    $jobs = @(
        (New-Job $script:Admin 'POST' "/prescriptions/$($rx1.publicId)/dispense" @{ version = $v1.version } -StartAt $go),
        (New-Job $script:Admin2 'POST' "/prescriptions/$($rx2.publicId)/dispense" @{ version = $v2.version } -StartAt $go)
    )
    $results = Invoke-Parallel $jobs
    $ok = @($results | Where-Object { $_.Status -eq 200 })
    $conflict = @($results | Where-Object { $_.Status -eq 409 })
    Assert-True ($ok.Count -eq 1) "winners $($ok.Count) :: $($results | ForEach-Object { $_.Status })"
    Assert-True ($conflict.Count -eq 1) "conflicts $($conflict.Count)"
    $after = @(Get-ActiveBatches $drug.Body.publicId | Where-Object { $_.publicId -eq $batch.Body.publicId })[0]
    if ($null -ne $after) {
        Assert-True ([int]$after.quantityAvailable -eq 0) "stock $($after.quantityAvailable)"
    }
    $moves = Invoke-Api $script:Admin GET "/stock/movements?drug=$($drug.Body.publicId)&type=DISPENSE&size=100"
    Assert-True (@($moves.Body.items).Count -eq 1) "dispense movements $(@($moves.Body.items).Count)"
    'one dispense, no negative stock'
}
Check 'TC-171 two parallel amendments of a signed note produce one winner' {
    $ccPatient = New-Patient 'CC-NOTE'
    $ccEncounter = New-EncounterId $ccPatient.publicId 'OUTPATIENT'
    $r = Invoke-Api $script:Admin POST "/encounters/$ccEncounter/notes" @{
        noteType   = 'PROGRESS'
        subjective = 'Concurrency baseline'
        objective  = 'Stable'
        assessment = 'Stable'
        plan       = 'Observe'
    }
    Assert-Code $r 201
    $note = $r.Body
    $s = Invoke-Api $script:Admin POST "/encounters/$ccEncounter/notes/$($note.publicId)/sign"
    Assert-Code $s 200
    $signedVersion = [int]$s.Body.versionNo
    $noteVersion = [int]$s.Body.version
    $go = (Get-Date).AddSeconds(3)
    $jobs = @(
        (New-Job $script:Admin 'POST' "/encounters/$ccEncounter/notes/$($note.publicId)/amend" @{ reason = 'Concurrent edit A'; subjective = 'Edit A'; version = $noteVersion } -StartAt $go),
        (New-Job $script:Admin2 'POST' "/encounters/$ccEncounter/notes/$($note.publicId)/amend" @{ reason = 'Concurrent edit B'; subjective = 'Edit B'; version = $noteVersion } -StartAt $go)
    )
    $results = Invoke-Parallel $jobs
    $ok = @($results | Where-Object { $_.Status -eq 200 })
    $conflict = @($results | Where-Object { $_.Status -eq 409 })
    Assert-True ($ok.Count -eq 1) "winners $($ok.Count) :: $($results | ForEach-Object { $_.Status })"
    Assert-True ($conflict.Count -eq 1) "conflicts $($conflict.Count)"
    Assert-True ($conflict[0].Body.code -eq 'STALE_VERSION' -or $conflict[0].Body.code -eq 'VERSION_CONFLICT') "code $($conflict[0].Body.code)"
    $versions = Invoke-Api $script:Admin GET "/encounters/$ccEncounter/notes/$($note.publicId)/versions"
    $signed = @($versions.Body | Where-Object { [int]$_.versionNo -eq $signedVersion })[0]
    Assert-True ($null -ne $signed) 'signed version missing'
    Assert-True ($signed.contentText -like 'Concurrency baseline*') "signed content changed: $($signed.contentText)"
    Assert-True ($signed.contentText -notmatch 'Edit A|Edit B') "signed content was overwritten: $($signed.contentText)"
    Assert-True (@($versions.Body).Count -eq 2) "versions $(@($versions.Body).Count)"
    'one winner, signed version intact'
}
Check 'TC-172 two parallel verifications of one result produce one winner' {
    $order = Invoke-Api $script:Admin POST '/lab/orders' @{
        encounterPublicId = $script:LabEncounter
        items             = @('CREAT')
    }
    Assert-Code $order 201
    $collect = Invoke-Api $script:Admin POST "/lab/orders/$($order.Body.publicId)/collect" @{
        version = $order.Body.version
    }
    Assert-Code $collect 200
    $spec = @($collect.Body.specimens)[0]
    $receive = Invoke-Api $script:Admin POST "/lab/specimens/$($spec.publicId)/receive" @{
        version = $spec.version
    }
    Assert-Code $receive 200
    $result = Invoke-Api $script:Admin POST "/lab/orders/$($order.Body.publicId)/result" @{
        testCode     = 'CREAT'
        valueNumeric = 1.0
    }
    Assert-Code $result 200
    $res = Find-LabResult $result.Body 'CREAT'
    $go = (Get-Date).AddSeconds(3)
    $jobs = @(
        (New-Job $script:Admin 'POST' "/lab/results/$($res.publicId)/verify" @{ version = $res.version } -StartAt $go),
        (New-Job $script:Admin2 'POST' "/lab/results/$($res.publicId)/verify" @{ version = $res.version } -StartAt $go)
    )
    $results = Invoke-Parallel $jobs
    $ok = @($results | Where-Object { $_.Status -eq 200 })
    $conflict = @($results | Where-Object { $_.Status -eq 409 })
    Assert-True ($ok.Count -eq 1) "winners $($ok.Count) :: $($results | ForEach-Object { $_.Status })"
    Assert-True ($conflict.Count -eq 1) "conflicts $($conflict.Count)"
    $final = Invoke-Api $script:Admin GET "/lab/results/$($res.publicId)/report"
    Assert-True ($final.Body.results[0].status -eq 'VERIFIED') "status $($final.Body.results[0].status)"
    'one verified version'
}
Check 'TC-173 two parallel bookings of one provider slot produce one winner' {
    $providers = Invoke-Api $script:Admin GET '/scheduling/providers'
    $clinics = Invoke-Api $script:Admin GET '/scheduling/clinics'
    Assert-True (@($providers.Body).Count -ge 1) 'no providers seeded'
    Assert-True (@($clinics.Body).Count -ge 1) 'no clinics seeded'
    $provider = @($providers.Body)[0]
    $clinic = @($clinics.Body)[0]
    $tomorrow = (Get-Date).AddDays(1)
    $dow = [int]$tomorrow.DayOfWeek
    if ($dow -eq 0) { $dow = 7 }
    $schedule = Invoke-Api $script:Admin POST '/scheduling/schedules' @{
        providerPublicId = $provider.publicId
        clinicPublicId   = $clinic.publicId
        dayOfWeek        = $dow
        startTime        = '09:00'
        endTime          = '12:00'
        slotMinutes      = 20
        validFrom        = (Get-Date).ToString('yyyy-MM-dd')
        validTo          = (Get-Date).AddDays(60).ToString('yyyy-MM-dd')
    }
    Assert-True ($schedule.Status -eq 201 -or $schedule.Status -eq 200 -or $schedule.Status -eq 409) "schedule $($schedule.Status) :: $($schedule.Raw)"
    $date = $tomorrow.ToString('yyyy-MM-dd')
    $slots = Invoke-Api $script:Admin GET "/scheduling/slots?provider=$($provider.publicId)&clinic=$($clinic.publicId)&date=$date"
    $slot = @($slots.Body.slots | Where-Object { $_.available })[0]
    Assert-True ($null -ne $slot) 'no available slot'
    $p1 = New-Patient 'CC-SLOT-1'
    $p2 = New-Patient 'CC-SLOT-2'
    $go = (Get-Date).AddSeconds(3)
    $jobs = @(
        (New-Job $script:Admin 'POST' '/scheduling/appointments' @{
            patientPublicId  = $p1.publicId
            providerPublicId = $provider.publicId
            clinicPublicId   = $clinic.publicId
            slotStart        = $slot.start
        } -StartAt $go),
        (New-Job $script:Admin2 'POST' '/scheduling/appointments' @{
            patientPublicId  = $p2.publicId
            providerPublicId = $provider.publicId
            clinicPublicId   = $clinic.publicId
            slotStart        = $slot.start
        } -StartAt $go)
    )
    $results = Invoke-Parallel $jobs
    $ok = @($results | Where-Object { $_.Status -eq 201 -or $_.Status -eq 200 })
    $conflict = @($results | Where-Object { $_.Status -eq 409 })
    Assert-True ($ok.Count -eq 1) "winners $($ok.Count) :: $($results | ForEach-Object { $_.Status })"
    Assert-True ($conflict.Count -eq 1) "conflicts $($conflict.Count)"
    Assert-True ($conflict[0].Body.code -eq 'APPOINTMENT_CONFLICT') "code $($conflict[0].Body.code)"
    $winner = $null
    if ($results[0].Status -lt 300) { $winner = $results[0].Body } else { $winner = $results[1].Body }
    $cancel = Invoke-Api $script:Admin POST "/scheduling/appointments/$($winner.publicId)/cancel" @{
        reason = 'QA concurrency cleanup'
    }
    Assert-True ($cancel.Status -eq 200) "cancel $($cancel.Status) :: $($cancel.Raw)"
    'one appointment'
}

# ================================================================ notifications

Section 'Notifications - outbox delivery and inbox'

Check 'NOT inbox exposes unread count and paginated items' {
    $r = Invoke-Api $script:Admin GET '/notifications?page=0&size=20'
    Assert-True ($null -ne $r.Body.total) 'total missing'
    Assert-True ($null -ne $r.Body.unread) 'unread missing'
    $s = Invoke-Api $script:Admin GET '/notifications/unread-count'
    Assert-True ($null -ne $s.Body.unread) 'summary unread missing'
    Assert-True ($s.Body.unread -eq $r.Body.unread) "unread $($s.Body.unread) vs $($r.Body.unread)"
    "unread=$($r.Body.unread) total=$($r.Body.total)"
}
Check 'NOT mark-one-read and read-all work' {
    $list = Invoke-Api $script:Admin GET '/notifications?unreadOnly=true&size=100'
    $items = @($list.Body.items)
    Assert-True ($items.Count -ge 1) 'no unread notifications to mark'
    $one = Invoke-Api $script:Admin POST "/notifications/$($items[0].publicId)/read"
    Assert-True ($one.Status -eq 200) "mark one $($one.Status)"
    $all = Invoke-Api $script:Admin POST '/notifications/read-all'
    Assert-True ($all.Status -eq 200) "read all $($all.Status)"
    Assert-True ($all.Body.unread -eq 0) "unread after read-all $($all.Body.unread)"
    'inbox clean'
}
Check 'NOT released-result notification has no duplicate' {
    $r = Invoke-Api $script:Admin GET '/notifications?size=100'
    $count = @($r.Body.items | Where-Object {
        $_.type -eq 'LAB_RESULT_RELEASED' -and $_.entityPublicId -eq $script:GlucoseResult.publicId
    }).Count
    Assert-True ($count -eq 1) "duplicate notifications $count"
    'one row'
}

# ================================================================ summary

Write-Host "`n=== Result ===" -ForegroundColor Cyan
Write-Host ("PASS: {0}   FAIL: {1}   SKIP: {2}" -f $script:Pass, $script:Fail, $script:Skipped) -ForegroundColor $(if ($script:Fail -eq 0) { 'Green' } else { 'Red' })
if ($script:Fail -gt 0) {
    Write-Host "`nFailures:" -ForegroundColor Red
    foreach ($f in $script:Failures) { Write-Host "  - $f" -ForegroundColor Red }
    exit 1
}
exit 0
