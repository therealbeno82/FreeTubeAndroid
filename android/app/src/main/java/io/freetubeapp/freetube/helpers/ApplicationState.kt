package io.freetubeapp.freetube.helpers

import org.json.JSONObject

data class ApplicationState(
  val consoleMessages: MutableList<JSONObject> = mutableListOf(),
  var showSplashScreen: Boolean = true,
  var darkMode: Boolean = false,
  var paused: Boolean = false,
  var isInAPrompt: Boolean = false,
  var keepScreenOn: Boolean = false,
  var currentPage: String? = null
) {
  fun addConsoleMessage(message: JSONObject) {
    synchronized(consoleMessages) {
      consoleMessages.add(message)
      val overflow = consoleMessages.size - MAX_CONSOLE_MESSAGES
      if (overflow > 0) {
        repeat(overflow) { consoleMessages.removeAt(0) }
      }
    }
  }

  companion object {
    private const val MAX_CONSOLE_MESSAGES = 500
  }
}
