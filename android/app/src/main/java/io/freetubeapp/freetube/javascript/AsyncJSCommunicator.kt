package io.freetubeapp.freetube.javascript

import android.webkit.WebView

class AsyncJSCommunicator(givenWebView: WebView) {
  private val webView = givenWebView
  private val syncMessages: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()

  /**
   * whether each stored message is a resolution or a rejection. the event name says so
   * too, but a caller that starts listening after the event has fired only has this
   */
  private val syncStatuses: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()

  /**
   * resolves a js promise given the id
   */
  fun resolve(id: String, message: String) {
    // the message goes in first, so that a visible status always has one to go with it
    syncMessages[id] = message
    syncStatuses[id] = "resolve"
    webView.dispatchEvent("$id-resolve")
  }

  /**
   * rejects a js promise given the id
   */
  fun reject(id: String, message: String) {
    syncMessages[id] = message
    syncStatuses[id] = "reject"
    webView.dispatchEvent("$id-reject")
  }

  fun getSyncMessage(promise: String): String? {
    syncStatuses.remove(promise)
    return syncMessages.remove(promise)
  }

  /**
   * "resolve" or "reject" once the promise has settled, null before then. does not
   * consume the message
   */
  fun getSyncStatus(promise: String): String? {
    return syncStatuses[promise]
  }
}
