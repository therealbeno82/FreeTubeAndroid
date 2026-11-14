package io.freetubeapp.freetube.javascript

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.media.session.PlaybackState.STATE_PAUSED
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import io.freetubeapp.freetube.activities.FreeTubeActivity
import io.freetubeapp.freetube.helpers.MediaSessionFacade
import io.freetubeapp.freetube.helpers.Promise
import io.freetubeapp.freetube.helpers.WriteMode
import io.freetubeapp.freetube.helpers.getDataDirectory
import io.freetubeapp.freetube.helpers.getFileName
import io.freetubeapp.freetube.helpers.isTelevision
import io.freetubeapp.freetube.helpers.readBytes
import io.freetubeapp.freetube.helpers.readText
import io.freetubeapp.freetube.helpers.resolveAmbiguousUri
import io.freetubeapp.freetube.helpers.writeBytes
import io.freetubeapp.freetube.webviews.BotGuardWebView
import io.freetubeapp.freetube.webviews.FreeTubeWebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

const val DATA_DIRECTORY = "data://"

class FreeTubeJavaScriptInterface(
  private val context: FreeTubeActivity,
  private val webView: FreeTubeWebView
) {
  private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val mediaSession: MediaSessionFacade = MediaSessionFacade(
    context,
    "media_controls",
    { event ->
      webView.dispatchEvent(event)
    },
    { position ->
      webView.dispatchEvent("media-seek", "position", position)
    }
  )
  val jsCommunicator: AsyncJSCommunicator = AsyncJSCommunicator(webView)

  fun release() {
    coroutineScope.cancel()
    mediaSession.release()
    webView.destroyChildren()
  }

  @JavascriptInterface
  fun createMediaSession(title: String, artist: String, duration: Long = 0, thumbnail: String? = null) {
    mediaSession
      .setMetadata(title, artist, duration, thumbnail)
      .setState(STATE_PAUSED, 0)
      .push()
  }

  @JavascriptInterface
  fun updateMediaSessionState(state: String?, position: String? = null) {
    mediaSession
      .setState(
        state?.toInt(),
        position?.toLong()
      )
  }

  @JavascriptInterface
  fun updateMediaSessionData(trackName: String, artist: String, duration: Long, art: String? = null) {
    mediaSession
      .setMetadata(
        trackName,
        artist,
        duration,
        art
      )
  }

  @JavascriptInterface
  fun cancelMediaNotification() {
    mediaSession.cancel()
  }

  @JavascriptInterface
  fun getDirectory(directory: String): String? {
    return if (directory == DATA_DIRECTORY) {
      context.getDataDirectory()
    } else {
      directory
    }
  }

  @JavascriptInterface
  fun revokePermissionForTree(treeUri: String) {
    try {
      context.contentResolver.releasePersistableUriPermission(
        treeUri.toUri(),
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
      )
    } catch (_: Exception) {
    }
  }

  @JavascriptInterface
  fun listFilesInTree(tree: String): String {
    val directory = DocumentFile.fromTreeUri(context, tree.toUri())
    val array = JSONArray()
    directory?.listFiles()?.forEach { file ->
      val obj = JSONObject()
      obj.put("uri", file.uri.toString())
      obj.put("fileName", file.name)
      obj.put("isFile", file.isFile)
      obj.put("isDirectory", file.isDirectory)
      array.put(obj)
    }
    return array.toString()
  }

  @JavascriptInterface
  fun createFileInTree(tree: String, fileName: String): String? {
    val directory = DocumentFile.fromTreeUri(context, tree.toUri())
    return directory?.createFile("*/*", fileName)?.uri?.toString()
  }

  @JavascriptInterface
  fun listFilesInDataDir(): String {
    val directory = context.getDataDirectory()
    return if (directory == null) {
      "[]"
    } else {
      val array = JSONArray()
      File(directory).listFiles()?.forEach { file ->
        val obj = JSONObject()
        obj.put("uri", "$DATA_DIRECTORY${file.name}")
        obj.put("fileName", file.name)
        obj.put("isFile", file.isFile)
        obj.put("isDirectory", file.isDirectory)
        array.put(obj)
      }
      return array.toString()
    }
  }

  @JavascriptInterface
  fun readFile(uri: String): String {
    return Promise(coroutineScope) { resolve, reject ->
      try {
        val file = context.resolveAmbiguousUri(uri)
        if (file != null) {
          val bytes = context.contentResolver.readBytes(file.uri)
          if (bytes == null) {
            reject("File not found from given uri")
          } else {
            resolve(bytes.toString(Charset.forName("utf-8")))
          }
        } else {
          reject("File not found from given uri")
        }
      } catch (ex: Throwable) {
        reject(ex.stackTraceToString())
      }
    }.addJsCommunicator(jsCommunicator)
  }

  @OptIn(ExperimentalEncodingApi::class)
  @JavascriptInterface
  fun writeFile(uri: String, content: String): String {
    return Promise(coroutineScope) { resolve, reject ->
      try {
        val file = context.resolveAmbiguousUri(uri)
        if (file != null) {
          val bytes = if (content.startsWith("data:")) {
            Base64.decode(content.split("base64,")[1])
          } else {
            content.toByteArray()
          }
          context.contentResolver.writeBytes(
            file.uri,
            bytes
          )
          resolve("")
        } else {
          reject("File not found from given uri")
        }
      } catch (ex: Throwable) {
        reject(ex.stackTraceToString())
      }
    }.addJsCommunicator(jsCommunicator)
  }

  @OptIn(ExperimentalEncodingApi::class)
  @JavascriptInterface
  fun appendFile(uri: String, content: String): String {
    return Promise(coroutineScope) { resolve, reject ->
      try {
        val file = context.resolveAmbiguousUri(uri)
        if (file != null) {
          val bytes = if (content.startsWith("data:")) {
            Base64.decode(content.split("base64,")[1])
          } else {
            content.toByteArray()
          }
          context.contentResolver.writeBytes(
            file.uri,
            bytes,
            WriteMode.Append
          )
          resolve("")
        } else {
          reject("File not found from given uri")
        }
      } catch (ex: Throwable) {
        reject(ex.stackTraceToString())
      }
    }.addJsCommunicator(jsCommunicator)
  }

  @JavascriptInterface
  fun requestSaveDialog(fileName: String, fileType: String): String {
    return Promise(coroutineScope) { resolve, reject ->
      context.launchIntent(
        Intent(Intent.ACTION_CREATE_DOCUMENT)
          .addCategory(Intent.CATEGORY_OPENABLE)
          .setType(fileType)
          .putExtra(Intent.EXTRA_TITLE, fileName)
      ).then {
        if (it?.resultCode == Activity.RESULT_CANCELED) {
          resolve("USER_CANCELED")
          return@then
        }
        try {
          val payload = JSONObject()
          payload.put("uri", it?.data?.data)
          resolve(payload)
        } catch (ex: Exception) {
          reject(ex.toString())
        }
      }.`catch` {
        reject(it.toString())
      }
    }.addJsCommunicator(jsCommunicator)
  }

  @JavascriptInterface
  fun requestOpenDialog(fileTypes: String): String {
    return Promise(coroutineScope) { resolve, reject ->
      context.launchIntent(
        Intent(Intent.ACTION_GET_CONTENT)
          .setType("*/*")
          .putExtra(Intent.EXTRA_MIME_TYPES, fileTypes.split(",").toTypedArray())
      ).then {
        if (it?.resultCode == Activity.RESULT_CANCELED) {
          resolve("USER_CANCELED")
          return@then
        }
        try {
          val uri = it?.data?.data
          if (uri != null) {
            val mimeType = context.contentResolver.getType(uri)
            val fileName = context.contentResolver.getFileName(uri)
            val payload = JSONObject()
            payload.put("uri", uri)
            payload.put("type", mimeType)
            payload.put("fileName", fileName)
            resolve(payload)
          } else {
            reject("Uri from intent was null")
          }
        } catch (ex: Exception) {
          reject(ex.toString())
        }
      }.`catch` {
        reject(it.toString())
      }
    }.addJsCommunicator(jsCommunicator)
  }

  @JavascriptInterface
  fun requestDirectoryAccessDialog(): String {
    return Promise(coroutineScope) { resolve, reject ->
      context.launchIntent(
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
      ).then {
        if (it?.resultCode == Activity.RESULT_CANCELED) {
          resolve("USER_CANCELED")
          return@then
        }
        try {
          val uri = it?.data?.data
          if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
              uri,
              Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            resolve(uri)
          } else {
            reject("Uri from intent was null")
          }
        } catch (ex: Exception) {
          reject(ex.toString())
        }
      }.`catch` {
        reject(it.toString())
      }
    }.addJsCommunicator(jsCommunicator)
  }

  @JavascriptInterface
  fun openExternalLink(url: String) {
    val uri = url.toUri()
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") {
      return
    }
    webView.post {
      try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
      } catch (_: Exception) {
      }
    }
  }

  @JavascriptInterface
  fun getLogs(): String {
    val array = JSONArray()
    synchronized(context.state.consoleMessages) {
      for (message in context.state.consoleMessages) {
        array.put(message)
      }
    }
    return array.toString()
  }

  @JavascriptInterface
  fun restart() {
    context.runOnUiThread {
      context.restart()
    }
  }

  @JavascriptInterface
  fun hideSplashScreen() {
    context.state.showSplashScreen = false
  }

  @JavascriptInterface
  fun enableKeepScreenOn() {
    context.setKeepScreenOn(true)
  }

  @JavascriptInterface
  fun disableKeepScreenOn() {
    context.setKeepScreenOn(false)
  }

  @JavascriptInterface
  fun getSyncMessage(promise: String): String? {
    return jsCommunicator.getSyncMessage(promise)
  }

  @JavascriptInterface
  fun getSyncStatus(promise: String): String? {
    return jsCommunicator.getSyncStatus(promise)
  }

  @JavascriptInterface
  fun themeSystemUi(navigationHex: String, statusHex: String, navigationDarkMode: Boolean  = true,  statusDarkMode: Boolean = true) {
    context.themeSystemUi(navigationHex, statusHex, navigationDarkMode, statusDarkMode)
  }

  @JavascriptInterface
  fun getSystemTheme(): String {
    return if (context.state.darkMode) {
      "dark"
    } else {
      "light"
    }
  }

  @JavascriptInterface
  fun isTelevision(): Boolean {
    return context.isTelevision()
  }

  @JavascriptInterface
  fun isDebugBuild(): Boolean {
    return (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
  }

  @JavascriptInterface
  fun isAppPaused(): Boolean {
    return context.state.paused
  }

  @JavascriptInterface
  fun enterPromptMode() {
    webView.post {
      webView.isVerticalScrollBarEnabled = false
      context.state.isInAPrompt = true
    }
  }

  @JavascriptInterface
  fun exitPromptMode() {
    webView.post {
      webView.isVerticalScrollBarEnabled = true
      context.state.isInAPrompt = false
    }
  }

  private var defaultSoftInputMode: Int? = null

  @JavascriptInterface
  fun suppressInputMethod() {
    context.runOnUiThread {
      val window = context.window
      defaultSoftInputMode = window.attributes.softInputMode
      window.setSoftInputMode(
        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
          WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
      )
      val methodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
      methodManager?.hideSoftInputFromWindow(context.currentFocus?.windowToken, 0)
    }
  }

  @JavascriptInterface
  fun restoreInputMethod() {
    context.runOnUiThread {
      defaultSoftInputMode?.let {
        context.window.setSoftInputMode(it)
      }
      defaultSoftInputMode = null
    }
  }

  @JavascriptInterface
  fun setScale(scale: Int) {
    webView.setScale(scale / 100.0, context)
  }

  private fun getBotGuardScript(
    videoId: String,
    sessionContext: String,
    initialAttestationData: String,
    ytConfig: String
  ): String {
    val script = context.assets.readText("botGuardScript.js")
    val functionName = script.split("export{")[1].split(" as default};")[0]
    val exportSection = "export{${functionName} as default};"
    val bakedScript =
      script.replace(exportSection, "; ${functionName}(${JSONObject.quote(videoId)}, $sessionContext, $initialAttestationData, $ytConfig)")
    return bakedScript
  }

  private fun destroyBotGuard(bgWv: BotGuardWebView) {
    try {
      bgWv.destroy()
    } catch (_: Exception) {
    }
  }

  @JavascriptInterface
  fun generatePOToken(
    videoId: String,
    sessionContext: String,
    initialAttestationData: String,
    ytConfig: String
  ): String {
    return Promise(coroutineScope) { resolve, reject ->
      webView.post {
        val finished = AtomicBoolean(false)
        val timeoutHandler = Handler(Looper.getMainLooper())
        var bgWv: BotGuardWebView? = null
        val timeoutRunnable = Runnable {
          if (finished.compareAndSet(false, true)) {
            reject("PO token generation timed out")
            bgWv?.let { destroyBotGuard(it) }
          }
        }
        try {
          val bgScript = getBotGuardScript(videoId, sessionContext, initialAttestationData, ytConfig)
          val created = webView.generateBgWebview()
          bgWv = created
          created.jsInterface.onReturn = { token ->
            if (finished.compareAndSet(false, true)) {
              timeoutHandler.removeCallbacks(timeoutRunnable)
              webView.post {
                resolve(token)
                destroyBotGuard(created)
              }
            }
          }
          created.jsInterface.onReject = { error ->
            if (finished.compareAndSet(false, true)) {
              timeoutHandler.removeCallbacks(timeoutRunnable)
              webView.post {
                reject(error)
                destroyBotGuard(created)
              }
            }
          }
          timeoutHandler.postDelayed(timeoutRunnable, 20000)
          created.loadDataWithBaseURL(
            "https://www.youtube.com/",
            "<!DOCTYPE html>" +
              "<html lang=\"en\">" +
              "<head>" +
              "<title></title>" +
              "</head>" +
              "<body><script>${bgScript}.then((TOKEN_RESULT) => { console.log(`Your potoken is \${TOKEN_RESULT}`); Android.returnToken(TOKEN_RESULT) }).catch((error) => { Android.rejectToken(error.toString()) })</script></body>" +
              "</html>",
            "text/html",
            "utf-8",
            null
          )
        } catch (exception: Exception) {
          if (finished.compareAndSet(false, true)) {
            timeoutHandler.removeCallbacks(timeoutRunnable)
            bgWv?.let { destroyBotGuard(it) }
            reject(exception.message ?: exception.javaClass.name)
          }
        }
      }
    }.addJsCommunicator(jsCommunicator)
  }

  @JavascriptInterface
  fun runDecipherScript(id: String, code: String, timeout: String): String {
    webView.post {
      webView.generateSigWebview()
        .onLoad = {
          jsInterface.jsCommunicator.resolve(id, code)
          dispatchEvent("message", "id", id)
          postDelayed({
            destroyOnce()
          }, timeout.toLongOrNull() ?: 10000L)
      }
    }
    return id
  }
}
