# FreeTube Android

A soft fork of [FreeTubeAndroid](https://github.com/MarmadileManteater/FreeTubeAndroid),
itself a fork of FreeTube. The renderer is upstream FreeTube's Vue 3 app; the Android
shell replaces Electron's main process with a Kotlin WebView and a JavaScript bridge
(`addJavascriptInterface(..., "Android")`, reached from JS as `import android from 'android'`).

Current focus: making the app usable on Android TV / Google TV with a d-pad remote.

## Building

`pnpm` is **not** on PATH here — go through corepack. Do not run `pnpm run pack:android`:
that script shells out to a nested bare `pnpm`, which fails under corepack. Run its two
sub-scripts directly instead.

```bash
corepack pnpm install --frozen-lockfile
corepack pnpm run pack:botGuardScript:android
corepack pnpm run pack:android:core
```

Webpack writes the bundle into `android/app/src/main/assets/` (gitignored).

Gradle 8.2 supports Java up to 20, so the **Java 23 on PATH will not work**. JDK 17 is
installed and is what CI uses:

```bash
cd android && JAVA_HOME="C:/Program Files/Java/jdk-17" ANDROID_HOME="C:/Users/joelb/AppData/Local/Android/Sdk" ./gradlew assembleDebug
```

Output: `android/app/build/outputs/apk/debug/app-debug.apk` (~18 MB, debug-signed —
uninstall any differently-signed build first).

Only `android/gradlew` exists, no `.bat`, so build from a bash shell.

## Testing

```bash
node src/renderer/helpers/android/spatialNavigation.test.mjs
```

The repo has no test runner and no other tests; this file is plain node with no
dependencies. Node 24 auto-detects ESM in `.js`, which is why it can import the module
directly.

`preview_start` runs the **web** build (`.claude/launch.json` → `node _scripts/dev-runner.js --web`,
port 9080). Useful for general UI work but it does **not** exercise any TV code — all of
it is gated behind `process.env.IS_ANDROID`.

## Television support

Everything is gated on `isTelevision()` so phone and desktop behaviour is untouched.
Verified working on a real TCL Google TV.

**The gate.** `android.isTelevision()` (`FreeTubeJavaScriptInterface.kt`) → `Context.isTelevision()`
(`ContextExtensions.kt`), which checks the leanback system feature *or* the television ui
mode. Two checks because some devices and most emulator images report one but not the
other. Renderer side: `isTelevision()` in `helpers/android/system.js`.

**Launcher.** `AndroidManifest.xml` declares `leanback` and marks touchscreen optional,
adds `LEANBACK_LAUNCHER`, and points `android:banner` at `res/drawable/tv_banner.xml` —
a vector at 160x90dp (= 320x180px xhdpi) reusing the mark from `ic_launcher_foreground`.

**Focus visibility.** FreeTube hides all focus outlines (`.hideOutlines *:focus` in
`themes.css`) until Tab is pressed. A d-pad never sends Tab, so outlines stayed suppressed
for entire sessions. `App.vue` now reveals them on arrow keys too, and on a television
shows them from startup and never hides them on mousedown.

**Spatial navigation** — `helpers/android/spatialNavigation.js`, installed from `App.vue`
only on a television. Arrow keys default to *scrolling* in a web page, so without this a
remote slid the page around instead of moving between controls.

- Candidates that overlap on the perpendicular axis always beat ones off to the side,
  however much closer those are. Distance-based scoring alone is not enough: a neighbour
  a few pixels away but well off to one side beats the item directly below, and focus
  wanders out of its column. Diagonals are only taken when nothing lines up.
- The handler runs in the **capture** phase and calls `stopPropagation()` when it claims a
  key. The video player registers a `document`-level keydown handler that fires on every
  arrow press regardless of focus, so without this you would navigate *and* seek on one
  press.
- It defers to whatever already uses arrows meaningfully — see `ownsArrowKeys()`: text
  fields, selects and sliders; the player on the horizontal keys; `FtPrompt` on the
  horizontal keys, since it already walks its button row and wraps.
- Tuning dials are `PERPENDICULAR_PENALTY` and `EDGE_TOLERANCE` at the top of the module.

**The player** is one navigation destination, not a row of shaka buttons. Its container
takes a `tabindex` on television only; arriving focuses the container and raises the
controls, horizontal keys seek as on desktop, vertical keys leave again. Giving up the
player's volume binding costs nothing: a remote's volume buttons are handled by the set
and never reach the app. Chrome draws no focus ring for programmatic focus on a
`tabindex="-1"` element, hence the explicit `.ftVideoPlayer:focus` rule.

The player auto-enters fullscreen as soon as the video loads, **on every Android device,
not only televisions** — a deliberate exception to the "gated on `isTelevision()`" rule
(`Watch.js` forces `startInFullscreen` under `process.env.IS_ANDROID`, and
`ft-shaka-video-player.js` calls `controls.toggleFullScreen()`; `MainActivity.kt`
intercepts the system Back button to call `FreeTubeWebView.exitFullscreen()`).
On a television that covers the missing on-screen fullscreen button — a remote never had
a way to reach it via spatial navigation. If the Fullscreen API is blocked (requires a
user gesture on some WebViews) the player falls back to `fullWindow`; it waits for
`fullscreenchange` before deciding, because `document.fullscreenElement` is not always
set by the time the request settles.

**Search.** A television has no usable soft keyboard flow for the top-bar search box, so
`TopNav` hides it there and `SideNav` gains a Search entry that opens
`TvSearchOverlay` — a teleported dialog with its own d-pad keyboard
(`TvVirtualKeyboard`). The overlay calls `android.suppressInputMethod()` so the system
keyboard does not open over it. On a television the side nav is always expanded.

Focus inside the overlay is steered by its own `window`-level capturing key handler
(`handleEscape`), which has to sit on `window` to run ahead of spatial navigation's
`document` one:

- It opens with focus on the first key, not the input. A focused input drops its
  suggestions over the top row of keys.
- Up from the top row goes to the input. Spatial navigation alone would pick the input's
  clear button, which is invisible until the input has focus but still a candidate.
- In the input, Up/Down walk the suggestions (`ownsArrowKeys` leaves them to `FtInput`)
  and Enter picks the highlighted one. `FtInput` wraps from the last suggestion to the
  top, so Down from the last one, or with none showing, moves on to the keys instead. The
  move is deferred with `setTimeout`, not `nextTick`: a microtask runs between listeners
  and spatial navigation would then act on the same press.

**Styling.** `src/renderer/styles/tv.css` holds the television look, every rule scoped to
`.app.tv` (`App.vue` sets the class). Its `:root` tokens are unscoped and are what
`TvVirtualKeyboard` is coloured with.

Candidates are collected from `document.fullscreenElement` when there is one, so focus
cannot escape to elements that are not on screen.

### TV emulator

An Android TV AVD (`FreeTubeTV`, tv_1080p, API 36 android-tv x86_64, AEHD-accelerated)
is set up for fast iteration without shuttling APKs to a real TV. It reports
`android.hardware.type.television` + `android.software.leanback`, so `isTelevision()`
is true and every TV code path activates.

- Launch: `& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd FreeTubeTV`
- `pnpm run tv:install` — rebuild webpack (BotGuard script, then the **development**
  renderer build) + gradlew, `adb install -r`, relaunch the app (the whole iterate loop).
  Pass `-- -NoWebpack` to skip the webpack rebuild, `-- -Production` to pack the renderer
  the way a release is.
- `pnpm run tv:run -- up` — send remote key events to the emulator to drive the D-pad
  from the terminal. Supports `up/down/left/right/enter/back/home/search/menu/space/backspace`
  and the alphabet/digits. Multiple keys: `pnpm run tv:run -- down right enter`.
- Scripts live in `_scripts/tv/` (`install.ps1`, `key.ps1`).

### Known gaps

- UI needs adjustment for the ten-foot viewing distance — `uiScaleAndroid` already exists
  as a setting wired to `android.setScale()` and could take a television-specific default.
- Horizontal keys cannot navigate out of the player, since they seek. Down-then-right works
  (inside fullscreen, Up still leaves the player; Back leaves fullscreen).
- No overscan / safe-area padding.

## Fixes applied (2026-08-31)

Applied from the review at https://claude.ai/code/artifact/0d76c1e4-d8c3-4cbc-8802-49a566943e76 — crash / data-loss / ANR fixes, plus the TV windowed-playback fix requested after testing (2026-08-31):

- (superseded 2026-11-13, see below) `android/app/build.gradle.kts:35` `versionCode` now `major*10000000 + minor*100000 + patch*1000` (was `minor*10000000`, so `0.25.2` and `1.24.2` collided).
- `helpers/android/utils.js:97` `versionNumberGt()` strips leading `v` before split, so `v0.25.2` comparisons work.
- `android/app/src/main/java/io/freetubeapp/freetube/KeepAliveService.kt:17` `onBind` returns `null` instead of `TODO()` (was `NotImplementedError` on bind).
- `android/app/src/main/java/io/freetubeapp/freetube/helpers/ContentResolverExtensions.kt:12` `readBytes`/`writeBytes` use `use {}` so streams close on exception.
- `android/app/src/main/java/io/freetubeapp/freetube/javascript/AsyncJSCommunicator.kt:7` `HashMap` → `ConcurrentHashMap` (JavaBridge vs WebView thread race).
- `android/app/src/main/java/io/freetubeapp/freetube/helpers/ContextExtensions.kt:114` `PendingIntent` requestCode `1` for all actions → `action.hashCode()` / `100`/`101`, fixes OEM collision where every media button fired the same intent. `resolveAmbiguousUri` now handles `data://` via `removePrefix` and null `getDataDirectory()`.
- `android/app/src/main/java/io/freetubeapp/freetube/MainActivity.kt:36` `addOnPreDrawListener` now removes itself once splash is hidden (was called every frame).
- `android/app/src/main/java/io/freetubeapp/freetube/MediaControlsReceiver.kt:9` `lateinit var notifyMediaSessionListeners` → nullable `var` with safe `?.invoke` (early broadcast no longer crashes).
- `android/app/src/main/java/io/freetubeapp/freetube/webviews/FreeTubeWebView.kt:63` `youtube_regex` cached per-WebView and `startActivity(Intent.VIEW)` wrapped in try/catch.
- `android/app/src/main/java/io/freetubeapp/freetube/javascript/FreeTubeJavaScriptInterface.kt:121,137` `listFilesInTree`/`listFilesInDataDir` build JSON via `JSONArray/JSONObject` instead of string interpolation (fixes `"` in filenames). Dialog helpers `requestSaveDialog:233`, `requestOpenDialog:255`, `requestDirectoryAccessDialog:284` `return@then` after `USER_CANCELED` to avoid double `resolve()`.
- `src/renderer/helpers/android/storage.js:44` TDZ `ReferenceError` on `writeFile` error path fixed (`key` declared before use, guarded delete); `storage.js:185` `updateFilesInCurrentDataDirectory` writes `directory:` not `uri:` so restart reloads the SAF grant.
- `src/renderer/helpers/android/nedb.js:131` missing `return` on `unlinkAsync`/`mkdirAsync`/`ensureParentDirectoryExistsAsync`/`ensureDatafileIntegrityAsync` added; `crashSafeWriteFileLinesAsync:170` clones `lines` before appending.
- `src/renderer/views/Watch/Watch.js:523` forces `startInFullscreen` when `isTelevision()` is true (first load and route change) — windowed playback unusable with a remote. `src/renderer/components/ft-shaka-video-player/ft-shaka-video-player.js:3096` handles `startInFullscreen` on `IS_ANDROID` + TV via `controls.toggleFullScreen()` with `fullWindow` fallback; `MainActivity.kt:96` intercepts Back to `FreeTubeWebView.exitFullscreen()`; `FreeTubeWebView.kt:40` tracks `fullscreenView`/`fullscreenCallback` correctly and exposes `isInFullscreen`/`exitFullscreen`.

## Fixes applied (2026-11-13)

From the second review, https://claude.ai/artifact/Mge7qR4LvXfjwbXM7LGuPK (all numbered
findings fixed; the cleanups and enhancements it lists are still open):

