$ErrorActionPreference = 'Stop'
$backend = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent $backend
$frontend = Join-Path $root 'frontend'
$jdk = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Users\11589\.jdks\temurin-17' }
$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;$env:Path"
$env:PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD = '1'

$gradle = 'C:\Users\11589\.gradle\wrapper\dists\gradle-9.8.1-bin\41i18951l6m6st25rtrwj0neh\gradle-9.8.1\bin\gradle.bat'
if (!(Test-Path -LiteralPath $gradle)) {
    $gradle = Join-Path $backend 'gradlew.bat'
}

Push-Location $frontend
try {
    npm install --no-audit --no-fund
    if ($LASTEXITCODE -ne 0) { throw 'npm install failed' }
    npm run build
    if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed' }
} finally {
    Pop-Location
}

Push-Location $backend
try {
    & $gradle --no-daemon build -x test --offline
    if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed' }

    $stage = Join-Path $backend 'build\jpackage-input'
    if (!(Test-Path -LiteralPath $stage)) {
        New-Item -ItemType Directory -Path $stage | Out-Null
    }
    Copy-Item -LiteralPath (Join-Path $backend 'build\libs\CenterControl.jar') -Destination $stage -Force

    $buildRoot = [IO.Path]::GetFullPath((Join-Path $backend 'build'))
    $packageDest = [IO.Path]::GetFullPath((Join-Path $backend 'build\jpackage-output'))
    if (!$packageDest.StartsWith($buildRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "jpackage output is outside build directory: $packageDest"
    }
    if (Test-Path -LiteralPath $packageDest) {
        Remove-Item -LiteralPath $packageDest -Recurse -Force
    }
    New-Item -ItemType Directory -Path $packageDest | Out-Null

    $args = @(
        '--type', 'app-image',
        '--name', 'CenterControl',
        '--input', $stage,
        '--main-jar', 'CenterControl.jar',
        '--main-class', 'org.springframework.boot.loader.launch.JarLauncher',
        '--java-options', '-Dfile.encoding=UTF-8',
        '--dest', $packageDest,
        '--app-version', '0.3.0',
        '--vendor', 'CenterControl',
        '--win-console'
    )
    $icon = Join-Path $root 'build\brand-logo.ico'
    if (Test-Path -LiteralPath $icon) {
        $args += @('--icon', $icon)
    }

    & (Join-Path $jdk 'bin\jpackage.exe') @args
    if ($LASTEXITCODE -ne 0) { throw 'jpackage failed' }

    $builtApp = Join-Path $packageDest 'CenterControl'
    $target = Join-Path $root 'dist\CenterControl'
    if (Test-Path -LiteralPath $target) {
        $backup = Join-Path $root ('dist\CenterControl.backup-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
        Move-Item -LiteralPath $target -Destination $backup
        Write-Host "Previous build preserved at: $backup"
    }
    Move-Item -LiteralPath $builtApp -Destination $target

    # 每次构建都会留一份约 348MB 的完整备份，几轮下来就是几个 GB。
    # 这里只保留最近一份，够回滚一次即可。
    Get-ChildItem -LiteralPath (Join-Path $root 'dist') -Directory -Filter 'CenterControl.backup-*' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -Skip 1 |
        ForEach-Object {
            Write-Host "Removing old backup: $($_.Name)"
            Remove-Item -LiteralPath $_.FullName -Recurse -Force
        }

    Write-Host "Built: $root\dist\CenterControl\CenterControl.exe"
} finally {
    Pop-Location
}
