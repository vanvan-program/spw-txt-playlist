param([string]$JdkHome = $env:JAVA_HOME, [switch]$Offline)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $JdkHome) {
    $localJbr = 'C:\Program Files\JetBrains\PyCharm 2025.1.6.2\jbr'
    if (Test-Path -LiteralPath "$localJbr\bin\javac.exe") { $JdkHome = $localJbr }
}
if (-not $JdkHome -or -not (Test-Path -LiteralPath "$JdkHome\bin\javac.exe")) {
    throw '请安装 JDK 25，并设置 JAVA_HOME，或通过 -JdkHome 指定路径。'
}
$env:JAVA_HOME = $JdkHome
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.tools\gradle-home'
Push-Location -LiteralPath $projectRoot
try {
    $arguments = @('test', 'plugin', '--console=plain')
    if ($Offline) { $arguments += '--offline' }
    & '.\gradlew.bat' @arguments
    if ($LASTEXITCODE -ne 0) { throw "构建失败，退出码 $LASTEXITCODE" }
    New-Item -ItemType Directory -Path 'dist' -Force | Out-Null
    Copy-Item -LiteralPath 'build\libs\plugin-io.github.localplaylist.txtimport-1.0.0.spmod' -Destination 'dist\按文件导入歌单-1.0.0.spmod' -Force
    Get-FileHash -LiteralPath 'dist\按文件导入歌单-1.0.0.spmod' -Algorithm SHA256
} finally { Pop-Location }
