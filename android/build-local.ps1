param(
    [string[]]$Tasks = @("lintDebug", "testDebugUnitTest", "assembleDebug"),
    [switch]$IgnoreUserGradleProxy
)

$ErrorActionPreference = "Stop"
$androidRoot = $PSScriptRoot
$workspaceRoot = Split-Path -Parent $androidRoot
$mappedDrive = $null
$userGradleProperties = Join-Path $env:USERPROFILE ".gradle\gradle.properties"
$proxyBackup = "$userGradleProperties.landosol-backup"
$proxyTemporarilyDisabled = $false

if ((Test-Path -LiteralPath $proxyBackup) -and -not (Test-Path -LiteralPath $userGradleProperties)) {
    Move-Item -LiteralPath $proxyBackup -Destination $userGradleProperties
}

try {
    if ($IgnoreUserGradleProxy -and (Test-Path -LiteralPath $userGradleProperties)) {
        if (Test-Path -LiteralPath $proxyBackup) {
            throw "Gradle proxy backup already exists: $proxyBackup"
        }
        Move-Item -LiteralPath $userGradleProperties -Destination $proxyBackup
        $proxyTemporarilyDisabled = $true
    }

    $buildRoot = $androidRoot
    if ($workspaceRoot -match "[^\x00-\x7F]") {
        $driveName = @("R", "S", "T", "U") |
            Where-Object { -not (Get-PSDrive -Name $_ -ErrorAction SilentlyContinue) } |
            Select-Object -First 1
        if (-not $driveName) {
            throw "No free temporary drive letter is available."
        }

        $mappedDrive = "${driveName}:"
        subst.exe $mappedDrive $workspaceRoot
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to map workspace to $mappedDrive"
        }
        $buildRoot = "$mappedDrive\android"
    }

    Push-Location $buildRoot
    try {
        & .\gradlew.bat @Tasks
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
} finally {
    if ($mappedDrive) {
        subst.exe $mappedDrive /D | Out-Null
    }
    if ($proxyTemporarilyDisabled -and (Test-Path -LiteralPath $proxyBackup)) {
        Move-Item -LiteralPath $proxyBackup -Destination $userGradleProperties
    }
}
