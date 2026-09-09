#requires -Version 5.1
<#
.SYNOPSIS
Semantic UI bridge for Android devices (PowerShell, no Python needed).

Battle-tested on Android 16 / ColorOS (PKX110). Mirrors ui_dump.py:
turns `adb shell uiautomator dump` into a table of interactive nodes
(text / desc / resource-id / class / tap center), or captures a
byte-faithful PNG screenshot via exec-out.

.EXAMPLES
    .\ui_dump.ps1 dump                  # node table of current screen
    .\ui_dump.ps1 dump -Json            # machine-readable
    .\ui_dump.ps1 screen screen.png     # PNG screenshot
    .\ui_dump.ps1 dump -Serial DEVICE   # pick a device
#>
[CmdletBinding()]
param(
    [string]$Serial = "",
    [string]$AdbPath = "adb",
    [Parameter(Position = 0, Mandatory = $true)]
    [ValidateSet("dump", "screen")]
    [string]$Command,
    [Parameter(Position = 1)]
    [string]$Output = "screen.png",
    [switch]$Json
)

$ErrorActionPreference = "Stop"

function Invoke-Adb {
    param([string[]]$AdbArgs)
    $extra = @()
    if ($Serial) { $extra = @("-s", $Serial) }
    & $AdbPath @extra @AdbArgs
    if ($LASTEXITCODE -ne 0) {
        throw "adb $AdbArgs failed (exit $LASTEXITCODE)"
    }
}

# cmd /c redirection is byte-faithful; PowerShell `>` re-encodes to UTF-16.
function Invoke-AdbRawToFile {
    param([string[]]$AdbArgs, [string]$Path)
    $extra = ""
    if ($Serial) { $extra = "-s $Serial" }
    $quoted = '"{0}"' -f $AdbPath
    cmd /c "$quoted $extra $($AdbArgs -join ' ') > `"$Path`""
    if ($LASTEXITCODE -ne 0) {
        throw "adb $AdbArgs failed (exit $LASTEXITCODE)"
    }
}

function Get-HierarchyXml {
    param([int]$Retries = 2)
    $dumpPath = "/sdcard/window_dump.xml"
    $tempXml = Join-Path $env:TEMP "ui_dump_$PID.xml"
    $last = ""
    for ($i = 0; $i -le $Retries; $i++) {
        $argv = @()
        if ($Serial) { $argv += @("-s", $Serial) }
        $argv += @("shell", "uiautomator", "dump", $dumpPath)
        # 2>&1 keeps "could not get idle state" diagnostics in $out.
        $out = (& $AdbPath @argv 2>&1) -join "`n"
        if ($out -match "dumped to") {
            Invoke-AdbRawToFile @("exec-out", "cat", $dumpPath) $tempXml
            $text = [System.Text.Encoding]::UTF8.GetString(
                [System.IO.File]::ReadAllBytes($tempXml))
            if ($text -match "<hierarchy") {
                return $text
            }
            $last = "empty hierarchy payload"
        } else {
            $last = $out.Trim()
        }
        Start-Sleep -Milliseconds 600
    }
    throw "uiautomator dump failed: $last (busy/animated screen? open a static app and retry)"
}

function ConvertToNodes {
    param([string]$XmlText)
    $doc = New-Object System.Xml.XmlDocument
    $doc.LoadXml($XmlText)
    $nodes = New-Object System.Collections.Generic.List[object]
    function Walk($Element) {
        foreach ($child in $Element.SelectNodes("node")) {
            $text = [string]$child.GetAttribute("text")
            $desc = [string]$child.GetAttribute("content-desc")
            $clickable = $child.GetAttribute("clickable") -eq "true"
            $scrollable = $child.GetAttribute("scrollable") -eq "true"
            if ($clickable -or $scrollable -or $text.Trim() -or $desc.Trim()) {
                $center = $null
                if ($child.GetAttribute("bounds") -match
                        '^\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]$') {
                    $l = [int]$Matches[1]; $t = [int]$Matches[2]
                    $r = [int]$Matches[3]; $b = [int]$Matches[4]
                    $center = "{0},{1}" -f [math]::Floor(($l + $r) / 2),
                                            [math]::Floor(($t + $b) / 2)
                }
                $class = $child.GetAttribute("class")
                $nodes.Add([pscustomobject]@{
                    N       = $nodes.Count + 1
                    Text    = $text
                    Desc    = $desc
                    Id      = $child.GetAttribute("resource-id")
                    Class   = ($class -replace '^.*\.', '')
                    Click   = $clickable
                    Scroll  = $scrollable
                    Center  = $center
                })
            }
            Walk $child
        }
    }
    Walk $doc.DocumentElement
    return ,$nodes
}

try {
    switch ($Command) {
        "dump" {
            $xmlText = Get-HierarchyXml
            $nodes = ConvertToNodes $xmlText
            if ($nodes.Count -eq 0) {
                throw "no interactive nodes found (screen locked?)"
            }
            if ($Json) {
                $nodes | ConvertTo-Json -Depth 3
            } else {
                $nodes | Format-Table N, Text, Desc, Id, Class, Click, Center `
                    -AutoSize | Out-String -Width 200
                ""
                "tap:    adb shell input tap <x> <y>"
                "text:   adb shell input text 'hello%sworld'   (%s = space)"
                "key:    adb shell input keyevent 4            (BACK)"
            }
        }
        "screen" {
            $temp = Join-Path $env:TEMP "screen_$PID.png"
            Invoke-AdbRawToFile @("exec-out", "screencap", "-p") $temp
            $bytes = [System.IO.File]::ReadAllBytes($temp)
            if ($bytes.Length -lt 16 -or $bytes[0] -ne 0x89 -or $bytes[1] -ne 0x50) {
                throw "screencap returned no PNG frame (secure surface?)"
            }
            [System.IO.File]::WriteAllBytes($Output, $bytes)
            "wrote $Output ($($bytes.Length) bytes)"
        }
    }
} catch {
    Write-Host "error: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
