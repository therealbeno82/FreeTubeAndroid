#Requires -Version 5.1
<#
.SYNOPSIS
  Sends remote-control key events to the TV emulator so you can drive FreeTube's
  D-pad navigation from the terminal instead of the emulator window.

.PARAMETER Keys
  One or more key names. Also accepts -Serial <serial> to target a specific device.

.EXAMPLE
  powershell -File _scripts\tv\key.ps1 up
  powershell -File _scripts\tv\key.ps1 down right enter -Serial emulator-5556
#>

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$adb = "$sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { throw "adb not found at $adb" }

$map = @{
  'up'        = 'KEYCODE_DPAD_UP'
  'down'      = 'KEYCODE_DPAD_DOWN'
  'left'      = 'KEYCODE_DPAD_LEFT'
  'right'     = 'KEYCODE_DPAD_RIGHT'
  'enter'     = 'KEYCODE_ENTER'
  'dpadcenter'= 'KEYCODE_DPAD_CENTER'
  'back'      = 'KEYCODE_BACK'
  'home'      = 'KEYCODE_HOME'
  'search'    = 'KEYCODE_SEARCH'
  'menu'      = 'KEYCODE_MENU'
  'space'     = 'KEYCODE_SPACE'
  'backspace' = 'KEYCODE_DEL'
  'esc'       = 'KEYCODE_ESCAPE'
  'tab'       = 'KEYCODE_TAB'
  'play'      = 'KEYCODE_MEDIA_PLAY'
  'pause'     = 'KEYCODE_MEDIA_PAUSE'
  'playpause' = 'KEYCODE_MEDIA_PLAY_PAUSE'
  'volup'     = 'KEYCODE_VOLUME_UP'
  'voldown'   = 'KEYCODE_VOLUME_DOWN'
  'mute'      = 'KEYCODE_VOLUME_MUTE'
  'a'='KEYCODE_A'; 'b'='KEYCODE_B'; 'c'='KEYCODE_C'; 'd'='KEYCODE_D'; 'e'='KEYCODE_E'
  'f'='KEYCODE_F'; 'g'='KEYCODE_G'; 'h'='KEYCODE_H'; 'i'='KEYCODE_I'; 'j'='KEYCODE_J'
  'k'='KEYCODE_K'; 'l'='KEYCODE_L'; 'm'='KEYCODE_M'; 'n'='KEYCODE_N'; 'o'='KEYCODE_O'
  'p'='KEYCODE_P'; 'q'='KEYCODE_Q'; 'r'='KEYCODE_R'; 's'='KEYCODE_S'; 't'='KEYCODE_T'
  'u'='KEYCODE_U'; 'v'='KEYCODE_V'; 'w'='KEYCODE_W'; 'x'='KEYCODE_X'; 'y'='KEYCODE_Y'
  'z'='KEYCODE_Z'
}
foreach ($n in 0..9) { $map["$n"] = "KEYCODE_$n" }

# Pull the keys out of $args, tolerating npm's forwarded `--` delimiter.
$keys = @()
$Serial = ''
for ($i = 0; $i -lt $args.Count; $i++) {
  $a = $args[$i]
  if ($a -eq '--' -or [string]::IsNullOrEmpty($a)) { continue }
  if ($a -eq '-Serial' -or $a -eq '-s') {
    if (($i + 1) -lt $args.Count) { $Serial = $args[++$i] }
    continue
  }
  $keys += $a
}

if (-not $Serial) {
  $Serial = (& $adb devices | Select-String 'emulator-\d+\s+device' | ForEach-Object { ($_ -split '\s+')[0] } | Select-Object -First 1)
}
if (-not $Serial) { throw 'No booted emulator found.' }

foreach ($k in $keys) {
  $key = $k.ToLower()
  if (-not $map.ContainsKey($key)) { Write-Warning "Unrecognised key '$k'"; continue }
  $code = $map[$key]
  & $adb -s $Serial shell input keyevent $code | Out-Null
  Write-Host "$key -> $code"
}
