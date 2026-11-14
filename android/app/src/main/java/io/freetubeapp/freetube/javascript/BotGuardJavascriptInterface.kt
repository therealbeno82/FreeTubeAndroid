package io.freetubeapp.freetube.javascript

import android.webkit.JavascriptInterface
import java.util.concurrent.atomic.AtomicBoolean

class BotGuardJavascriptInterface {
  @Volatile var onReturn: ((String) -> Unit)? = null
  @Volatile var onReject: ((String) -> Unit)? = null
  private val settled = AtomicBoolean(false)

  @JavascriptInterface
  fun returnToken(token: String) {
    if (settled.compareAndSet(false, true)) {
      onReturn?.invoke(token)
    }
  }

  @JavascriptInterface
  fun rejectToken(error: String) {
    if (settled.compareAndSet(false, true)) {
      onReject?.invoke(error)
    }
  }
}
