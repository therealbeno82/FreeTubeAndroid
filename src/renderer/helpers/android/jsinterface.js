import android from 'android'
import { awaitAsyncResultFrom } from './asyncResult'

/**
 *
 * @param {string} id the result of a js interface async function
 * @param {number} [timeoutMs]
 * @returns {Promise<string>}
 */
export function awaitAsyncResult(id, timeoutMs = 60000) {
  return awaitAsyncResultFrom(android, window, id, timeoutMs)
}
