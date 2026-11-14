package io.freetubeapp.freetube.helpers

import android.content.res.AssetManager

fun AssetManager.readText(assetName: String) : String {
  return open(assetName).bufferedReader().use { it.readText() }
}
