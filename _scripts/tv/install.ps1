#Requires -Version 5.1
<#
.SYNOPSIS
  Builds the debug APK, installs it on the FreeTubeTV emulator, and relaunches the app.
  One command for the whole iterate cycle.

  The renderer is packed with the development webpack build, which is quicker to rebuild
  and keeps the log viewer. Pass -Production to pack it the way a release is.

.PARAMETER NoWebpack
  Skip the webpack rebuild (just install the existing APK). Useful when you only changed Kotlin.
.PARAMETER Production
  Pack the renderer with the production webpack build instead of the development one.
.PARAMETER Serial
  Target a specific emulator serial (defaults to the first `emulator-*` device).
#>

$ErrorActionPreference = 'Stop'

# Parse $args, tolerating npm's forwarded `--` delimiter.
$NoWebpack = $false
$Production = $false
$Serial = ''
for ($i = 0; $i -lt $args.Count; $i++) {
  $a = $args[$i]
  if ($a -eq '--' -or [string]::IsNullOrEmpty($a)) { continue }
  if ($a -eq '-NoWebpack') { $NoWebpack = $true; continue }
  if ($a -eq '-Production') { $Production = $true; continue }
  if ($a -eq '-Serial' -or $a -eq '-s') {
    if (($i + 1) -lt $args.Count) { $Serial = $args[++$i] }
    continue
  }
}

$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$sdk  = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$java = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\jdk-17' }
$adb  = "$sdk\platform-tools\adb.exe"
$apk  = "$root\android\app\build\outputs\apk\debug\app-debug.apk"
$bash = 'C:\Program Files\Git\bin\bash.exe'

if (-not (Test-Path $adb)) { throw "adb not found at $adb (is ANDROID_HOME set?)" }
if (-not (Test-Path $bash)) { throw "Git bash not found at $bash (needed to run gradlew)" }

if (-not $NoWebpack) {
  # assets/ is not in git, so on a fresh checkout nothing else puts botGuardScript.js there
  Write-Host '==> Packing BotGuard script (webpack)'
  & $bash -c "cd '$root' && corepack pnpm run pack:botGuardScript:android"
  if ($LASTEXITCODE -ne 0) { throw 'BotGuard script build failed' }

  $pack = if ($Production) { 'pack:android:core' } else { 'pack:android:dev:core' }
  Write-Host "==> Packing renderer (webpack, $pack)"
  & $bash -c "cd '$root' && corepack pnpm run $pack"
  if ($LASTEXITCODE -ne 0) { throw 'webpack build failed' }
}

Write-Host '==> Building debug APK (gradlew)'
& $bash -c "cd '$root/android' && JAVA_HOME='$java' ANDROID_HOME='$sdk' ./gradlew assembleDebug"
if ($LASTEXITCODE -ne 0) { throw 'gradle build failed' }

if (-not (Test-Path $apk)) { throw "APK not found at $apk" }

# Resolve the target emulator serial
if (-not $Serial) {
  $Serial = (& $adb devices | Select-String 'emulator-\d+\s+device' | ForEach-Object { ($_ -split '\s+')[0] } | Select-Object -First 1)
}
if (-not $Serial) { throw 'No booted emulator found. Start it with: & "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd FreeTubeTV' }

Write-Host "==> Installing APK on $Serial"
& $adb -s $Serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'adb install failed' }

Write-Host '==> Relaunching FreeTube'
& $adb -s $Serial shell am force-stop io.freetubeapp.freetube
& $adb -s $Serial shell monkey -p io.freetubeapp.freetube -c android.intent.category.LAUNCHER 1 | Out-Null

Write-Host 'Done. App launched on the emulator.'
