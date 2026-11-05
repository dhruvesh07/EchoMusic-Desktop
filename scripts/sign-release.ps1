param(
    [Parameter(Mandatory = $true)]
    [string]$CertificatePath,

    [Parameter(Mandatory = $true)]
    [string]$CertificatePassword,

    [string]$TimestampUrl = "http://timestamp.digicert.com"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $CertificatePath)) {
    throw "Certificate not found: $CertificatePath"
}

$signtool = Get-ChildItem "${env:ProgramFiles(x86)}\Windows Kits\10\bin" `
    -Recurse -Filter "signtool.exe" -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -match '\\x64\\' } |
    Sort-Object FullName -Descending |
    Select-Object -First 1 -ExpandProperty FullName

if (-not $signtool) {
    throw "signtool.exe not found. Install the Windows SDK signing tools."
}

$msiDir = Join-Path $PSScriptRoot "..\desktop\build\compose\binaries\main\msi"
$msis = @(Get-ChildItem $msiDir -Filter "*.msi" -ErrorAction SilentlyContinue)
if ($msis.Count -eq 0) {
    throw "No MSI found in $msiDir. Run: .\gradlew.bat :desktop:packageMsi"
}

foreach ($msi in $msis) {
    Write-Host "Signing $($msi.Name) ..."
    & $signtool sign `
        /f $CertificatePath `
        /p $CertificatePassword `
        /fd SHA256 `
        /tr $TimestampUrl `
        /td SHA256 `
        $msi.FullName

    if ($LASTEXITCODE -ne 0) {
        throw "signtool failed for $($msi.Name) with exit code $LASTEXITCODE"
    }

    Get-AuthenticodeSignature $msi.FullName |
        Format-List Status, StatusMessage, SignerCertificate
}
