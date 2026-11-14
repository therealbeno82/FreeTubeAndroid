package io.freetubeapp.freetube.helpers

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException

enum class WriteMode {
  Truncate,
  Append
}

fun ContentResolver.readBytes(uri: Uri): ByteArray? {
  return openInputStream(uri)?.use { it.readBytes() }
}

fun ContentResolver.writeBytes(uri: Uri, bytes: ByteArray, writeMode: WriteMode = WriteMode.Truncate) {
  val mode = when (writeMode) {
      WriteMode.Truncate -> "wt"
      WriteMode.Append -> "wa"
  }
  val stream = openOutputStream(uri, mode)
    ?: throw IOException("Unable to open output stream for $uri")
  stream.use {
    it.write(bytes)
    it.flush()
  }
}

fun ContentResolver.getFileName(uri: Uri): String {
  var result: String? = null
  query(uri, null, null, null, null)?.use { cursor ->
    if (cursor.moveToFirst()) {
      val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
      if (index != -1) {
        result = cursor.getString(index)
      }
    }
  }

  return result ?: uri.toString().split(Regex("(/)|(%2F)")).last()
}
