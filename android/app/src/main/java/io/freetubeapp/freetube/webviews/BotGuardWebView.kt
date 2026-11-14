package io.freetubeapp.freetube.webviews

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import io.freetubeapp.freetube.javascript.BotGuardJavascriptInterface
import org.json.JSONObject
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class BotGuardWebView @JvmOverloads constructor(
  context: Context, attrs: AttributeSet? = null
) : WebView(context, attrs) {
    val jsInterface = BotGuardJavascriptInterface()
    init {
      @SuppressLint("SetJavaScriptEnabled")
      settings.javaScriptEnabled = true
      settings.allowFileAccess = false
      addJavascriptInterface(jsInterface, "Android")
      webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(
          view: WebView?,
          request: WebResourceRequest?
        ): WebResourceResponse? {
          if (request == null) {
            return null
          }
          val url = request.url.toString()
          if (url.startsWith("data:text/html") || url.startsWith("https://www.youtube.com/api/jnn/v1/GenerateIT")) {
            return super.shouldInterceptRequest(view, request)
          }
          if (request.method != "GET") {
            return super.shouldInterceptRequest(view, request)
          }
          val openConnection = try {
            URL(url).openConnection() as HttpURLConnection
          } catch (_: Exception) {
            return super.shouldInterceptRequest(view, request)
          }
          try {
            openConnection.connectTimeout = 10000
            openConnection.readTimeout = 15000
            openConnection.requestMethod = "GET"
            openConnection.setRequestProperty("Accept-Encoding", "identity")
            val headers = request.requestHeaders
            for (header in headers) {
              if (!header.key.equals("Accept-Encoding", ignoreCase = true)) {
                openConnection.setRequestProperty(header.key, header.value)
              }
            }

            if (url.startsWith("https://www.youtube.com/youtubei/")) {
              openConnection.setRequestProperty("Referer", "https://www.youtube.com/")
              openConnection.setRequestProperty("Origin", "https://www.youtube.com")
              openConnection.setRequestProperty("Sec-Fetch-Site", "same-origin")
              openConnection.setRequestProperty("Sec-Fetch-Mode", "same-origin")
              openConnection.setRequestProperty("X-Youtube-Bootstrap-Logged-In", "false")
            }
            if (url.startsWith("https://www.google.com/js/")) {
              openConnection.setRequestProperty("referer", "https://www.google.com/")
              openConnection.setRequestProperty("origin", "https://www.google.com")
              openConnection.setRequestProperty("Sec-Fetch-Dest", "script")
              openConnection.setRequestProperty("Sec-Fetch-Site", "cross-site")
              openConnection.setRequestProperty("Accept-Language", "*")
            }

            val contentTypeHeader = openConnection.contentType
            val mime = contentTypeHeader?.substringBefore(';')?.trim() ?: "application/octet-stream"
            val charset = contentTypeHeader
              ?.substringAfter("charset=", missingDelimiterValue = "")
              ?.substringBefore(';')
              ?.trim()
              ?.ifBlank { null }
            val stream = DisconnectingInputStream(openConnection, openConnection.inputStream)
            return WebResourceResponse(mime, charset, stream)
          } catch (_: Exception) {
            openConnection.disconnect()
            return super.shouldInterceptRequest(view, request)
          }
        }
      }
    }


  constructor(context: Context, onConsoleMessage: (JSONObject) -> Unit = {}): this(context, null) {
    webChromeClient = ConsoleLogChromeClient(onConsoleMessage)
  }
}

private class DisconnectingInputStream(
  private val connection: HttpURLConnection,
  input: InputStream
) : FilterInputStream(input) {
  override fun close() {
    try {
      super.close()
    } finally {
      connection.disconnect()
    }
  }
}
