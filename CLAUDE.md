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

On a television the player also auto-enters fullscreen as soon as the video loads
(`Watch.js` forces `startInFullscreen` when `isTelevision()` is true, and
`ft-shaka-video-player.js` calls `controls.toggleFullScreen()`; `MainActivity.kt`
intercepts the system Back button to call `FreeTubeWebView.exitFullscreen()`).
That covers the missing on-screen fullscreen button — a remote never had a way to
reach it via spatial navigation. If the Fullscreen API is blocked (requires a user
gesture on some WebViews) the player falls back to `fullWindow`.

Candidates are collected from `document.fullscreenElement` when there is one, so focus
cannot escape to elements that are not on screen.

### Known gaps

- UI needs adjustment for the ten-foot viewing distance — `uiScaleAndroid` already exists
  as a setting wired to `android.setScale()` and could take a television-specific default.
- Horizontal keys cannot navigate out of the player, since they seek. Down-then-right works
  (inside fullscreen, Up still leaves the player; Back leaves fullscreen).
- No overscan / safe-area padding.

## Fixes applied (2026-08-31)

Applied from the review at https://claude.ai/code/artifact/0d76c1e4-d8c3-4cbc-8802-49a566943e76 — crash / data-loss / ANR fixes, plus the TV windowed-playback fix requested after testing (2026-08-31):

- `android/app/build.gradle.kts:35` `versionCode` now `major*10000000 + minor*100000 + patch*1000` (was `minor*10000000`, so `0.25.2` and `1.24.2` collided).
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

## Pre-existing issues

Not introduced by the television work — do not be alarmed by them:

- `pnpm run lint` **fails on a clean checkout**: an `eqeqeq` error in
  `ft-shaka-video-player.js` and an unused `marked` import in `App.vue`. Unnoticed because
  `testAndroid.yml` runs lint with `continue-on-error: true`.

A fuller review of the codebase (19 findings across the bridge, storage and release
pipeline) is at https://claude.ai/code/artifact/0d76c1e4-d8c3-4cbc-8802-49a566943e76
