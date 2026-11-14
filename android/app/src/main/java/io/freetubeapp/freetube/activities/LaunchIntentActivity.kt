package io.freetubeapp.freetube.activities

import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import io.freetubeapp.freetube.helpers.Promise

open class LaunchIntentActivity: AppCompatActivity() {
  private val activityResultListeners: MutableList<(ActivityResult?) -> Unit> = mutableListOf()
  private val activityResultLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
    val listeners = synchronized(activityResultListeners) {
      val copy = activityResultListeners.toList()
      activityResultListeners.clear()
      copy
    }
    for (listener in listeners) {
      listener(it)
    }
  }
  private fun listenForActivityResults(listener: (ActivityResult?) -> Unit) {
    synchronized(activityResultListeners) {
      activityResultListeners.add(listener)
    }
  }
  fun launchIntent(intent: Intent): Promise<ActivityResult?, Exception> {
    return Promise { resolve, reject ->
      runOnUiThread {
        try {
          synchronized(activityResultListeners) {
            if (activityResultListeners.isNotEmpty()) {
              reject(IllegalStateException("Another file dialog is already open"))
              return@runOnUiThread
            }
            listenForActivityResults {
              resolve(it)
            }
          }
          activityResultLauncher.launch(intent)
        } catch (exception: Exception) {
          synchronized(activityResultListeners) {
            activityResultListeners.clear()
          }
          reject(exception)
        }
      }
    }
  }
}
