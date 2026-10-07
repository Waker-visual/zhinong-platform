# Local-only credential reader. This file contains no deployment values.
function Read-RdsConfig([string]$Path) {
    $config = @{}
    foreach ($line in [IO.File]::ReadAllLines((Resolve-Path -LiteralPath $Path).Path, [Text.Encoding]::UTF8)) {
        if (-not $line.Trim() -or $line.TrimStart().StartsWith('#')) { continue }
        if ($line -notmatch '^\s*([^:\uFF1A]+)[:\uFF1A]\s*(.+?)\s*$') { throw 'Invalid RDS configuration line.' }
        $key = $matches[1].Trim(); $value = $matches[2].Trim()
        $name = switch -Regex ($key) {
            '^(host|\u4e3b\u673a)$' { 'host'; break }
            '^(port|\u7aef\u53e3)$' { 'port'; break }
            '^(user|\u7528\u6237)$' { 'user'; break }
            '^(password|\u5bc6\u7801)$' { 'password'; break }
            '^(database|\u6570\u636e\u5e93)$' { 'database'; break }
            default { throw 'Unknown RDS configuration field.' }
        }
        if ($config.ContainsKey($name)) { throw 'Duplicate RDS configuration field.' }
        $config[$name] = $value
    }
    foreach ($name in @('host','port','user','password','database')) {
        if (-not $config[$name]) { throw "Missing RDS field: $name" }
    }
    if ($config.host -notmatch '^[A-Za-z0-9.-]+$' -or $config.database -notmatch '^[A-Za-z0-9_$-]+$') { throw 'Invalid host or database name.' }
    $port = 0
    if (-not [int]::TryParse($config.port, [ref]$port) -or $port -lt 1 -or $port -gt 65535) { throw 'Invalid database port.' }
    return $config
}

function Resolve-RdsDirect([hashtable]$Config, [string]$Address, [string]$BindAddress) {
    if (-not $Address) {
        $ip = $null
        if ([Net.IPAddress]::TryParse($Config.host, [ref]$ip)) { $Address = $ip.ToString() }
        else {
            $uri = 'https://dns.alidns.com/resolve?name=' + [Uri]::EscapeDataString($Config.host) + '&type=A'
            try { $answer = Invoke-RestMethod -Uri $uri -TimeoutSec 15 } catch { throw 'Public DNS lookup failed; supply -DirectAddress explicitly.' }
            $Address = $answer.Answer | Where-Object { $_.type -eq 1 -and $_.data -notmatch '^198\.(18|19)\.' } | Select-Object -First 1 -ExpandProperty data
        }
    }
    $ip = $null
    if (-not [Net.IPAddress]::TryParse($Address, [ref]$ip) -or $ip.AddressFamily -ne 'InterNetwork' -or $Address -match '^198\.(18|19)\.') { throw 'A real IPv4 address is required; VPN synthetic DNS addresses are not usable.' }
    if (-not $BindAddress) {
        $physical = @(Get-NetAdapter -Physical | Where-Object Status -eq 'Up' | Select-Object -ExpandProperty ifIndex)
        $interface = Get-NetIPConfiguration | Where-Object { $_.InterfaceIndex -in $physical -and $_.IPv4DefaultGateway } | Select-Object -First 1
        if (-not $interface) { throw 'No physical network interface found; supply -BindAddress.' }
        $BindAddress = $interface.IPv4Address.IPAddress
    }
    if (-not (Get-NetIPAddress -AddressFamily IPv4 | Where-Object IPAddress -eq $BindAddress)) { throw 'Bind address is not assigned to this computer.' }
    return @{ address=$Address; bind=$BindAddress }
}
