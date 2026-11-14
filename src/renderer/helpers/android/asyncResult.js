/**
 * Waits for the result of an async call across the android bridge.
 *
 * The native side stores the payload, records whether it was a resolution or a
 * rejection, and then dispatches `${id}-resolve` or `${id}-reject`. It can easily finish
 * before the listeners below are attached, so the stored outcome is checked as well, and
 * that check has to ask which of the two it was: the payload alone cannot say, and
 * guessing "resolved" turns an error message into a result.
 *
 * Kept clear of the bridge and of `window` so that it can be tested without a device.
 *
 * @param {{ getSyncMessage: (id: string) => string | null, getSyncStatus: (id: string) => string | null }} bridge
 * @param {EventTarget} target where the native side dispatches its events
 * @param {string} id the result of a js interface async function
 * @param {number} [timeoutMs]
 * @returns {Promise<string>}
 */
export function awaitAsyncResultFrom(bridge, target, id, timeoutMs = 60000) {
  return new Promise((resolve, reject) => {
    let settled = false
    let timer = null

    const cleanup = () => {
      target.removeEventListener(`${id}-resolve`, resolveWrapper)
      target.removeEventListener(`${id}-reject`, rejectWrapper)
      if (timer !== null) {
        clearTimeout(timer)
      }
    }

    const finish = (fn, value) => {
      if (settled) {
        return
      }
      settled = true
      cleanup()
      fn(value)
    }

    const resolveWrapper = () => {
      finish(resolve, bridge.getSyncMessage(id))
    }
    const rejectWrapper = () => {
      finish(reject, bridge.getSyncMessage(id))
    }

    target.addEventListener(`${id}-resolve`, resolveWrapper)
    target.addEventListener(`${id}-reject`, rejectWrapper)

    // already finished before we started listening
    const status = bridge.getSyncStatus(id)
    if (status === 'resolve') {
      resolveWrapper()
      return
    }
    if (status === 'reject') {
      rejectWrapper()
      return
    }

    timer = setTimeout(() => {
      bridge.getSyncMessage(id)
      finish(reject, new Error(`Async result timed out: ${id}`))
    }, timeoutMs)
  })
}
