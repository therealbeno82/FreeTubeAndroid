package io.freetubeapp.freetube.javascript

import android.content.Context
import android.webkit.WebView
import org.json.JSONObject
import java.nio.charset.StandardCharsets

fun WebView.fafJS(js: String) {
  post {
    evaluateJavascript(js, null)
  }
}

fun WebView.dispatchEvent(eventName: String) {
  fafJS("window.dispatchEvent(new Event(${JSONObject.quote(eventName)}))")
}

fun WebView.dispatchEvent(eventName: String, event: JSONObject) {
  var js = "var tempVar = new Event(${JSONObject.quote(eventName)});"
  js += "Object.assign(tempVar, $event);"
  js += "window.dispatchEvent(tempVar);"
  fafJS(js)
}

fun WebView.dispatchEvent(eventName: String, keyName: String, data: String) {
  val wrapper = JSONObject()
  wrapper.put(keyName, data)
  dispatchEvent(eventName, wrapper)
}

fun WebView.dispatchEvent(eventName: String, keyName: String, data: Long) {
  val wrapper = JSONObject()
  wrapper.put(keyName, data)
  dispatchEvent(eventName, wrapper)
}

fun WebView.dispatchEvent(eventName: String, keyName: String, data: JSONObject) {
  val wrapper = JSONObject()
  wrapper.put(keyName, data)
  dispatchEvent(eventName, wrapper)
}

fun btoa(message: String): String {
  return "atob(\"${String(
    android.util.Base64.encode(message.toByteArray(), android.util.Base64.NO_WRAP),
    StandardCharsets.UTF_8
  )}\")"
}

fun WebView.consoleLog(message: String, level: String = "log") {
  fafJS("console.$level(${btoa(message)})")
}

fun WebView.setScale(scale: Double, context: Context) {
  post {
    if (scale == 0.0) {
      setInitialScale(0)
    } else {

      val feelsLike =
        context.resources.displayMetrics.widthPixels / context.resources.displayMetrics.density

      val percentageOfWidth = feelsLike / context.resources.displayMetrics.widthPixels

      setInitialScale(((1 / percentageOfWidth) * (scale * 100)).toInt())
    }
  }
}
