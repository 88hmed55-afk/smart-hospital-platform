# Phase 5 functional regression: Billing (BIL) + Inventory (INV) + concurrency.
# Covers TC-108..TC-127 and TC-169/170/174/175 (C3/C4/C8/C9).
# Usage: powershell -ExecutionPolicy Bypass -File tools\test-phase5.ps1
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

function Dec {
    param($Value)
    if ($null -eq $Value -or "$Value" -eq '') { return [decimal]0 }
    return [decimal]$Value
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

# ---------------------------------------------------------------- database helper

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

function Db-Scalar {
    param([string]$Sql)
    return ((Invoke-Db $Sql) -join '').Trim()
}

function Db-Int {
    param([string]$Sql)
    return [long](Db-Scalar $Sql)
}

# ---------------------------------------------------------------- domain helpers

function New-Patient {
    param([string]$Label)
    $r = Invoke-Api $script:Admin POST '/patients' @{
        fullName = "QA5 $Label $script:RunId"
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
        chiefComplaint  = 'QA phase5 regression'
    }
    Assert-True ($r.Status -eq 201) "encounter create failed: $($r.Raw)"
    return $r.Body
}

function New-Billable {
    param([string]$Label)
    $patient = New-Patient $Label
    $encounter = (New-Encounter $patient.publicId).encounter.publicId
    # A consultation is billable as soon as it is performed. The encounter stays
    # open, so capture the CONSULT-GEN charge explicitly instead of relying on
    # the close-time capture (EncounterService.captureConsultationCharge).
    [void](Add-Charge $script:Admin $encounter $script:ConsultService 1)
    return [pscustomobject]@{ Patient = $patient.publicId; Encounter = $encounter }
}

function New-Invoice {
    param($Session, [string]$PatientPublicId, [string]$EncounterPublicId = $null)
    $body = @{ patientPublicId = $PatientPublicId }
    if ($EncounterPublicId) { $body.encounterPublicId = $EncounterPublicId }
    $r = Invoke-Api $Session POST '/billing/invoices' $body
    Assert-True ($r.Status -eq 201) "invoice create failed: $($r.Raw)"
    return $r.Body
}

function Add-Charge {
    param($Session, [string]$EncounterPublicId, [string]$ServicePublicId, [int]$Quantity = 1)
    $r = Invoke-Api $Session POST "/encounters/$EncounterPublicId/charges" @{
        servicePublicId = $ServicePublicId
        quantity        = $Quantity
        sourceType      = 'MANUAL'
    }
    Assert-True ($r.Status -eq 201) "charge capture failed: $($r.Raw)"
    return $r.Body
}

function Get-Invoice {
    param($Session, [string]$PublicId)
    $r = Invoke-Api $Session GET "/billing/invoices/$PublicId"
    Assert-True ($r.Status -eq 200) "invoice read failed: $($r.Raw)"
    return $r.Body
}

function Get-Setting {
    param([string]$Key)
    $r = Invoke-Api $script:Admin GET '/admin/settings'
    $match = @($r.Body | Where-Object { $_.key -eq $Key })
    if ($match.Count -eq 0) { throw "setting $Key not found" }
    return $match[0].value
}

function Set-Setting {
    param([string]$Key, [string]$Value)
    return Invoke-Api $script:Admin PUT "/admin/settings/$Key" @{ value = $Value }
}

function Set-Bool-Setting {
    param([string]$Key, [bool]$Value)
    return Set-Setting $Key $(if ($Value) { 'true' } else { 'false' })
}

function New-User {
    param([string]$Username, [string]$DisplayName, [string]$RoleCode)
    $r = Invoke-Api $script:Admin POST '/admin/users' @{
        username      = $Username
        displayNameAr = $DisplayName
        password      = 'QaPass2026x!!'
        roleCodes     = @($RoleCode)
    }
    Assert-True ($r.Status -eq 201) "create user $Username failed: $($r.Raw)"
    return New-ReadySession $Username 'QaPass2026x!!' 'QaPass2026y!!'
}

function Find-Item {
    param([string]$Code)
    $r = Invoke-Api $script:Admin GET "/inventory/items?q=$Code&size=50"
    Assert-True ($r.Status -eq 200) "item search failed: $($r.Raw)"
    $match = @($r.Body.items | Where-Object { $_.code -eq $Code })
    if ($match.Count -eq 0) { throw "item $Code not found" }
    return $match[0]
}

function Get-ItemDetail {
    param([string]$PublicId)
    $r = Invoke-Api $script:Admin GET "/inventory/items/$PublicId"
    Assert-True ($r.Status -eq 200) "item read failed: $($r.Raw)"
    return $r.Body
}

function Find-Warehouse {
    param([string]$Code)
    $r = Invoke-Api $script:Admin GET '/inventory/warehouses?activeOnly=false'
    Assert-True ($r.Status -eq 200) "warehouse list failed: $($r.Raw)"
    $match = @($r.Body.items | Where-Object { $_.code -eq $Code })
    if ($match.Count -eq 0) { throw "warehouse $Code not found" }
    return $match[0]
}

function Find-Batch {
    param([string]$ItemPublicId, [string]$WarehousePublicId, [string]$BatchNumber)
    $r = Invoke-Api $script:Admin GET "/inventory/batches?item=$ItemPublicId&warehouse=$WarehousePublicId&size=100"
    Assert-True ($r.Status -eq 200) "batch list failed: $($r.Raw)"
    $match = @($r.Body.items | Where-Object { $_.batchNumber -eq $BatchNumber })
    if ($match.Count -eq 0) { throw "batch $BatchNumber not found" }
    return $match[0]
}

function Batch-Quantity {
    param([string]$PublicId)
    return [long](Db-Scalar "SELECT quantity_available FROM smart_hospital.inventory_batches WHERE public_id='$PublicId';")
}

function Receive-Batch {
    param($Session, [string]$ItemPublicId, [string]$WarehousePublicId,
          [string]$BatchNumber, [int]$Quantity, [decimal]$UnitCost = 2.5,
          [string]$ExpiryDate = $null, [string]$SupplierPublicId = $null, [string]$LotNumber = $null)
    $body = @{
        itemPublicId      = $ItemPublicId
        warehousePublicId = $WarehousePublicId
        batchNumber       = $BatchNumber
        quantity          = $Quantity
        unitCost          = $UnitCost
    }
    if ($ExpiryDate) { $body.expiryDate = $ExpiryDate }
    if ($SupplierPublicId) { $body.supplierPublicId = $SupplierPublicId }
    if ($LotNumber) { $body.lotNumber = $LotNumber }
    return Invoke-Api $Session POST '/inventory/batches' $body
}

function Issue-Stock {
    param($Session, [string]$WarehousePublicId, $Lines, [string]$DocumentRef = $null, [string]$Reason = $null)
    $items = @()
    foreach ($line in @($Lines)) {
        $items += @{
            itemPublicId      = $line.Item
            warehousePublicId = $(if ($line.Warehouse) { $line.Warehouse } else { $WarehousePublicId })
            quantity          = $line.Quantity
        }
    }
    $body = @{ items = $items }
    if ($DocumentRef) { $body.documentRef = $DocumentRef }
    if ($Reason) { $body.reason = $Reason }
    return Invoke-Api $Session POST '/inventory/issues' $body
}

function Transfer-Stock {
    param($Session, [string]$From, [string]$To, [string]$ItemPublicId, [int]$Quantity,
          [string]$DocumentRef = $null)
    $body = @{
        fromWarehousePublicId = $From
        toWarehousePublicId   = $To
        items                 = @(@{ itemPublicId = $ItemPublicId; quantity = $Quantity })
    }
    if ($DocumentRef) { $body.documentRef = $DocumentRef }
    return Invoke-Api $Session POST '/inventory/transfers' $body
}

# ---------------------------------------------------------------- setup

$script:RunId = (Get-Date -Format 'MMddHHmmss')

Write-Host "`n=== Phase 5 functional regression (run $script:RunId) ===" -ForegroundColor Cyan

Section 'Setup'

Check 'SETUP login as admin' {
    $script:Admin = New-Session 'admin' 'Admin@2026Secure'
    Assert-True ([bool]$script:Admin.Csrf) 'csrfToken missing'
    $script:Admin.User.username
}

Check 'SETUP create QA-only roles (billing clerk, count clerk)' {
    [void](Invoke-Db @"
INSERT IGNORE INTO smart_hospital.roles (public_id, code, name_ar, name_en, description, system_role)
VALUES ('00000000000000000000QA0001', 'QA_BILLING_CLERK', 'QA Billing Clerk', 'QA Billing Clerk', 'QA fixture: BIL:REFUND without approve', 0),
       ('00000000000000000000QA0002', 'QA_COUNT_CLERK', 'QA Count Clerk', 'QA Count Clerk', 'QA fixture: INV:COUNT without adjust', 0);
INSERT IGNORE INTO smart_hospital.role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM smart_hospital.roles r JOIN smart_hospital.permissions p
 WHERE r.code = 'QA_BILLING_CLERK'
   AND p.code IN ('BIL:READ','BIL:CREATE','BIL:PAYMENT','BIL:REFUND','BIL:PRINT');
INSERT IGNORE INTO smart_hospital.role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM smart_hospital.roles r JOIN smart_hospital.permissions p
 WHERE r.code = 'QA_COUNT_CLERK'
   AND p.code IN ('INV:READ','INV:COUNT');
"@)
    $roles = Db-Int "SELECT COUNT(*) FROM smart_hospital.roles WHERE code IN ('QA_BILLING_CLERK','QA_COUNT_CLERK');"
    Assert-True ($roles -eq 2) "expected 2 QA roles, found $roles"
    'ok'
}

Check 'SETUP create cashier session' {
    $script:Cashier = New-User "qa5.cashier.$script:RunId" 'QA5 Cashier' 'CASHIER'
    $script:Cashier.User.username
}

Check 'SETUP create finance manager session' {
    $script:Fin = New-User "qa5.fin.$script:RunId" 'QA5 Finance' 'FIN_MANAGER'
    $script:Fin.User.username
}

Check 'SETUP create storekeeper session' {
    $script:Store = New-User "qa5.store.$script:RunId" 'QA5 Storekeeper' 'STOREKEEPER'
    $script:Store.User.username
}

Check 'SETUP create billing clerk (refund without approve) session' {
    $script:Clerk = New-User "qa5.clerk.$script:RunId" 'QA5 Billing Clerk' 'QA_BILLING_CLERK'
    $script:Clerk.User.username
}

Check 'SETUP create count clerk (count without adjust) session' {
    $script:Counter = New-User "qa5.counter.$script:RunId" 'QA5 Count Clerk' 'QA_COUNT_CLERK'
    $script:Counter.User.username
}

Check 'SETUP service catalog IDs' {
    $r = Invoke-Api $script:Admin GET '/billing/service-prices'
    Assert-True ($r.Status -eq 200) "price list failed: $($r.Raw)"
    $consult = @($r.Body.items | Where-Object { $_.code -eq 'CONSULT-GEN' })
    $ecg = @($r.Body.items | Where-Object { $_.code -eq 'PROC-ECG' })
    Assert-True ($consult.Count -ge 1) 'CONSULT-GEN price missing'
    Assert-True ($ecg.Count -ge 1) 'PROC-ECG price missing'
    $script:ConsultService = $consult[0].servicePublicId
    $script:EcgService = $ecg[0].servicePublicId
    $script:EcgBase = [decimal]$ecg[0].amount
    "consult=$script:ConsultService ecg=$script:EcgService ecgBase=$script:EcgBase"
}

Check 'SETUP health' {
    $r = Invoke-Api $script:Admin GET '/health'
    Assert-True ($r.Status -eq 200) "health failed: $($r.Raw)"
    $r.Body.status
}

# ---------------------------------------------------------------- billing

Section 'BIL - invoices (TC-108..TC-117)'

Check 'TC-108 invoice from captured charges, no double billing' {
    $b = New-Billable 'bil108'
    $inv = New-Invoice $script:Cashier $b.Patient $b.Encounter
    Assert-True ($inv.status -eq 'ISSUED') "expected ISSUED, got $($inv.status)"
    Assert-True (@($inv.items).Count -eq 1) "expected 1 line, got $(@($inv.items).Count)"
    Assert-True ($inv.items[0].serviceCode -eq 'CONSULT-GEN') "unexpected service $($inv.items[0].serviceCode)"
    Assert-True ((Dec $inv.subtotal) -eq (Dec $inv.grandTotal)) 'subtotal/grand mismatch'
    $again = Invoke-Api $script:Cashier POST '/billing/invoices' @{
        patientPublicId   = $b.Patient
        encounterPublicId = $b.Encounter
    } -AllowError
    Assert-Code $again 422 'NO_CHARGES'
    $items = Db-Int "SELECT COUNT(*) FROM smart_hospital.invoice_items ii JOIN smart_hospital.invoices i ON i.id=ii.invoice_id WHERE i.public_id='$($inv.publicId)';"
    Assert-True ($items -eq 1) "expected 1 invoice item row, got $items"
    $dupes = Db-Int "SELECT COUNT(*) FROM (SELECT service_execution_id FROM smart_hospital.invoice_items GROUP BY service_execution_id HAVING COUNT(*) > 1) d;"
    Assert-True ($dupes -eq 0) "found $dupes duplicated service executions"
    "invoice=$($inv.invoiceNumber) total=$($inv.grandTotal)"
}

Check 'TC-109 price snapshot is frozen at execution time' {
    $b = New-Billable 'bil109'
    [void](Add-Charge $script:Admin $b.Encounter $script:EcgService 1)
    $inv1 = New-Invoice $script:Cashier $b.Patient $b.Encounter
    $ecg1 = @($inv1.items | Where-Object { $_.serviceCode -eq 'PROC-ECG' })
    Assert-True ($ecg1.Count -eq 1) 'ECG line missing on first invoice'
    Assert-True ((Dec $ecg1[0].unitPrice) -eq $script:EcgBase) "expected ECG $script:EcgBase, got $($ecg1[0].unitPrice)"

    $p1 = $script:EcgBase + 12
    $price = Invoke-Api $script:Fin POST '/billing/service-prices' @{
        servicePublicId = $script:EcgService
        amount          = $p1
        validFrom       = (Get-Date -Format 'yyyy-MM-dd')
    }
    Assert-True ($price.Status -eq 201) "price change failed: $($price.Raw)"
    [void](Add-Charge $script:Admin $b.Encounter $script:EcgService 1)
    $inv2 = New-Invoice $script:Cashier $b.Patient $b.Encounter
    Assert-True (@($inv2.items).Count -eq 1) "expected only the unbilled ECG line, got $(@($inv2.items).Count)"
    Assert-True ((Dec $inv2.items[0].unitPrice) -eq $p1) "expected ECG $p1, got $($inv2.items[0].unitPrice)"

    $p2 = $p1 + 12
    $price2 = Invoke-Api $script:Fin POST '/billing/service-prices' @{
        servicePublicId = $script:EcgService
        amount          = $p2
        validFrom       = (Get-Date -Format 'yyyy-MM-dd')
    }
    Assert-True ($price2.Status -eq 201) "second price change failed: $($price2.Raw)"
    [void](Add-Charge $script:Admin $b.Encounter $script:EcgService 1)
    $inv3 = New-Invoice $script:Cashier $b.Patient $b.Encounter
    Assert-True ((Dec $inv3.items[0].unitPrice) -eq $p2) "expected ECG $p2, got $($inv3.items[0].unitPrice)"

    $oldPrice = Dec (Db-Scalar "SELECT unit_price FROM smart_hospital.invoice_items WHERE public_id='$($ecg1[0].publicId)';")
    Assert-True ($oldPrice -eq $script:EcgBase) "stored snapshot changed to $oldPrice"
    $newPrice = Dec (Db-Scalar "SELECT unit_price FROM smart_hospital.invoice_items WHERE public_id='$($inv2.items[0].publicId)';")
    Assert-True ($newPrice -eq $p1) "second snapshot wrong: $newPrice"
    "snapshots $script:EcgBase -> $p1 -> $p2 preserved"
}

Check 'SETUP set CONSULT-GEN price to 20 for deterministic totals' {
    $r = Invoke-Api $script:Fin POST '/billing/service-prices' @{
        servicePublicId = $script:ConsultService
        amount          = 20
        validFrom       = (Get-Date -Format 'yyyy-MM-dd')
    }
    Assert-True ($r.Status -eq 201) "consult price change failed: $($r.Raw)"
    [void](Set-Setting 'billing.discount.max_percent' '20')
    [void](Set-Setting 'billing.tax.percent' '0')
    "consult=20 maxDiscount=20 tax=0"
}

Check 'TC-110 discount within limit, above limit and missing reason' {
    $b = New-Billable 'bil110'
    $inv = New-Invoice $script:Cashier $b.Patient $b.Encounter
    Assert-True ((Dec $inv.grandTotal) -eq (Dec 20)) "expected 20 total, got $($inv.grandTotal)"

    $noReason = Invoke-Api $script:Cashier POST "/billing/invoices/$($inv.publicId)/discount" @{ percent = 10 } -AllowError
    Assert-Code $noReason 400 'VALIDATION_FAILED'

    $ok = Invoke-Api $script:Cashier POST "/billing/invoices/$($inv.publicId)/discount" @{
        percent = 10
        reason  = 'QA within limit'
    }
    Assert-True ((Dec $ok.Body.discountTotal) -eq (Dec 2)) "expected discount 2, got $($ok.Body.discountTotal)"
    Assert-True ((Dec $ok.Body.grandTotal) -eq (Dec 18)) "expected grand 18, got $($ok.Body.grandTotal)"

    $over = Invoke-Api $script:Cashier POST "/billing/invoices/$($inv.publicId)/discount" @{
        percent = 50
        reason  = 'QA above limit'
    } -AllowError
    Assert-Code $over 422 'DISCOUNT_LIMIT_EXCEEDED'

    $full = Invoke-Api $script:Fin POST "/billing/invoices/$($inv.publicId)/discount" @{
        percent = 50
        reason  = 'QA manager discount'
    }
    Assert-True ((Dec $full.Body.discountTotal) -eq (Dec 10)) "expected manager discount 10, got $($full.Body.discountTotal)"

    $audit = Invoke-Api $script:Admin GET "/admin/audit?action=INVOICE_DISCOUNTED&entityType=INVOICE&entityPublicId=$($inv.publicId)&size=50"
    Assert-True ($audit.Status -eq 200) "audit search failed: $($audit.Raw)"
    Assert-True (@($audit.Body.items).Count -ge 2) "expected 2 discount audit rows, got $(@($audit.Body.items).Count)"
    "discounts 2 (limited) and 10 (manager)"
}

Check 'TC-111 partial then full payment, receipt, overpay guard' {
    $b = New-Billable 'bil111'
    $inv = New-Invoice $script:Cashier $b.Patient $b.Encounter

    $p1 = Invoke-Api $script:Cashier POST "/billing/invoices/$($inv.publicId)/payments" @{
        amount         = 5
        method         = 'CASH'
        reference      = "QA5-$script:RunId-1"
        idempotencyKey = "qa5-111-a-$script:RunId"
    }
    Assert-True ($p1.Status -eq 201) "first payment failed: $($p1.Raw)"
    $after1 = Get-Invoice $script:Cashier $inv.publicId
    Assert-True ((Dec $after1.balance) -eq (Dec 15)) "expected balance 15, got $($after1.balance)"
    Assert-True ($after1.status -eq 'PARTIALLY_PAID') "expected PARTIALLY_PAID, got $($after1.status)"

    $receipt = Invoke-Api $script:Cashier GET "/billing/payments/$($p1.Body.publicId)/receipt"
    Assert-True ($receipt.Status -eq 200) "receipt failed: $($receipt.Raw)"
    Assert-True ((Dec $receipt.Body.amount) -eq (Dec 5)) "receipt amount wrong: $($receipt.Body.amount)"
    Assert-True ($receipt.Body.method -eq 'CASH') "receipt method wrong: $($receipt.Body.method)"
    Assert-True ([bool]$receipt.Body.receiptNumber) 'receipt number missing'
    Assert-True ([bool]$receipt.Body.receivedAt) 'receipt timestamp missing'

    $over = Invoke-Api $script:Cashier POST "/billing/invoices/$($inv.publicId)/payments" @{
        amount         = 25
        method         = 'CASH'
        idempotencyKey = "qa5-111-b-$script:RunId"
    } -AllowError
    Assert-Code $over 422 'PAYMENT_EXCEEDS_BALANCE'

    $p2 = Invoke-Api $script:Cashier POST "/billing/invoices/$($inv.publicId)/payments" @{
        amount         = 15
        method         = 'CASH'
        idempotencyKey = "qa5-111-c-$script:RunId"
    }
    Assert-True ($p2.Status -eq 201) "second payment failed: $($p2.Raw)"
    $after2 = Get-Invoice $script:Cashier $inv.publicId
    Assert-True ((Dec $after2.balance) -eq (Dec 0)) "expected balance 0, got $($after2.balance)"
    Assert-True ($after2.status -eq 'PAID') "expected PAID, got $($after2.status)"

    $extra = Invoke-Api $script:Cashier POST "/billing/invoices/$($inv.publicId)/payments" @{
        amount         = 1
        method         = 'CASH'
        idempotencyKey = "qa5-111-d-$script:RunId"
    } -AllowError
    Assert-Code $extra 422 'INVOICE_ALREADY_PAID'
    "receipt=$($receipt.Body.receiptNumber) paid=20"
}

Check 'TC-112 payment idempotency replay and key reuse conflict' {
    $b = New-Billable 'bil112'
    $inv = New-Invoice $script:Cashier $b.Patient $b.Encounter
    $key = "qa5-112-$script:RunId"
    $body = @{
        amount         = 5
        method         = 'CASH'
        reference      = 'QA5-IDEM'
        idempotencyKey = $key
    }
    $first = Invoke-Api $script:Cashier POST "/billing/invoices/$($inv.publicId)/payments" $body
    Assert-True ($first.Status -eq 201) "first payment failed: $($first.Raw)"
    Assert-True ($first.Body.replayed -eq $false) 'first response must not be marked replayed'

    $replay = Invoke-Api $script:Cashier POST "/billing/invoices/$($inv.publicId)/payments" $body
    Assert-True ($replay.Status -eq 201) "replay failed: $($replay.Raw)"
    Assert-True ($replay.Body.publicId -eq $first.Body.publicId) 'replay returned a different payment'
    Assert-True ($replay.Body.replayed -eq $true) 'replay must be marked replayed'

    $conflict = Invoke-Api $script:Cashier POST "/billing/invoices/$($inv.publicId)/payments" @{
        amount         = 6
        method         = 'CASH'
        reference      = 'QA5-IDEM'
        idempotencyKey = $key
    } -AllowError
    Assert-Code $conflict 409 'IDEMPOTENCY_KEY_REUSED'

    $rows = Db-Int "SELECT COUNT(*) FROM smart_hospital.payments WHERE idempotency_key='$key';"
    Assert-True ($rows -eq 1) "expected 1 payment row, got $rows"
    $keys = Db-Int "SELECT COUNT(*) FROM smart_hospital.idempotency_keys k WHERE k.key='$key' AND k.endpoint='billing.payment';"
    Assert-True ($keys -eq 1) "expected 1 idempotency row, got $keys"
    $after = Get-Invoice $script:Cashier $inv.publicId
    Assert-True ((Dec $after.paidTotal) -eq (Dec 5)) "expected paid 5, got $($after.paidTotal)"
    "key=$key single effect"
}

Check 'TC-113 refund requires approval, links to payment, respects amount' {
    $b = New-Billable 'bil113'
    $inv = New-Invoice $script:Cashier $b.Patient $b.Encounter
    $pay = Invoke-Api $script:Cashier POST "/billing/invoices/$($inv.publicId)/payments" @{
        amount         = 20
        method         = 'CASH'
        idempotencyKey = "qa5-113-pay-$script:RunId"
    }
    Assert-True ($pay.Status -eq 201) "payment failed: $($pay.Raw)"

    $noApproval = Invoke-Api $script:Clerk POST "/billing/payments/$($pay.Body.publicId)/refunds" @{
        amount = 5
        reason = 'QA refund without approver'
    } -AllowError
    Assert-Code $noApproval 403 'REFUND_APPROVAL_REQUIRED'

    $finUser = Invoke-Api $script:Admin GET '/admin/users?q=qa5.fin'
    Assert-True ($finUser.Status -eq 200) "user search failed: $($finUser.Raw)"
    $finRow = @($finUser.Body.items | Where-Object { $_.username -eq "qa5.fin.$script:RunId" })
    Assert-True ($finRow.Count -eq 1) 'finance user not found for approval'
    $finPublicId = $finRow[0].publicId

    $refund = Invoke-Api $script:Clerk POST "/billing/payments/$($pay.Body.publicId)/refunds" @{
        amount           = 5
        reason           = 'QA approved refund'
        approvedByPublicId = $finPublicId
    }
    Assert-True ($refund.Status -eq 201) "refund failed: $($refund.Raw)"
    Assert-True ((Dec $refund.Body.amount) -eq (Dec 5)) "refund amount wrong: $($refund.Body.amount)"
    Assert-True ($refund.Body.paymentPublicId -eq $pay.Body.publicId) 'refund is not linked to the payment'

    $tooMuch = Invoke-Api $script:Fin POST "/billing/payments/$($pay.Body.publicId)/refunds" @{
        amount = 20
        reason = 'QA refund over payment'
    } -AllowError
    Assert-Code $tooMuch 422 'REFUND_EXCEEDS_PAYMENT'

    $missing = Invoke-Api $script:Fin POST '/billing/payments/00000000000000000000000000/refunds' @{
        amount = 1
        reason = 'QA missing payment'
    } -AllowError
    Assert-Code $missing 404 'PAYMENT_NOT_FOUND'

    $after = Get-Invoice $script:Fin $inv.publicId
    Assert-True ((Dec $after.refundedTotal) -eq (Dec 5)) "expected refunded 5, got $($after.refundedTotal)"
    Assert-True ((Dec $after.balance) -eq (Dec 5)) "expected balance 5, got $($after.balance)"
    $audit = Invoke-Api $script:Admin GET "/admin/audit?action=PAYMENT_REFUNDED&entityType=PAYMENT&entityPublicId=$($pay.Body.publicId)"
    Assert-True (@($audit.Body.items).Count -ge 1) 'refund audit row missing'
    "refund=$($refund.Body.refundNumber) approvedBy=$($refund.Body.approvedBy)"
}

Check 'TC-114 void without movements, blocked void, credit note instead' {
    $b1 = New-Billable 'bil114a'
    $invA = New-Invoice $script:Cashier $b1.Patient $b1.Encounter
    $voidA = Invoke-Api $script:Fin POST "/billing/invoices/$($invA.publicId)/void" @{ reason = 'QA void clean invoice' }
    Assert-True ($voidA.Status -eq 200) "void failed: $($voidA.Raw)"
    Assert-True ($voidA.Body.status -eq 'VOID') "expected VOID, got $($voidA.Body.status)"
    $voidAgain = Invoke-Api $script:Fin POST "/billing/invoices/$($invA.publicId)/void" @{ reason = 'QA void twice' } -AllowError
    Assert-Code $voidAgain 409 'INVOICE_ALREADY_VOID'

    $b2 = New-Billable 'bil114b'
    $invB = New-Invoice $script:Cashier $b2.Patient $b2.Encounter
    $pay = Invoke-Api $script:Cashier POST "/billing/invoices/$($invB.publicId)/payments" @{
        amount         = 5
        method         = 'CASH'
        idempotencyKey = "qa5-114-$script:RunId"
    }
    Assert-True ($pay.Status -eq 201) "payment failed: $($pay.Raw)"
    $voidB = Invoke-Api $script:Fin POST "/billing/invoices/$($invB.publicId)/void" @{ reason = 'QA void paid invoice' } -AllowError
    Assert-Code $voidB 409 'INVOICE_HAS_PAYMENTS'
    $audit = Invoke-Api $script:Admin GET "/admin/audit?action=INVOICE_VOID_REJECTED&entityType=INVOICE&entityPublicId=$($invB.publicId)"
    Assert-True (@($audit.Body.items).Count -ge 1) 'void rejection audit row missing'

    $credit = Invoke-Api $script:Fin POST "/billing/invoices/$($invB.publicId)/credit-notes" @{
        amount = 20
        reason = 'QA credit note for paid invoice'
    }
    Assert-True ($credit.Status -eq 201) "credit note failed: $($credit.Raw)"
    Assert-True ($credit.Body.invoiceStatus -eq 'CREDITED') "expected CREDITED, got $($credit.Body.invoiceStatus)"
    $after = Get-Invoice $script:Fin $invB.publicId
    Assert-True ((Dec $after.creditedTotal) -eq (Dec 20)) "expected credited 20, got $($after.creditedTotal)"
    Assert-True ((Dec $after.balance) -eq (Dec 0)) "expected balance 0, got $($after.balance)"

    $tooMuch = Invoke-Api $script:Fin POST "/billing/invoices/$($invB.publicId)/credit-notes" @{
        amount = 1
        reason = 'QA credit over invoice'
    } -AllowError
    Assert-Code $tooMuch 422 'CREDIT_EXCEEDS_INVOICE'
    "void=$($invA.invoiceNumber) credit=$($credit.Body.creditNumber)"
}

Check 'TC-115 balance formula after payment, refund and credit' {
    $b = New-Billable 'bil115'
    $inv = New-Invoice $script:Fin $b.Patient $b.Encounter
    $pay = Invoke-Api $script:Fin POST "/billing/invoices/$($inv.publicId)/payments" @{
        amount         = 8
        method         = 'CARD'
        idempotencyKey = "qa5-115-pay-$script:RunId"
    }
    Assert-True ($pay.Status -eq 201) "payment failed: $($pay.Raw)"
    $refund = Invoke-Api $script:Fin POST "/billing/payments/$($pay.Body.publicId)/refunds" @{
        amount = 3
        reason = 'QA partial refund'
    }
    Assert-True ($refund.Status -eq 201) "refund failed: $($refund.Raw)"
    $credit = Invoke-Api $script:Fin POST "/billing/invoices/$($inv.publicId)/credit-notes" @{
        amount = 2
        reason = 'QA partial credit'
    }
    Assert-True ($credit.Status -eq 201) "credit failed: $($credit.Raw)"

    $after = Get-Invoice $script:Fin $inv.publicId
    $expected = (Dec $after.grandTotal) - (Dec $after.creditedTotal) - (Dec $after.paidTotal) + (Dec $after.refundedTotal)
    Assert-True ((Dec $after.balance) -eq (Dec 13)) "expected balance 13, got $($after.balance)"
    Assert-True ((Dec $after.balance) -eq $expected) "balance $($after.balance) does not match formula $expected"
    $dbBalance = Dec (Db-Scalar "SELECT balance FROM smart_hospital.invoices WHERE public_id='$($inv.publicId)';")
    Assert-True ($dbBalance -eq (Dec 13)) "stored balance wrong: $dbBalance"

    $settle = Invoke-Api $script:Fin POST "/billing/invoices/$($inv.publicId)/payments" @{
        amount         = 13
        method         = 'CARD'
        idempotencyKey = "qa5-115-settle-$script:RunId"
    }
    Assert-True ($settle.Status -eq 201) "settle failed: $($settle.Raw)"
    $settled = Get-Invoice $script:Fin $inv.publicId
    Assert-True ((Dec $settled.balance) -eq (Dec 0)) "expected 0, got $($settled.balance)"
    $negatives = Db-Int "SELECT COUNT(*) FROM smart_hospital.invoices WHERE balance < 0;"
    Assert-True ($negatives -eq 0) "found $negatives negative balances"
    "20 - 2 - 8 + 3 = 13, settled"
}

Check 'TC-116 daily collection report and cash session close' {
    $session = Invoke-Api $script:Cashier POST '/billing/cash-sessions' @{ openingFloat = 100 }
    Assert-True ($session.Status -eq 201) "open session failed: $($session.Raw)"
    Assert-True ($session.Body.status -eq 'OPEN') "expected OPEN, got $($session.Body.status)"
    $sessionId = $session.Body.publicId

    $b = New-Billable 'bil116'
    $inv = New-Invoice $script:Cashier $b.Patient $b.Encounter
    $pay = Invoke-Api $script:Cashier POST "/billing/invoices/$($inv.publicId)/payments" @{
        amount         = 10
        method         = 'CASH'
        idempotencyKey = "qa5-116-$script:RunId"
    }
    Assert-True ($pay.Status -eq 201) "payment failed: $($pay.Raw)"
    Assert-True ($pay.Body.cashSessionPublicId -eq $sessionId) 'payment not linked to the open session'

    $today = Get-Date -Format 'yyyy-MM-dd'
    $report = Invoke-Api $script:Fin GET "/billing/reports/daily?date=$today"
    Assert-True ($report.Status -eq 200) "report failed: $($report.Raw)"
    Assert-True ((Dec $report.Body.collected) -ge (Dec 10)) "expected collected >= 10, got $($report.Body.collected)"
    $cashRow = @($report.Body.byMethod | Where-Object { $_.method -eq 'CASH' })
    Assert-True ($cashRow.Count -eq 1) 'CASH collection row missing'
    Assert-True ((Dec $cashRow[0].collected) -ge (Dec 10)) "CASH collected wrong: $($cashRow[0].collected)"

    $closed = Invoke-Api $script:Cashier POST "/billing/cash-sessions/$sessionId/close" @{ countedTotal = 110 }
    Assert-True ($closed.Status -eq 200) "close failed: $($closed.Raw)"
    Assert-True ($closed.Body.status -eq 'CLOSED') "expected CLOSED, got $($closed.Body.status)"
    Assert-True ((Dec $closed.Body.expectedTotal) -eq (Dec 110)) "expected 110, got $($closed.Body.expectedTotal)"
    Assert-True ((Dec $closed.Body.variance) -eq (Dec 0)) "expected variance 0, got $($closed.Body.variance)"
    $active = Db-Scalar "SELECT COALESCE(active_flag,'NULL') FROM smart_hospital.cash_sessions WHERE public_id='$sessionId';"
    Assert-True ($active -eq 'NULL') "active_flag should be NULL, got $active"

    $second = Invoke-Api $script:Cashier POST "/billing/cash-sessions/$sessionId/close" @{ countedTotal = 110 } -AllowError
    Assert-Code $second 409 'CASH_SESSION_CLOSED'

    $closedInvoice = Get-Invoice $script:Cashier $inv.publicId
    Assert-True ($closedInvoice.closed -eq $true) 'invoice should be closed with the session'
    $script:ClosedInvoice = $inv.publicId
    "session=$($session.Body.sessionNumber) expected=110"
}

Check 'TC-117 edits after close rejected and audited' {
    $id = $script:ClosedInvoice
    $discount = Invoke-Api $script:Fin POST "/billing/invoices/$id/discount" @{ percent = 5; reason = 'QA post close discount' } -AllowError
    Assert-Code $discount 409 'INVOICE_CLOSED'
    $payment = Invoke-Api $script:Fin POST "/billing/invoices/$id/payments" @{
        amount = 1
        method = 'CASH'
        idempotencyKey = "qa5-117-$script:RunId"
    } -AllowError
    Assert-Code $payment 409 'INVOICE_CLOSED'
    $void = Invoke-Api $script:Fin POST "/billing/invoices/$id/void" @{ reason = 'QA post close void' } -AllowError
    Assert-Code $void 409 'INVOICE_CLOSED'
    $credit = Invoke-Api $script:Fin POST "/billing/invoices/$id/credit-notes" @{ amount = 1; reason = 'QA post close credit' } -AllowError
    Assert-Code $credit 409 'INVOICE_CLOSED'

    $audit = Invoke-Api $script:Admin GET "/admin/audit?action=INVOICE_EDIT_REJECTED&entityType=INVOICE&entityPublicId=$id&size=50"
    Assert-True ($audit.Status -eq 200) "audit search failed: $($audit.Raw)"
    Assert-True (@($audit.Body.items).Count -ge 4) "expected >= 4 rejection rows, got $(@($audit.Body.items).Count)"
    $paid = Dec (Db-Scalar "SELECT paid_total FROM smart_hospital.invoices WHERE public_id='$id';")
    Assert-True ($paid -eq (Dec 10)) "paid total changed after close: $paid"
    "rejections audited = $(@($audit.Body.items).Count)"
}

# ---------------------------------------------------------------- inventory

Section 'INV - items, warehouses, stock (TC-118..TC-127)'

Check 'TC-118 create item with unit/category, reject duplicate SKU and missing unit' {
    $code = "QAIT$script:RunId"
    $r = Invoke-Api $script:Store POST '/inventory/items' @{
        code        = $code
        sku         = "QASKU$script:RunId"
        nameAr      = 'صنف اختبار'
        nameEn      = 'QA test item'
        category    = 'SUPPLY'
        baseUnit    = 'BOX'
        trackExpiry = $true
        unitCost    = 2.5
        barcode     = "QABC$script:RunId"
    }
    Assert-True ($r.Status -eq 201) "item create failed: $($r.Raw)"
    Assert-True ($r.Body.baseUnit -eq 'BOX') "base unit wrong: $($r.Body.baseUnit)"
    Assert-True ($r.Body.category -eq 'SUPPLY') "category wrong: $($r.Body.category)"
    $script:QaItem = $r.Body.publicId
    $script:QaItemCode = $code

    $dupSku = Invoke-Api $script:Store POST '/inventory/items' @{
        code = "${code}X"; sku = "QASKU$script:RunId"; nameAr = 'تكرار'; baseUnit = 'BOX'
    } -AllowError
    Assert-Code $dupSku 409 'SKU_EXISTS'

    $dupCode = Invoke-Api $script:Store POST '/inventory/items' @{
        code = $code; sku = "QASKU${script:RunId}X"; nameAr = 'تكرار'; baseUnit = 'BOX'
    } -AllowError
    Assert-Code $dupCode 409 'ITEM_CODE_EXISTS'

    $noUnit = Invoke-Api $script:Store POST '/inventory/items' @{
        code = "${code}Y"; sku = "QASKU${script:RunId}Y"; nameAr = 'بلا وحدة'
    } -AllowError
    Assert-Code $noUnit 400 'VALIDATION_FAILED'

    $detail = Get-ItemDetail $script:QaItem
    Assert-True ($detail.code -eq $code) "item read mismatch: $($detail.code)"
    "item=$code"
}

Check 'TC-119 create warehouse/supplier, deactivate empty then with stock' {
    $wcode = "QAWH$script:RunId"
    $w = Invoke-Api $script:Store POST '/inventory/warehouses' @{
        code          = $wcode
        nameAr        = 'مخزن اختبار'
        nameEn        = 'QA warehouse'
        warehouseType = 'OTHER'
        location      = 'QA block'
    }
    Assert-True ($w.Status -eq 201) "warehouse create failed: $($w.Raw)"
    $script:QaWarehouse = $w.Body.publicId

    $scode = "QASP$script:RunId"
    $s = Invoke-Api $script:Store POST '/inventory/suppliers' @{
        code            = $scode
        nameAr          = 'مورد اختبار'
        nameEn          = 'QA supplier'
        phone           = '0000000000'
        contactPerson   = 'QA'
    }
    Assert-True ($s.Status -eq 201) "supplier create failed: $($s.Raw)"
    $script:QaSupplier = $s.Body.publicId

    $dupW = Invoke-Api $script:Store POST '/inventory/warehouses' @{ code = $wcode; nameAr = 'تكرار' } -AllowError
    Assert-Code $dupW 409 'WAREHOUSE_CODE_EXISTS'
    $dupS = Invoke-Api $script:Store POST '/inventory/suppliers' @{ code = $scode; nameAr = 'تكرار' } -AllowError
    Assert-Code $dupS 409 'SUPPLIER_CODE_EXISTS'

    $off = Invoke-Api $script:Store PATCH "/inventory/warehouses/$script:QaWarehouse" @{ active = $false }
    Assert-True ($off.Status -eq 200) "deactivate failed: $($off.Raw)"
    Assert-True ($off.Body.active -eq $false) 'warehouse should be inactive'
    $on = Invoke-Api $script:Store PATCH "/inventory/warehouses/$script:QaWarehouse" @{ active = $true }
    Assert-True ($on.Body.active -eq $true) 'warehouse should be active again'

    [void](Receive-Batch $script:Store $script:QaItem $script:QaWarehouse "QA-B1-$script:RunId" 50 2.5 ((Get-Date).AddDays(365).ToString('yyyy-MM-dd')) $script:QaSupplier)
    $blocked = Invoke-Api $script:Store PATCH "/inventory/warehouses/$script:QaWarehouse" @{ active = $false } -AllowError
    Assert-Code $blocked 409 'WAREHOUSE_HAS_STOCK'
    $rows = Db-Int "SELECT COUNT(*) FROM smart_hospital.warehouses WHERE public_id='$script:QaWarehouse';"
    Assert-True ($rows -eq 1) 'warehouse row missing (physical delete?)'
    "warehouse=$wcode supplier=$scode"
}

Check 'TC-120 receive batch with expiry, increments, rejects missing/expired' {
    $b1 = Find-Batch $script:QaItem $script:QaWarehouse "QA-B1-$script:RunId"
    Assert-True ((Dec $b1.quantityAvailable) -eq (Dec 50)) "expected 50, got $($b1.quantityAvailable)"
    Assert-True ($b1.expiryDate -eq (Get-Date).AddDays(365).ToString('yyyy-MM-dd')) "expiry wrong: $($b1.expiryDate)"

    $noExpiry = Invoke-Api $script:Store POST '/inventory/batches' @{
        itemPublicId = $script:QaItem; warehousePublicId = $script:QaWarehouse
        batchNumber = "QA-B-NOEXP-$script:RunId"; quantity = 5; unitCost = 2.5
        supplierPublicId = $script:QaSupplier
    } -AllowError
    Assert-Code $noExpiry 400 'EXPIRY_REQUIRED'

    $expired = Invoke-Api $script:Store POST '/inventory/batches' @{
        itemPublicId = $script:QaItem; warehousePublicId = $script:QaWarehouse
        batchNumber = "QA-B-EXP-$script:RunId"; quantity = 5; unitCost = 2.5
        supplierPublicId = $script:QaSupplier
        expiryDate = (Get-Date).AddDays(-1).ToString('yyyy-MM-dd')
    } -AllowError
    Assert-Code $expired 422 'BATCH_EXPIRED'

    $more = Receive-Batch $script:Store $script:QaItem $script:QaWarehouse "QA-B1-$script:RunId" 10 2.5 ((Get-Date).AddDays(365).ToString('yyyy-MM-dd')) $script:QaSupplier
    Assert-True ($more.Status -eq 201) "increment receipt failed: $($more.Raw)"
    Assert-True ((Dec $more.Body.quantityAvailable) -eq (Dec 60)) "expected 60, got $($more.Body.quantityAvailable)"
    $batchRows = Db-Int "SELECT COUNT(*) FROM smart_hospital.inventory_batches b JOIN smart_hospital.inventory_items i ON i.id=b.item_id WHERE i.public_id='$script:QaItem' AND b.batch_number='QA-B1-$script:RunId';"
    Assert-True ($batchRows -eq 1) "expected 1 batch row, got $batchRows"
    $movements = Db-Int "SELECT COUNT(*) FROM smart_hospital.inventory_movements m JOIN smart_hospital.inventory_items i ON i.id=m.item_id WHERE i.public_id='$script:QaItem' AND m.movement_type='RECEIPT';"
    Assert-True ($movements -eq 2) "expected 2 receipt movements, got $movements"
    $script:QaBatch = $more.Body.publicId
    "batch qty=60"
}

Check 'TC-121 issue and transfer require a document and move stock' {
    $before = [long](Db-Scalar "SELECT COALESCE(SUM(quantity_available),0) FROM smart_hospital.inventory_batches b JOIN smart_hospital.inventory_items i ON i.id=b.item_id WHERE i.public_id='$script:QaItem' AND b.warehouse_id=(SELECT id FROM smart_hospital.warehouses WHERE public_id='$script:QaWarehouse');")
    Assert-True ($before -eq 60) "expected 60 before issue, got $before"

    $noDoc = Invoke-Api $script:Store POST '/inventory/issues' @{
        items = @(@{ itemPublicId = $script:QaItem; warehousePublicId = $script:QaWarehouse; quantity = 5 })
    } -AllowError
    Assert-Code $noDoc 400 'DOCUMENT_REQUIRED'

    $issue = Issue-Stock $script:Store $script:QaWarehouse @(@{ Item = $script:QaItem; Quantity = 5 }) "QA-DOC-$script:RunId" 'QA issue'
    Assert-True ($issue.Status -eq 201) "issue failed: $($issue.Raw)"
    Assert-True ((Dec $issue.Body.totalValue) -eq (Dec 12.5)) "expected value 12.5, got $($issue.Body.totalValue)"
    $afterIssue = [long](Db-Scalar "SELECT COALESCE(SUM(quantity_available),0) FROM smart_hospital.inventory_batches b JOIN smart_hospital.inventory_items i ON i.id=b.item_id WHERE i.public_id='$script:QaItem' AND b.warehouse_id=(SELECT id FROM smart_hospital.warehouses WHERE public_id='$script:QaWarehouse');")
    Assert-True ($afterIssue -eq 55) "expected 55 after issue, got $afterIssue"

    $main = Find-Warehouse 'MAIN'
    $recvMain = Receive-Batch $script:Store $script:QaItem $main.publicId "QA-BM-$script:RunId" 20 2.5 ((Get-Date).AddDays(200).ToString('yyyy-MM-dd')) $script:QaSupplier
    Assert-True ($recvMain.Status -eq 201) "main receipt failed: $($recvMain.Raw)"

    $noDocT = Invoke-Api $script:Store POST '/inventory/transfers' @{
        fromWarehousePublicId = $main.publicId
        toWarehousePublicId   = $script:QaWarehouse
        items                 = @(@{ itemPublicId = $script:QaItem; quantity = 10 })
    } -AllowError
    Assert-Code $noDocT 400 'DOCUMENT_REQUIRED'

    $same = Invoke-Api $script:Store POST '/inventory/transfers' @{
        fromWarehousePublicId = $main.publicId
        toWarehousePublicId   = $main.publicId
        documentRef           = "QA-TRF-SAME-$script:RunId"
        items                 = @(@{ itemPublicId = $script:QaItem; quantity = 1 })
    } -AllowError
    Assert-Code $same 400 'VALIDATION_FAILED'

    $transfer = Transfer-Stock $script:Store $main.publicId $script:QaWarehouse $script:QaItem 10 "QA-TRF-$script:RunId"
    Assert-True ($transfer.Status -eq 201) "transfer failed: $($transfer.Raw)"
    Assert-True ($transfer.Body.documentRef -eq "QA-TRF-$script:RunId") "document ref wrong: $($transfer.Body.documentRef)"
    $mainQty = [long](Db-Scalar "SELECT COALESCE(SUM(quantity_available),0) FROM smart_hospital.inventory_batches b JOIN smart_hospital.inventory_items i ON i.id=b.item_id WHERE i.public_id='$script:QaItem' AND b.warehouse_id=(SELECT id FROM smart_hospital.warehouses WHERE public_id='$($main.publicId)');")
    $qaQty = [long](Db-Scalar "SELECT COALESCE(SUM(quantity_available),0) FROM smart_hospital.inventory_batches b JOIN smart_hospital.inventory_items i ON i.id=b.item_id WHERE i.public_id='$script:QaItem' AND b.warehouse_id=(SELECT id FROM smart_hospital.warehouses WHERE public_id='$script:QaWarehouse');")
    Assert-True ($mainQty -eq 10) "expected MAIN 10, got $mainQty"
    Assert-True ($qaQty -eq 65) "expected QA 65, got $qaQty"
    $transferRows = Db-Int "SELECT COUNT(*) FROM smart_hospital.inventory_movements m JOIN smart_hospital.inventory_items i ON i.id=m.item_id WHERE i.public_id='$script:QaItem' AND m.movement_type='TRANSFER_IN' AND m.reference_public_id='$($transfer.Body.publicId)';"
    Assert-True ($transferRows -eq 1) "expected 1 TRANSFER_IN, got $transferRows"
    "issued 5, transferred 10, source=10 dest=65"
}

Check 'TC-122 over-issue rejected with no negative and no partial movement' {
    $before = [long](Db-Scalar "SELECT COALESCE(SUM(quantity_available),0) FROM smart_hospital.inventory_batches b JOIN smart_hospital.inventory_items i ON i.id=b.item_id WHERE i.public_id='$script:QaItem' AND b.warehouse_id=(SELECT id FROM smart_hospital.warehouses WHERE public_id='$script:QaWarehouse');")
    $movBefore = Db-Int "SELECT COUNT(*) FROM smart_hospital.inventory_movements m JOIN smart_hospital.inventory_items i ON i.id=m.item_id WHERE i.public_id='$script:QaItem';"

    $over = Invoke-Api $script:Store POST '/inventory/issues' @{
        documentRef = "QA-OVER-$script:RunId"
        items = @(@{ itemPublicId = $script:QaItem; warehousePublicId = $script:QaWarehouse; quantity = 999999 })
    } -AllowError
    Assert-Code $over 422 'INSUFFICIENT_STOCK'

    $mixed = Invoke-Api $script:Store POST '/inventory/issues' @{
        documentRef = "QA-MIXED-$script:RunId"
        items = @(
            @{ itemPublicId = $script:QaItem; warehousePublicId = $script:QaWarehouse; quantity = 5 },
            @{ itemPublicId = $script:QaItem; warehousePublicId = $script:QaWarehouse; quantity = 999999 }
        )
    } -AllowError
    Assert-Code $mixed 422 'INSUFFICIENT_STOCK'

    $after = [long](Db-Scalar "SELECT COALESCE(SUM(quantity_available),0) FROM smart_hospital.inventory_batches b JOIN smart_hospital.inventory_items i ON i.id=b.item_id WHERE i.public_id='$script:QaItem' AND b.warehouse_id=(SELECT id FROM smart_hospital.warehouses WHERE public_id='$script:QaWarehouse');")
    $movAfter = Db-Int "SELECT COUNT(*) FROM smart_hospital.inventory_movements m JOIN smart_hospital.inventory_items i ON i.id=m.item_id WHERE i.public_id='$script:QaItem';"
    Assert-True ($after -eq $before) "stock changed from $before to $after after failed issue"
    Assert-True ($movAfter -eq $movBefore) "movements changed from $movBefore to $movAfter after failed issue"
    $negatives = Db-Int "SELECT COUNT(*) FROM smart_hospital.inventory_batches WHERE quantity_available < 0;"
    Assert-True ($negatives -eq 0) "found $negatives negative batch quantities"
    "no partial movement, stock=$after"
}

Check 'TC-123 count snapshot, variance reason and approval' {
    $count = Invoke-Api $script:Store POST '/inventory/counts' @{ warehousePublicId = $script:QaWarehouse }
    Assert-True ($count.Status -eq 201) "count create failed: $($count.Raw)"
    Assert-True ($count.Body.status -eq 'COUNTING') "expected COUNTING, got $($count.Body.status)"
    Assert-True (@($count.Body.lines).Count -ge 1) 'count has no lines'
    $countId = $count.Body.publicId
    $line = @($count.Body.lines | Where-Object { $_.batchNumber -eq "QA-B1-$script:RunId" })
    Assert-True ($line.Count -eq 1) 'expected the QA batch line in the count'
    $lineId = $line[0].publicId
    $systemQty = [int]$line[0].systemQuantity
    Assert-True ($systemQty -gt 3) "system quantity too small: $systemQty"

    $patched = Invoke-Api $script:Store PATCH "/inventory/counts/$countId/lines/$lineId" @{ countedQuantity = ($systemQty - 3) }
    Assert-True ($patched.Status -eq 200) "count line patch failed: $($patched.Raw)"
    $patchedLine = @($patched.Body.lines | Where-Object { $_.publicId -eq $lineId })[0]
    Assert-True ([int]$patchedLine.variance -eq -3) "expected variance -3, got $($patchedLine.variance)"

    $noReason = Invoke-Api $script:Store POST "/inventory/counts/$countId/post" @{} -AllowError
    Assert-Code $noReason 400 'REASON_REQUIRED'

    $noApprover = Invoke-Api $script:Counter POST "/inventory/counts/$countId/post" @{ reason = 'QA variance' } -AllowError
    Assert-Code $noApprover 403 'ADJUST_APPROVAL_REQUIRED'

    $storeUser = Invoke-Api $script:Admin GET '/admin/users?q=qa5.store'
    Assert-True ($storeUser.Status -eq 200) "user search failed: $($storeUser.Raw)"
    $storeRow = @($storeUser.Body.items | Where-Object { $_.username -eq "qa5.store.$script:RunId" })
    Assert-True ($storeRow.Count -eq 1) 'storekeeper not found for approval'

    $posted = Invoke-Api $script:Counter POST "/inventory/counts/$countId/post" @{
        reason             = 'QA variance approved'
        approvedByPublicId = $storeRow[0].publicId
    }
    Assert-True ($posted.Status -eq 200) "count post failed: $($posted.Raw)"
    Assert-True ($posted.Body.status -eq 'POSTED') "expected POSTED, got $($posted.Body.status)"

    $batchQty = Batch-Quantity $script:QaBatch
    Assert-True ($batchQty -eq ($systemQty - 3)) "expected batch $($systemQty - 3), got $batchQty"
    $adjust = Db-Int "SELECT COUNT(*) FROM smart_hospital.inventory_movements WHERE movement_type='ADJUSTMENT' AND reference_public_id='$countId';"
    Assert-True ($adjust -eq 1) "expected 1 ADJUSTMENT movement, got $adjust"

    $again = Invoke-Api $script:Store POST "/inventory/counts/$countId/post" @{ reason = 'QA again' } -AllowError
    Assert-Code $again 409 'COUNT_NOT_OPEN'
    $latePatch = Invoke-Api $script:Store PATCH "/inventory/counts/$countId/lines/$lineId" @{ countedQuantity = 1 } -AllowError
    Assert-Code $latePatch 409 'COUNT_NOT_OPEN'
    "count=$($count.Body.countNumber) variance=-3"
}

Check 'TC-124 reorder alert appears and clears with stock movement' {
    $rp = "INSERT INTO smart_hospital.inventory_reorder_points (public_id, item_id, warehouse_id, reorder_level, reorder_quantity)
SELECT LPAD(CONCAT('QARP','$script:RunId'),26,'0'), i.id, w.id, 70, 50
FROM smart_hospital.inventory_items i, smart_hospital.warehouses w
WHERE i.public_id='$script:QaItem' AND w.public_id='$script:QaWarehouse'
  AND NOT EXISTS (SELECT 1 FROM smart_hospital.inventory_reorder_points rp WHERE rp.item_id=i.id AND rp.warehouse_id=w.id);"
    [void](Invoke-Db $rp)

    $alerts = Invoke-Api $script:Store GET '/inventory/alerts'
    Assert-True ($alerts.Status -eq 200) "alerts failed: $($alerts.Raw)"
    $low = @($alerts.Body.lowStock | Where-Object { $_.itemPublicId -eq $script:QaItem -and $_.warehousePublicId -eq $script:QaWarehouse })
    Assert-True ($low.Count -eq 1) "expected 1 low-stock row, got $($low.Count)"
    Assert-True ([int]$low[0].reorderLevel -eq 70) "reorder level wrong: $($low[0].reorderLevel)"
    Assert-True ([int]$low[0].available -lt 70) "available should be below 70: $($low[0].available)"

    $before = [long](Db-Scalar "SELECT COALESCE(SUM(quantity_available),0) FROM smart_hospital.inventory_batches b JOIN smart_hospital.inventory_items i ON i.id=b.item_id WHERE i.public_id='$script:QaItem' AND b.warehouse_id=(SELECT id FROM smart_hospital.warehouses WHERE public_id='$script:QaWarehouse');")
    $topUp = Receive-Batch $script:Store $script:QaItem $script:QaWarehouse "QA-BTOP-$script:RunId" ([int](70 - $before + 20)) 2.5 ((Get-Date).AddDays(300).ToString('yyyy-MM-dd')) $script:QaSupplier
    Assert-True ($topUp.Status -eq 201) "top-up receipt failed: $($topUp.Raw)"
    $alerts2 = Invoke-Api $script:Store GET '/inventory/alerts'
    $low2 = @($alerts2.Body.lowStock | Where-Object { $_.itemPublicId -eq $script:QaItem -and $_.warehousePublicId -eq $script:QaWarehouse })
    Assert-True ($low2.Count -eq 0) 'low-stock alert should clear after restock'

    $soon = Invoke-Api $script:Store POST '/inventory/batches' @{
        itemPublicId = $script:QaItem; warehousePublicId = $script:QaWarehouse
        batchNumber = "QA-BEXP-$script:RunId"; quantity = 4; unitCost = 2.5
        supplierPublicId = $script:QaSupplier
        expiryDate = (Get-Date).AddDays(10).ToString('yyyy-MM-dd')
    }
    Assert-True ($soon.Status -eq 201) "expiring batch create failed: $($soon.Raw)"
    $alerts3 = Invoke-Api $script:Store GET '/inventory/alerts'
    $exp = @($alerts3.Body.expiring | Where-Object { $_.batchPublicId -eq $soon.Body.publicId })
    Assert-True ($exp.Count -eq 1) "expected expiring alert, got $($exp.Count)"
    Assert-True ([int]$exp[0].daysLeft -le 60) "daysLeft wrong: $($exp[0].daysLeft)"
    "reorder level 70 -> cleared, expiring batch flagged"
}

Check 'TC-125 purchase request to order to receipt with policy' {
    $req = Invoke-Api $script:Store POST '/inventory/purchase-requests' @{
        warehousePublicId = $script:QaWarehouse
        status            = 'SUBMITTED'
        notes             = 'QA phase5 request'
        items             = @(@{ itemPublicId = $script:QaItem; quantity = 30 })
    }
    Assert-True ($req.Status -eq 201) "PR create failed: $($req.Raw)"
    Assert-True ($req.Body.status -eq 'SUBMITTED') "expected SUBMITTED, got $($req.Body.status)"

    $unapprovedPo = Invoke-Api $script:Store POST '/inventory/purchase-orders' @{
        supplierPublicId        = $script:QaSupplier
        warehousePublicId       = $script:QaWarehouse
        purchaseRequestPublicId = $req.Body.publicId
        items                   = @(@{ itemPublicId = $script:QaItem; quantity = 30 })
    } -AllowError
    Assert-Code $unapprovedPo 409 'REQUEST_NOT_APPROVED'

    $approve = Invoke-Api $script:Store POST "/inventory/purchase-requests/$($req.Body.publicId)/approve" @{ decision = 'APPROVE'; notes = 'QA approve' }
    Assert-True ($approve.Status -eq 200) "approve failed: $($approve.Raw)"
    Assert-True ($approve.Body.status -eq 'APPROVED') "expected APPROVED, got $($approve.Body.status)"
    $approveAgain = Invoke-Api $script:Store POST "/inventory/purchase-requests/$($req.Body.publicId)/approve" @{ decision = 'APPROVE' } -AllowError
    Assert-Code $approveAgain 409 'REQUEST_NOT_SUBMITTED'

    $noRequest = Invoke-Api $script:Store POST '/inventory/purchase-orders' @{
        supplierPublicId  = $script:QaSupplier
        warehousePublicId = $script:QaWarehouse
        items             = @(@{ itemPublicId = $script:QaItem; quantity = 5 })
    } -AllowError
    Assert-Code $noRequest 422 'PURCHASE_REQUEST_REQUIRED'

    $po = Invoke-Api $script:Store POST '/inventory/purchase-orders' @{
        supplierPublicId        = $script:QaSupplier
        warehousePublicId       = $script:QaWarehouse
        purchaseRequestPublicId = $req.Body.publicId
        notes                   = 'QA phase5 order'
        items                   = @(@{ itemPublicId = $script:QaItem; quantity = 30 })
    }
    Assert-True ($po.Status -eq 201) "PO create failed: $($po.Raw)"
    Assert-True ($po.Body.status -eq 'ORDERED') "expected ORDERED, got $($po.Body.status)"
    Assert-True (@($po.Body.items).Count -eq 1) "expected 1 PO line, got $(@($po.Body.items).Count)"
    $reqAfter = @((Invoke-Api $script:Store GET '/inventory/purchase-requests?size=100').Body.items | Where-Object { $_.publicId -eq $req.Body.publicId })
    Assert-True ($reqAfter.Count -eq 1) 'converted request not listed'
    Assert-True ($reqAfter[0].status -eq 'CONVERTED') "expected CONVERTED, got $($reqAfter[0].status)"

    $overReceipt = Invoke-Api $script:Store POST "/inventory/purchase-orders/$($po.Body.publicId)/receive" @{
        items = @(@{ itemPublicId = $script:QaItem; quantity = 40; expiryDate = (Get-Date).AddDays(300).ToString('yyyy-MM-dd') })
    } -AllowError
    Assert-Code $overReceipt 422 'OVER_RECEIPT'

    $before = [long](Db-Scalar "SELECT COALESCE(SUM(quantity_available),0) FROM smart_hospital.inventory_batches b JOIN smart_hospital.inventory_items i ON i.id=b.item_id WHERE i.public_id='$script:QaItem' AND b.warehouse_id=(SELECT id FROM smart_hospital.warehouses WHERE public_id='$script:QaWarehouse');")
    $receipt = Invoke-Api $script:Store POST "/inventory/purchase-orders/$($po.Body.publicId)/receive" @{
        items = @(@{ itemPublicId = $script:QaItem; quantity = 30; expiryDate = (Get-Date).AddDays(300).ToString('yyyy-MM-dd'); batchNumber = "QA-BPO-$script:RunId" })
    }
    Assert-True ($receipt.Status -eq 200) "receipt failed: $($receipt.Raw)"
    Assert-True ($receipt.Body.status -eq 'RECEIVED') "expected RECEIVED, got $($receipt.Body.status)"
    Assert-True ([bool]$receipt.Body.receipt.grnNumber) 'GRN number missing'
    $after = [long](Db-Scalar "SELECT COALESCE(SUM(quantity_available),0) FROM smart_hospital.inventory_batches b JOIN smart_hospital.inventory_items i ON i.id=b.item_id WHERE i.public_id='$script:QaItem' AND b.warehouse_id=(SELECT id FROM smart_hospital.warehouses WHERE public_id='$script:QaWarehouse');")
    Assert-True ($after -eq ($before + 30)) "expected $($before + 30) after receipt, got $after"

    $again = Invoke-Api $script:Store POST "/inventory/purchase-orders/$($po.Body.publicId)/receive" @{
        items = @(@{ itemPublicId = $script:QaItem; quantity = 1 })
    } -AllowError
    Assert-Code $again 409 'PO_NOT_RECEIVABLE'

    $req2 = Invoke-Api $script:Store POST '/inventory/purchase-requests' @{
        warehousePublicId = $script:QaWarehouse
        items             = @(@{ itemPublicId = $script:QaItem; quantity = 5 })
    }
    Assert-True ($req2.Status -eq 201) "second PR failed: $($req2.Raw)"
    $approve = Invoke-Api $script:Store POST "/inventory/purchase-requests/$($req2.Body.publicId)/approve" @{ decision = 'APPROVE'; notes = 'QA approve' }
    Assert-True ($approve.Status -eq 200) "approve failed: $($approve.Raw)"
    Assert-True ($approve.Body.status -eq 'APPROVED') "expected APPROVED, got $($approve.Body.status)"
    $approveAgain = Invoke-Api $script:Store POST "/inventory/purchase-requests/$($req2.Body.publicId)/approve" @{ decision = 'APPROVE' } -AllowError
    Assert-Code $approveAgain 409 'REQUEST_NOT_SUBMITTED'

    $previous = Get-Setting 'inventory.po.require_request'
    [void](Set-Bool-Setting 'inventory.po.require_request' $false)
    try {
        $freePo = Invoke-Api $script:Store POST '/inventory/purchase-orders' @{
            supplierPublicId  = $script:QaSupplier
            warehousePublicId = $script:QaWarehouse
            items             = @(@{ itemPublicId = $script:QaItem; quantity = 5 })
        }
        Assert-True ($freePo.Status -eq 201) "policy-off PO failed: $($freePo.Raw)"
    } finally {
        [void](Set-Bool-Setting 'inventory.po.require_request' ($previous -eq '1' -or $previous -eq 'true'))
    }
    "PR -> PO -> GRN ($($receipt.Body.receipt.grnNumber))"
}

Check 'TC-126 valuation and consumption reports' {
    $valuation = Invoke-Api $script:Store GET "/inventory/reports/valuation?warehouse=$script:QaWarehouse"
    Assert-True ($valuation.Status -eq 200) "valuation failed: $($valuation.Raw)"
    Assert-True ((Dec $valuation.Body.totalValue) -gt (Dec 0)) "valuation total should be > 0: $($valuation.Body.totalValue)"
    $row = @($valuation.Body.items | Where-Object { $_.itemPublicId -eq $script:QaItem })
    Assert-True ($row.Count -ge 1) 'QA item missing from valuation'
    Assert-True ([long]$row[0].quantity -gt 0) "valuation quantity wrong: $($row[0].quantity)"
    $expectedValue = (Dec $row[0].quantity) * (Dec 2.5)
    Assert-True ((Dec $row[0].value) -eq $expectedValue) "valuation value $($row[0].value) != $expectedValue"

    $today = Get-Date -Format 'yyyy-MM-dd'
    $consumption = Invoke-Api $script:Store GET "/inventory/reports/consumption?from=$today&to=$today&item=$script:QaItem"
    Assert-True ($consumption.Status -eq 200) "consumption failed: $($consumption.Raw)"
    $cRow = @($consumption.Body.items | Where-Object { $_.itemPublicId -eq $script:QaItem })
    Assert-True ($cRow.Count -eq 1) 'QA item missing from consumption'
    Assert-True ([long]$cRow[0].issued -ge 5) "expected issued >= 5, got $($cRow[0].issued)"
    Assert-True ([long]$consumption.Body.totalIssued -ge 5) "totalIssued wrong: $($consumption.Body.totalIssued)"
    "value=$($valuation.Body.totalValue) issued=$($consumption.Body.totalIssued)"
}

Check 'TC-127 write-off damaged stock with reason and guards' {
    $before = Batch-Quantity $script:QaBatch
    $wo = Invoke-Api $script:Store POST '/inventory/write-offs' @{
        itemPublicId  = $script:QaItem
        batchPublicId = $script:QaBatch
        quantity      = 2
        reason        = 'QA damaged packaging'
    }
    Assert-True ($wo.Status -eq 201) "write-off failed: $($wo.Raw)"
    $after = Batch-Quantity $script:QaBatch
    Assert-True ($after -eq ($before - 2)) "expected $($before - 2), got $after"
    $mov = Db-Int "SELECT COUNT(*) FROM smart_hospital.inventory_movements WHERE movement_type='WRITE_OFF' AND batch_id=(SELECT id FROM smart_hospital.inventory_batches WHERE public_id='$script:QaBatch') AND quantity_delta < 0;"
    Assert-True ($mov -eq 1) "expected 1 WRITE_OFF movement, got $mov"

    $noReason = Invoke-Api $script:Store POST '/inventory/write-offs' @{
        itemPublicId = $script:QaItem; quantity = 1
    } -AllowError
    Assert-Code $noReason 400 'VALIDATION_FAILED'

    $tooMuch = Invoke-Api $script:Store POST '/inventory/write-offs' @{
        itemPublicId  = $script:QaItem
        batchPublicId = $script:QaBatch
        quantity      = 999999
        reason        = 'QA over write-off'
    } -AllowError
    Assert-Code $tooMuch 422 'INSUFFICIENT_STOCK'

    $glv = Find-Item 'GLV-NST'
    $mismatch = Invoke-Api $script:Store POST '/inventory/write-offs' @{
        itemPublicId  = $glv.publicId
        batchPublicId = $script:QaBatch
        quantity      = 1
        reason        = 'QA mismatched batch'
    } -AllowError
    Assert-Code $mismatch 422 'BATCH_ITEM_MISMATCH'
    "wrote off 2, batch now $after"
}

Check 'INV ledger consistency for QA item batches' {
    $bad = Db-Int @"
SELECT COUNT(*) FROM (
  SELECT b.id, b.quantity_available,
         COALESCE((SELECT SUM(m.quantity_delta) FROM smart_hospital.inventory_movements m WHERE m.batch_id = b.id), 0) AS ledger
  FROM smart_hospital.inventory_batches b
  JOIN smart_hospital.inventory_items i ON i.id = b.item_id
  WHERE i.public_id = '$script:QaItem'
) x WHERE x.quantity_available <> x.ledger;
"@
    Assert-True ($bad -eq 0) "found $bad batches where quantity_available != ledger sum"
    'ledger matches batch quantities'
}

# ---------------------------------------------------------------- concurrency

Section 'Concurrency - C3/C4/C8/C9 (3 iterations each)'

Check 'TC-169 C3 concurrent payments never exceed balance' {
    $details = @()
    for ($i = 1; $i -le 3; $i++) {
        $b = New-Billable "c3-$i"
        $inv = New-Invoice $script:Fin $b.Patient $b.Encounter
        $start = (Get-Date).AddMilliseconds(250)
        $jobs = @(
            (New-Job $script:Fin 'POST' "/billing/invoices/$($inv.publicId)/payments" @{
                amount = 15; method = 'CARD'; idempotencyKey = "qa5-c3-a-$script:RunId-$i"
            } $start),
            (New-Job $script:Fin 'POST' "/billing/invoices/$($inv.publicId)/payments" @{
                amount = 15; method = 'CARD'; idempotencyKey = "qa5-c3-b-$script:RunId-$i"
            } $start)
        )
        $results = @(Invoke-Parallel $jobs)
        $ok = @($results | Where-Object { $_.Status -eq 201 })
        $rejected = @($results | Where-Object { $_.Status -eq 422 -or $_.Status -eq 409 })
        Assert-True ($ok.Count -eq 1) "iteration $i expected 1 success, got $($ok.Count) :: $($results.Raw -join ' | ')"
        Assert-True ($rejected.Count -eq 1) "iteration $i expected 1 rejection, got $($rejected.Count)"
        $after = Get-Invoice $script:Fin $inv.publicId
        Assert-True ((Dec $after.paidTotal) -eq (Dec 15)) "iteration $i paid total $($after.paidTotal)"
        Assert-True ((Dec $after.balance) -eq (Dec 5)) "iteration $i balance $($after.balance)"
        $dbBalance = Dec (Db-Scalar "SELECT balance FROM smart_hospital.invoices WHERE public_id='$($inv.publicId)';")
        Assert-True ($dbBalance -ge 0) "iteration $i negative balance $dbBalance"
        $payments = Db-Int "SELECT COUNT(*) FROM smart_hospital.payments WHERE invoice_id=(SELECT id FROM smart_hospital.invoices WHERE public_id='$($inv.publicId)') AND status='POSTED';"
        Assert-True ($payments -eq 1) "iteration $i expected 1 payment, got $payments"
        $details += "i$i ok=$($ok[0].Status) rejected=$($rejected[0].Status)/$($rejected[0].Body.code)"
    }
    $details -join '; '
}

Check 'TC-170 C4 same idempotency key concurrently yields one effect' {
    $details = @()
    for ($i = 1; $i -le 3; $i++) {
        $b = New-Billable "c4-$i"
        $inv = New-Invoice $script:Fin $b.Patient $b.Encounter
        $key = "qa5-c4-$script:RunId-$i"
        $body = @{ amount = 5; method = 'CASH'; reference = 'QA5-C4'; idempotencyKey = $key }
        $start = (Get-Date).AddMilliseconds(250)
        $jobs = @(
            (New-Job $script:Fin 'POST' "/billing/invoices/$($inv.publicId)/payments" $body $start),
            (New-Job $script:Fin 'POST' "/billing/invoices/$($inv.publicId)/payments" $body $start)
        )
        $results = @(Invoke-Parallel $jobs)
        $created = @($results | Where-Object { $_.Status -eq 201 })
        $blocked = @($results | Where-Object { $_.Status -eq 409 -and $_.Body.code -eq 'IDEMPOTENCY_IN_PROGRESS' })
        Assert-True (($created.Count + $blocked.Count) -eq 2) "iteration $i unexpected statuses :: $($results.Raw -join ' | ')"
        Assert-True ($created.Count -ge 1) "iteration $i expected at least one 201"
        $rows = Db-Int "SELECT COUNT(*) FROM smart_hospital.payments WHERE idempotency_key='$key';"
        Assert-True ($rows -eq 1) "iteration $i expected 1 payment row, got $rows"
        $keys = Db-Int "SELECT COUNT(*) FROM smart_hospital.idempotency_keys k WHERE k.key='$key' AND k.endpoint='billing.payment';"
        Assert-True ($keys -eq 1) "iteration $i expected 1 key row, got $keys"
        $after = Get-Invoice $script:Fin $inv.publicId
        Assert-True ((Dec $after.paidTotal) -eq (Dec 5)) "iteration $i paid total $($after.paidTotal)"

        $replay = Invoke-Api $script:Fin POST "/billing/invoices/$($inv.publicId)/payments" $body
        Assert-True ($replay.Status -eq 201) "iteration $i replay failed: $($replay.Raw)"
        Assert-True ($replay.Body.replayed -eq $true) "iteration $i replay not marked replayed"
        Assert-True ((Dec $replay.Body.amount) -eq (Dec 5)) "iteration $i replay amount wrong"
        $details += "i$i created=$($created.Count) inProgress=$($blocked.Count) rows=$rows"
    }
    $details -join '; '
}

Check 'TC-174 C8 concurrent cash session close closes once' {
    $session = Invoke-Api $script:Cashier POST '/billing/cash-sessions' @{ openingFloat = 0 }
    Assert-True ($session.Status -eq 201) "open session failed: $($session.Raw)"
    $sessionId = $session.Body.publicId
    $b = New-Billable 'c8'
    $inv = New-Invoice $script:Cashier $b.Patient $b.Encounter
    $pay = Invoke-Api $script:Cashier POST "/billing/invoices/$($inv.publicId)/payments" @{
        amount = 20; method = 'CASH'; idempotencyKey = "qa5-c8-$script:RunId"
    }
    Assert-True ($pay.Status -eq 201) "session payment failed: $($pay.Raw)"

    $start = (Get-Date).AddMilliseconds(250)
    $jobs = @(
        (New-Job $script:Cashier 'POST' "/billing/cash-sessions/$sessionId/close" @{ countedTotal = 20 } $start),
        (New-Job $script:Cashier 'POST' "/billing/cash-sessions/$sessionId/close" @{ countedTotal = 20 } $start)
    )
    $results = @(Invoke-Parallel $jobs)
    $ok = @($results | Where-Object { $_.Status -eq 200 })
    $closed = @($results | Where-Object { $_.Status -eq 409 -and $_.Body.code -eq 'CASH_SESSION_CLOSED' })
    Assert-True ($ok.Count -eq 1) "expected 1 close success, got $($ok.Count) :: $($results.Raw -join ' | ')"
    Assert-True ($closed.Count -eq 1) "expected 1 already-closed, got $($closed.Count)"
    $row = Db-Scalar "SELECT CONCAT(status, '|', expected_total, '|', variance, '|', COALESCE(active_flag,'NULL')) FROM smart_hospital.cash_sessions WHERE public_id='$sessionId';"
    Assert-True ($row -eq 'CLOSED|20.00|0.00|NULL') "unexpected session row: $row"
    $audit = Db-Int "SELECT COUNT(*) FROM smart_hospital.audit_logs WHERE action='CASH_SESSION_CLOSED' AND entity_public_id='$sessionId';"
    Assert-True ($audit -eq 1) "expected 1 close audit row, got $audit"
    "session=$($session.Body.sessionNumber) closed once"
}

Check 'TC-175 C9 concurrent transfers never go negative' {
    $main = Find-Warehouse 'MAIN'
    $details = @()
    for ($i = 1; $i -le 3; $i++) {
        $code = "QACONC$script:RunId$i"
        $item = Invoke-Api $script:Store POST '/inventory/items' @{
            code = $code; sku = "QACONCSKU$script:RunId$i"; nameAr = 'صنف تزامن'
            baseUnit = 'BOX'; trackExpiry = $false; unitCost = 1
        }
        Assert-True ($item.Status -eq 201) "concurrency item create failed: $($item.Raw)"
        $itemId = $item.Body.publicId
        $recv = Receive-Batch $script:Store $itemId $main.publicId "QA-BCONC-$script:RunId-$i" 40 1 $null $script:QaSupplier
        Assert-True ($recv.Status -eq 201) "concurrency receipt failed: $($recv.Raw)"

        $start = (Get-Date).AddMilliseconds(250)
        $jobs = @(
            (New-Job $script:Store 'POST' '/inventory/transfers' @{
                fromWarehousePublicId = $main.publicId
                toWarehousePublicId   = $script:QaWarehouse
                documentRef           = "QA-CONC-A-$script:RunId-$i"
                items                 = @(@{ itemPublicId = $itemId; quantity = 30 })
            } $start),
            (New-Job $script:Store 'POST' '/inventory/transfers' @{
                fromWarehousePublicId = $main.publicId
                toWarehousePublicId   = $script:QaWarehouse
                documentRef           = "QA-CONC-B-$script:RunId-$i"
                items                 = @(@{ itemPublicId = $itemId; quantity = 30 })
            } $start)
        )
        $results = @(Invoke-Parallel $jobs)
        $ok = @($results | Where-Object { $_.Status -eq 201 })
        $rejected = @($results | Where-Object { $_.Status -eq 422 -or $_.Status -eq 409 })
        Assert-True ($ok.Count -eq 1) "iteration $i expected 1 success, got $($ok.Count) :: $($results.Raw -join ' | ')"
        Assert-True ($rejected.Count -eq 1) "iteration $i expected 1 rejection, got $($rejected.Count)"
        $sourceQty = [long](Db-Scalar "SELECT COALESCE(SUM(quantity_available),0) FROM smart_hospital.inventory_batches WHERE item_id=(SELECT id FROM smart_hospital.inventory_items WHERE public_id='$itemId') AND warehouse_id=(SELECT id FROM smart_hospital.warehouses WHERE public_id='$($main.publicId)');")
        $destQty = [long](Db-Scalar "SELECT COALESCE(SUM(quantity_available),0) FROM smart_hospital.inventory_batches WHERE item_id=(SELECT id FROM smart_hospital.inventory_items WHERE public_id='$itemId') AND warehouse_id=(SELECT id FROM smart_hospital.warehouses WHERE public_id='$script:QaWarehouse');")
        Assert-True ($sourceQty -eq 10) "iteration $i source should be 10, got $sourceQty"
        Assert-True ($destQty -eq 30) "iteration $i destination should be 30, got $destQty"
        $negatives = Db-Int "SELECT COUNT(*) FROM smart_hospital.inventory_batches WHERE quantity_available < 0;"
        Assert-True ($negatives -eq 0) "iteration $i found $negatives negative batches"
        $details += "i$i ok=$($ok[0].Status) rejected=$($rejected[0].Status)/$($rejected[0].Body.code)"
    }
    $details -join '; '
}

# ---------------------------------------------------------------- global invariants

Section 'Global invariants'

Check 'DB invariant: no negative invoice balance' {
    $n = Db-Int "SELECT COUNT(*) FROM smart_hospital.invoices WHERE balance < 0;"
    Assert-True ($n -eq 0) "found $n negative balances"
    "0 negative balances"
}

Check 'DB invariant: no negative batch quantity' {
    $n = Db-Int "SELECT COUNT(*) FROM smart_hospital.inventory_batches WHERE quantity_available < 0;"
    Assert-True ($n -eq 0) "found $n negative batches"
    "0 negative batches"
}

Check 'DB invariant: no invoice item double billing' {
    $n = Db-Int "SELECT COUNT(*) FROM (SELECT service_execution_id FROM smart_hospital.invoice_items GROUP BY service_execution_id HAVING COUNT(*) > 1) d;"
    Assert-True ($n -eq 0) "found $n duplicated service executions"
    "0 duplicate charges"
}

Check 'DB invariant: audit hash chain verifies' {
    $r = Invoke-Api $script:Admin GET '/admin/audit/verify'
    Assert-True ($r.Status -eq 200) "verify failed: $($r.Raw)"
    Assert-True ($r.Body.valid -eq $true) "audit chain invalid: $($r.Raw)"
    "valid=$($r.Body.valid) entries=$($r.Body.entries)"
}

# ---------------------------------------------------------------- summary

$total = $script:Pass + $script:Fail
Write-Host "`n=== Phase 5 summary (run $script:RunId) ===" -ForegroundColor Cyan
Write-Host ("PASS {0} / {1}   FAIL {2}   SKIP {3}" -f $script:Pass, $total, $script:Fail, $script:Skipped)
if ($script:Fail -gt 0) {
    Write-Host "`nFailures:" -ForegroundColor Red
    foreach ($failure in $script:Failures) { Write-Host "  - $failure" -ForegroundColor Red }
    exit 1
}
exit 0
