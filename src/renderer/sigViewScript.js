// This runs in the sigView webview
/* global Android */
window.addEventListener('message', (event) => {
  const id = event.id
  const code = Android.readSync(id)
  try {
    // eslint-disable-next-line no-new-func -- running the decipher code it is handed is all this script is for
    const result = new Function(code)()
    Android.resolve(
      id,

      JSON.stringify(result)
    )
  } catch (ex) {
    Android.reject(
      id,
      ex.toString()
    )
  }
})
