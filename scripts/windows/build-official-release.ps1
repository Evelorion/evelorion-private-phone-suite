$ErrorActionPreference = "Stop"

. "$PSScriptRoot\ReleaseSigningCredential.ps1"

$repoRoot = (Resolve-Path "$PSScriptRoot\..\..").Path
$secret = Get-EvelorionReleaseSigningSecret

try {
    $env:KEYSTORE_PASSWORD = $secret
    $env:KEY_ALIAS = "private-edition"
    $env:KEY_PASSWORD = $secret
    $env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
    $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
    $env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"

    Push-Location "$repoRoot\contacts-app"
    try {
        & .\gradlew.bat :app:testDebugUnitTest :app:lintRelease :app:assembleRelease --no-daemon
        if ($LASTEXITCODE -ne 0) { throw "Contacts release build failed." }
    } finally {
        Pop-Location
    }

    Push-Location "$repoRoot\phone-app"
    try {
        & .\gradlew.bat :app:testDebugUnitTest :app:lintRelease :app:assembleRelease --no-daemon
        if ($LASTEXITCODE -ne 0) { throw "Phone release build failed." }
    } finally {
        Pop-Location
    }

    Push-Location "$repoRoot\messages-app"
    try {
        & .\gradlew.bat :app:testDebugUnitTest :app:lintRelease :app:assembleRelease --no-daemon
        if ($LASTEXITCODE -ne 0) { throw "Messages release build failed." }
    } finally {
        Pop-Location
    }
} finally {
    Remove-Item Env:KEYSTORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:KEY_ALIAS -ErrorAction SilentlyContinue
    Remove-Item Env:KEY_PASSWORD -ErrorAction SilentlyContinue
    $secret = $null
}