- **Async bridge outcome.** `AsyncJSCommunicator` records `resolve`/`reject` beside each
  stored message and the bridge exposes it as `getSyncStatus`. Before, a call that finished
  before the page started listening was always treated as resolved, so an error message
  could come back as a result. The waiting logic lives in `helpers/android/asyncResult.js`,
  kept free of the bridge so the test file can cover it.
- **Datafile journal.** `nedb.js` `crashSafeWriteFileLinesAsync` writes the new contents
  to `<name>.db~` (length header first), then the datafile, then empties the journal;
  `ensureDatafileIntegrityAsync` restores from a complete journal on load. So every
  database has an (normally empty) `~` sibling in the data directory — do not delete the
  mechanism as dead files. `readFileAsync` now throws when an existing file cannot be
  read instead of returning `''`, which nedb would have saved back as an empty database.
- **Version code** is `major*100000000 + minor*1000000 + patch*10000 + build`; the build
  fails if a part outgrows its width (build <= 9999) rather than colliding.
- `KeepAliveService` catches a refused `startForeground`; `MediaSessionFacade.decodeArt`
  no longer throws on a malformed URL; `MainActivity` sets window attributes back so the
  cutout mode applies; `FreeTubeWebView` uses safe casts on its parent;
  `WebviewExtensions.dispatchEvent` quotes event names.
- `FtaLogViewer` escapes the log backlog as well as live messages and uses `v-safer-html`.
- Trending restored in `SideNav.vue` (it was dropped when the television search entry was
  added).
- The television Backspace-goes-home shortcut in `App.vue` does nothing while any prompt
  is open (the search overlay counts as one) or a modifier key is held.
- `testAndroid.yml` uses pnpm like the other workflows, builds only `assembleDebug`, and
  no longer lets lint fail silently. Not run since the change — it needs a push to `test`.

## Lint status

As of 2026-11-13 eslint and stylelint both report **zero errors** (eslint has 9
warnings left, all unused variables or undefined globals in upstream files), and
`testAndroid.yml` now fails on a lint error, so keep it that way. The i18n lint rule reads
`static/locales-android/en-US.yaml` as well as `static/locales`, which is where the
Android-only strings live. `sigViewScript.js` carries a deliberate `no-new-func`
disable: running the code it is handed is its job.

The first review (19 findings across the bridge, storage and release pipeline) is at
https://claude.ai/code/artifact/0d76c1e4-d8c3-4cbc-8802-49a566943e76
