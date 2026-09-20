# Computes PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM for the QR-code
# device-owner provisioning payload. Reads keystore.properties (gitignored,
# local-only) so the store/key passwords never leave this machine.

$ErrorActionPreference = "Stop"

$propsFile = Join-Path $PSScriptRoot "..\keystore.properties"
if (-not (Test-Path $propsFile)) {
    throw "keystore.properties not found at $propsFile"
}

$props = @{}
Get-Content $propsFile | Where-Object { $_ -match "=" -and -not $_.TrimStart().StartsWith("#") } | ForEach-Object {
    $key, $value = $_ -split "=", 2
    $props[$key.Trim()] = $value.Trim()
}

$storeFile     = $props["storeFile"]
$storePassword = $props["storePassword"]
$keyAlias      = $props["keyAlias"]

if (-not $storeFile -or $storeFile -eq "/absolute/path/to/your/release.jks") {
    throw "Fill in keystore.properties with your real release keystore path/alias/passwords first."
}
if (-not (Test-Path $storeFile)) {
    throw "storeFile not found at $storeFile"
}

$keytoolCmd = Get-Command keytool -ErrorAction SilentlyContinue
if ($keytoolCmd) {
    $keytool = $keytoolCmd.Source
} elseif ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\keytool.exe")) {
    $keytool = "$env:JAVA_HOME\bin\keytool.exe"
} elseif (Test-Path "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe") {
    $keytool = "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"
} else {
    throw "keytool.exe not found. Set JAVA_HOME or install a JDK."
}

$tempCert = Join-Path $env:TEMP "simtelpas-cert.der"
try {
    if (Test-Path $tempCert) { Remove-Item $tempCert -Force }

    # keytool writes a routine "Certificate stored in file <...>" line to
    # stderr on success; under ErrorActionPreference=Stop that gets treated
    # as a terminating error, so relax it just for this native call and
    # verify success by checking the output file instead of the exit path.
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & $keytool -exportcert -alias $keyAlias -keystore $storeFile -storepass $storePassword -file $tempCert | Out-Null
    $ErrorActionPreference = $prevEap

    if (-not (Test-Path $tempCert)) {
        throw "keytool did not produce a certificate file - check alias/passwords."
    }

    $certBytes = [System.IO.File]::ReadAllBytes($tempCert)
    $hash      = [System.Security.Cryptography.SHA256]::Create().ComputeHash($certBytes)

    $base64  = [Convert]::ToBase64String($hash)
    $urlSafe = $base64.Replace('+', '-').Replace('/', '_').TrimEnd('=')

    Write-Output "PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM:"
    Write-Output $urlSafe
} finally {
    if (Test-Path $tempCert) { Remove-Item $tempCert -Force }
}
