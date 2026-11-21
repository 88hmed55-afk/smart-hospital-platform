# Creates the Phase 6 JMS destinations used by the notification pipeline.
# Idempotent: safe to run after a domain rebuild.
# Usage: powershell -ExecutionPolicy Bypass -File tools\setup-jms.ps1
$ErrorActionPreference = 'Stop'
$asadmin = 'C:\glassfish7\bin\asadmin.bat'
$passwordfile = 'C:\glassfish7\userpass.txt'

function Invoke-Asadmin {
    param([string[]]$Arguments)
    $output = & $asadmin --user admin --passwordfile $passwordfile @Arguments 2>&1
    return ($output | Out-String)
}

function Ensure-Destination {
    param([string]$Name, [string]$Extra = '')
    $list = Invoke-Asadmin @('list-jmsdest')
    if ($list -match "(?m)^\s*$([regex]::Escape($Name))\s*$") {
        Write-Host "destination $Name already exists"
        return
    }
    $cmd = @('create-jmsdest', '-T', 'queue')
    if ($Extra) { $cmd += @('--property', $Extra) }
    $cmd += $Name
    Invoke-Asadmin $cmd | Out-Null
    Write-Host "destination $Name created"
}

function Ensure-Resource {
    param([string]$Jndi, [string]$PhysicalName)
    $list = Invoke-Asadmin @('list-jms-resources')
    if ($list -match "(?m)^\s*$([regex]::Escape($Jndi))\s*$") {
        Write-Host "resource $Jndi already exists"
        return
    }
    Invoke-Asadmin @('create-jms-resource', '--restype', 'jakarta.jms.Queue',
        '--property', "Name=$PhysicalName", $Jndi) | Out-Null
    Write-Host "resource $Jndi created"
}

Ensure-Destination -Name 'NotificationQueue' -Extra 'max-redelivery-limit=3'
Ensure-Destination -Name 'NotificationDLQ'
Ensure-Resource -Jndi 'jms/NotificationQueue' -PhysicalName 'NotificationQueue'
Ensure-Resource -Jndi 'jms/NotificationDLQ' -PhysicalName 'NotificationDLQ'
Write-Host 'JMS setup complete.'
