$ErrorActionPreference = 'Stop'
$Base = 'http://localhost:8080/api/v1'
$cookies = New-Object System.Net.CookieContainer
$login = @{ username = 'admin'; password = 'Admin@2026Secure' } | ConvertTo-Json -Compress
$req = [System.Net.HttpWebRequest]::Create("$Base/auth/login")
$req.Method = 'POST'
$req.CookieContainer = $cookies
$req.ContentType = 'application/json; charset=utf-8'
$bytes = [System.Text.Encoding]::UTF8.GetBytes($login)
$req.ContentLength = $bytes.Length
$s = $req.GetRequestStream(); $s.Write($bytes, 0, $bytes.Length); $s.Close()
$resp = $req.GetResponse()
$reader = New-Object System.IO.StreamReader($resp.GetResponseStream(), [System.Text.Encoding]::UTF8)
$raw = $reader.ReadToEnd(); $reader.Close(); $resp.Close()
$auth = $raw | ConvertFrom-Json
$script:Csrf = $auth.csrfToken
$script:Cookies = $cookies

function Api {
    param([string]$Method, [string]$Path, $Body = $null)
    $r = [System.Net.HttpWebRequest]::Create("$Base$Path")
    $r.Method = $Method
    $r.CookieContainer = $script:Cookies
    $r.Accept = 'application/json'
    $r.Headers.Add('X-CSRF-Token', $script:Csrf)
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 12 -Compress
        $b = [System.Text.Encoding]::UTF8.GetBytes($json)
        $r.ContentType = 'application/json; charset=utf-8'
        $r.ContentLength = $b.Length
        $st = $r.GetRequestStream(); $st.Write($b, 0, $b.Length); $st.Close()
    }
    try {
        $res = $r.GetResponse()
        $code = [int]$res.StatusCode
        $rd = New-Object System.IO.StreamReader($res.GetResponseStream(), [System.Text.Encoding]::UTF8)
        $txt = $rd.ReadToEnd(); $rd.Close(); $res.Close()
    } catch [System.Net.WebException] {
        $we = $_.Exception
        $code = [int]$we.Response.StatusCode
        $rd = New-Object System.IO.StreamReader($we.Response.GetResponseStream(), [System.Text.Encoding]::UTF8)
        $txt = $rd.ReadToEnd(); $rd.Close(); $we.Response.Close()
    }
    $obj = $null
    if ($txt) { try { $obj = $txt | ConvertFrom-Json } catch { $obj = $txt } }
    return [pscustomobject]@{ Status = $code; Body = $obj; Raw = $txt }
}

# 1) create a count on the QA warehouse from the last run and post it as admin
$wh = (Api GET '/inventory/warehouses?activeOnly=false').Body.items | Where-Object { $_.code -like 'QAWH*' } | Select-Object -First 1
"warehouse=$($wh.code) $($wh.publicId)"
$count = Api POST '/inventory/counts' @{ warehousePublicId = $wh.publicId }
"count create HTTP $($count.Status) version=$($count.Body.version) lines=$(@($count.Body.lines).Count)"
$countId = $count.Body.publicId
$line = @($count.Body.lines)[0]
$patch = Api PATCH "/inventory/counts/$countId/lines/$($line.publicId)" @{ countedQuantity = [int]$line.systemQuantity - 1 }
"patch HTTP $($patch.Status) version=$($patch.Body.version)"
$post = Api POST "/inventory/counts/$countId/post" @{ reason = 'probe variance' }
"post HTTP $($post.Status) $($post.Raw)"
