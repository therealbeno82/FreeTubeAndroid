package io.freetubeapp.freetube.webviews

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.WebChromeClient.CustomViewCallback
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.freetubeapp.freetube.R
import io.freetubeapp.freetube.activities.FreeTubeActivity
import io.freetubeapp.freetube.helpers.WindowInsetsControllerWrapper
import io.freetubeapp.freetube.helpers.spoofDesktopUserAgent
import io.freetubeapp.freetube.javascript.FreeTubeJavaScriptInterface
import io.freetubeapp.freetube.javascript.dispatchEvent
import org.json.JSONObject

@SuppressLint("ViewConstructor")
class FreeTubeWebView (
  context: FreeTubeActivity
) : BackgroundPlayWebView(context, null) {
  private val windowInsetsControllerWrapper = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && windowInsetsController != null) {
    WindowInsetsControllerWrapper(windowInsetsController)
  } else {
    WindowInsetsControllerWrapper(context.windowInsetsController)
  }
  val jsInterface = FreeTubeJavaScriptInterface(context, this)

  val onConsoleMessage: (JSONObject) -> Unit = { messageData: JSONObject ->
    context.state.addConsoleMessage(messageData)
    dispatchEvent("console-message", "data", messageData)
  }

  private var fullscreenView: View? = null
  private var fullscreenCallback: CustomViewCallback? = null
  private val childWebViews = mutableListOf<WebView>()

  fun isInFullscreen(): Boolean = fullscreenView != null

  fun exitFullscreen() {
    if (fullscreenView == null) {
      return
    }
    try {
      webChromeClient?.onHideCustomView()
    } catch (_: Exception) {}
  }

  fun destroyChildren() {
    val children = synchronized(childWebViews) {
      val copy = childWebViews.toList()
      childWebViews.clear()
      copy
    }
    for (child in children) {
      try {
        (child.parent as? ViewGroup)?.removeView(child)
        if (child is SigWebView) {
          child.destroyOnce()
        } else {
          child.destroy()
        }
      } catch (_: Exception) {
      }
    }
  }

  init {
    spoofDesktopUserAgent()
    layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
    setBackgroundColor(Color.TRANSPARENT)

    @SuppressLint("SetJavaScriptEnabled")
    settings.javaScriptEnabled = true
    addJavascriptInterface(jsInterface, "Android")

    @Suppress("DEPRECATION")
    settings.allowUniversalAccessFromFileURLs = true
    @Suppress("DEPRECATION")
    settings.allowFileAccessFromFileURLs = true
    settings.mediaPlaybackRequiresUserGesture = false

    webViewClient = object: WebViewClient() {
      override fun onPageFinished(view: WebView?, url: String?) {
        context.state.currentPage = url
        super.onPageFinished(view, url)
      }
      private val youtubeRegex = Regex(context.getString(R.string.youtube_regex))

      override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        if (request?.url?.scheme == "file") {
          return true
        }

        val urlString = request?.url?.toString()
        if (urlString != null && youtubeRegex.containsMatchIn(urlString)) {
          dispatchEvent("youtube-link", "link", urlString)
          return true
        }
        try {
          context.startActivity(
            Intent(Intent.ACTION_VIEW, request?.url)
          )
        } catch (_: Exception) {
        }
        return true
      }
    }

    webChromeClient = object: ConsoleLogChromeClient({ message ->
      onConsoleMessage(message)
    }) {
      override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if (fullscreenView != null) {
          onHideCustomView()
        }
        if (view != null) {
          val viewGroup = parent as? ViewGroup
          if (viewGroup == null) {
            // nowhere to put it, so tell the page that fullscreen did not happen
            callback?.onCustomViewHidden()
            return
          }

          viewGroup.fitsSystemWindows = false

          windowInsetsControllerWrapper.hide(WindowInsetsCompat.Type.systemBars())
          windowInsetsControllerWrapper.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

          viewGroup.addView(view)
          fullscreenView = view
          fullscreenCallback = callback
          dispatchEvent("start-fullscreen")
        }
      }

      override fun onHideCustomView() {
        if (fullscreenView == null) return
        val viewGroup = parent as? ViewGroup

        viewGroup?.fitsSystemWindows = true
        windowInsetsControllerWrapper.show(WindowInsetsCompat.Type.systemBars())

        viewGroup?.removeView(fullscreenView)
        fullscreenView = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        dispatchEvent("end-fullscreen")
      }
    }
  }

  fun generateBgWebview(): BotGuardWebView {
    val view = BotGuardWebView(context, onConsoleMessage)
    synchronized(childWebViews) {
      childWebViews.add(view)
    }
    return view
  }

  fun generateSigWebview(): SigWebView {
    val view = SigWebView(context, jsInterface.jsCommunicator, onConsoleMessage)
    (parent as? ViewGroup)?.addView(view)
    view.visibility = GONE
    synchronized(childWebViews) {
      childWebViews.add(view)
    }
    return view
  }
}
