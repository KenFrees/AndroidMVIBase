# 两段强杀验收：仅停止独立 logging 测试包，不停止示例 app。
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$Adb = 'adb'
)
$ErrorActionPreference = 'Stop'
$taskOutput = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../build/stage2'))
New-Item -ItemType Directory -Force $taskOutput | Out-Null
$prepareOutput = Join-Path $taskOutput 'force-stop-prepare.txt'
$prepareError = Join-Path $taskOutput 'force-stop-prepare-error.txt'
$runner = 'com.ttxz.base.logging.test/androidx.test.runner.AndroidJUnitRunner'
$prepareClass = 'com.ttxz.base.logging.CrashRecoveryDeviceTest#prepareForForceStop'
$prepare = Start-Process -FilePath $Adb -ArgumentList @('-s', $Serial, 'shell', 'am', 'instrument', '-w', '-e', 'class', $prepareClass, '-e', 'crashStage', 'prepare', $runner) -WindowStyle Hidden -PassThru -RedirectStandardOutput $prepareOutput -RedirectStandardError $prepareError
$forceStopped = $false
try {
    $deadline = [DateTime]::UtcNow.AddSeconds(25)
    $ready = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        $ready = (Test-Path -LiteralPath $prepareOutput) -and ((Get-Content -LiteralPath $prepareOutput -Raw) -match 'LOGGER_CRASH_READY')
        if ($ready -or $prepare.HasExited) { break }
        Start-Sleep -Milliseconds 100
    }
    if (-not $ready) { throw '测试未进入 LOGGER_CRASH_READY；不能将此次实验视为强杀验收通过。' }
    & $Adb -s $Serial shell am force-stop com.ttxz.base.logging.test
    if ($LASTEXITCODE -ne 0) { throw '停止独立测试包失败。' }
    $forceStopped = $true
    if (-not $prepare.WaitForExit(5000)) { throw '强杀后 instrumentation 未退出。' }
    $verification = & $Adb -s $Serial shell am instrument -w -e class 'com.ttxz.base.logging.CrashRecoveryDeviceTest#verifyAfterForceStop' -e crashStage verify $runner
    $verification | Tee-Object -FilePath (Join-Path $taskOutput 'force-stop-verify.txt')
    if (($verification -join "`n") -notmatch 'OK \(1 test\)') { throw '强杀后的恢复测试失败。' }
} finally {
    if (-not $forceStopped -and -not $prepare.HasExited) {
        & $Adb -s $Serial shell am force-stop com.ttxz.base.logging.test
        $null = $prepare.WaitForExit(5000)
    }
    $prepare.Dispose()
}
