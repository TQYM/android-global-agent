# Build agentd-ui.apk without Gradle: aapt2 + javac + d8 + apksigner.
# Requires: JDK 17 (tools\jdk), build-tools;34.0.0 + platforms;android-34
# (tools\android-sdk). Output: agentd-apk\build\agentd-ui.apk
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$sdk  = "C:\dsh\tools\android-sdk"
$bt   = "$sdk\build-tools\34.0.0"
$plat = "$sdk\platforms\android-34\android.jar"
$jdk  = "C:\dsh\tools\jdk"
$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;$env:Path"

$out = "$root\build"
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force -Path $out | Out-Null
Set-Location $root

Write-Host "== 1. aapt2 compile resources =="
& "$bt\aapt2.exe" compile --dir res -o "$out\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "== 2. aapt2 link =="
& "$bt\aapt2.exe" link -o "$out\base.apk" -I $plat `
    --manifest AndroidManifest.xml -R "$out\res.zip" `
    --java "$out\gen" --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "== 3. javac =="
$srcs = Get-ChildItem "$root\src", "$out\gen" -Recurse -Filter *.java |
        ForEach-Object { $_.FullName }
& "$jdk\bin\javac.exe" -source 11 -target 11 -encoding UTF-8 `
    -classpath $plat -d "$out\classes" $srcs
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "== 4. d8 dex =="
New-Item -ItemType Directory -Force -Path "$out\dex" | Out-Null
$classFiles = Get-ChildItem "$out\classes" -Recurse -Filter *.class |
              ForEach-Object { $_.FullName }
& "$bt\d8.exe" --release --lib $plat --output "$out\dex" $classFiles
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "== 5. package dex into apk =="
& "$jdk\bin\jar.exe" -uf "$out\base.apk" -C "$out\dex" classes.dex
if ($LASTEXITCODE -ne 0) { throw "jar update failed" }

Write-Host "== 6. zipalign =="
& "$bt\zipalign.exe" -f 4 "$out\base.apk" "$out\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "== 7. sign =="
$ks = "$root\debug.keystore"
if (-not (Test-Path $ks)) {
    & "$jdk\bin\keytool.exe" -genkeypair -keystore $ks -alias agentd `
        -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass android -keypass android -dname "CN=agentd"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android `
    --ks-key-alias agentd --key-pass pass:android `
    --out "$out\agentd-ui.apk" "$out\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Write-Host ("DONE: " + "$out\agentd-ui.apk " +
            (Get-Item "$out\agentd-ui.apk").Length + " bytes")
