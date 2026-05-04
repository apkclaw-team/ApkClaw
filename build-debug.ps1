$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path

function Test-JbrHome {
    param([string]$Path)

    if ([string]::IsNullOrWhiteSpace($Path)) {
        return $false
    }

    $javaExe = Join-Path $Path 'bin\java.exe'
    $jlinkExe = Join-Path $Path 'bin\jlink.exe'
    return (Test-Path $javaExe) -and (Test-Path $jlinkExe)
}

function Resolve-JbrHome {
    $candidates = @(
        $env:APKCLAW_JBR,
        $env:ANDROID_STUDIO_JBR,
        $env:JAVA_HOME,
        'E:\2.work\Android\Android Studio\jbr',
        'C:\Program Files\Android\Android Studio\jbr'
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }

    foreach ($candidate in $candidates) {
        if (Test-JbrHome $candidate) {
            return $candidate
        }
    }

    throw @"
Unable to find a usable Android Studio JBR.

Set APKCLAW_JBR or ANDROID_STUDIO_JBR to a JBR/JDK path that contains:
  - bin\java.exe
  - bin\jlink.exe
"@
}

$jbrHome = Resolve-JbrHome
Write-Host "Using JBR: $jbrHome"

Push-Location $projectRoot
try {
    $env:JAVA_HOME = $jbrHome
    $env:PATH = "$jbrHome\bin;$env:PATH"

    & .\gradlew.bat `
        "-Dorg.gradle.java.home=$jbrHome" `
        "-Dorg.gradle.java.installations.paths=$jbrHome" `
        "-Dorg.gradle.java.installations.auto-detect=false" `
        assembleDebug

    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}
finally {
    Pop-Location
}