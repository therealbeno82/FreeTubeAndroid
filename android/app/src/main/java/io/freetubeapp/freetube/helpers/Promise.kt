package io.freetubeapp.freetube.helpers

import io.freetubeapp.freetube.javascript.AsyncJSCommunicator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID.randomUUID

class Promise<T, G>(coroutineScope: CoroutineScope, runnable: ((T) -> Unit, (G) -> Unit) -> Unit) {
  private val lock = Any()
  private val successListeners: MutableList<(T) -> Unit> = mutableListOf()
  private val errorListeners: MutableList<(G) -> Unit> = mutableListOf()
  private var settled = false
  private var isSuccess = false
  private var successResult: T? = null
  private var errorResult: G? = null
  private val id = "${randomUUID()}"

  constructor(runnable: ((T) -> Unit, (G) -> Unit) -> Unit): this(
    CoroutineScope(SupervisorJob() + Dispatchers.IO),
    runnable
  )

  init {
    coroutineScope.launch {
      try {
        runnable.invoke({ result ->
          notifySuccess(result)
        }, { result ->
          notifyError(result)
        })
      } catch (_: Throwable) {
      }
    }
  }

  fun addJsCommunicator(communicator: AsyncJSCommunicator) : String {
    then {
      communicator.resolve(id, it?.toString() ?: "")
    }
    `catch` {
      communicator.reject(id, "$it")
    }
    return id
  }

  private fun notifySuccess(result: T) {
    val listeners: List<(T) -> Unit>
    synchronized(lock) {
      if (settled) {
        return
      }
      settled = true
      isSuccess = true
      successResult = result
      listeners = successListeners.toList()
      successListeners.clear()
      errorListeners.clear()
    }
    listeners.forEach { listener ->
      listener.invoke(result)
    }
  }

  private fun notifyError(result: G) {
    val listeners: List<(G) -> Unit>
    synchronized(lock) {
      if (settled) {
        return
      }
      settled = true
      isSuccess = false
      errorResult = result
      listeners = errorListeners.toList()
      successListeners.clear()
      errorListeners.clear()
    }
    listeners.forEach { listener ->
      listener.invoke(result)
    }
  }

  fun then(listener: (T) -> Unit): Promise<T, G> {
    val snapshot: Pair<Boolean, T?> = synchronized(lock) {
      if (settled) {
        if (isSuccess) {
          @Suppress("UNCHECKED_CAST")
          Pair(true, successResult as T)
        } else {
          Pair(false, null)
        }
      } else {
        successListeners.add(listener)
        Pair(false, null)
      }
    }
    if (snapshot.first) {
      @Suppress("UNCHECKED_CAST")
      listener(snapshot.second as T)
    }
    return this
  }

  @SuppressWarnings
  fun catch(listener: (G) -> Unit): Promise<T, G> {
    val snapshot: Pair<Boolean, G?> = synchronized(lock) {
      if (settled) {
        if (!isSuccess) {
          @Suppress("UNCHECKED_CAST")
          Pair(true, errorResult as G)
        } else {
          Pair(false, null)
        }
      } else {
        errorListeners.add(listener)
        Pair(false, null)
      }
    }
    if (snapshot.first) {
      @Suppress("UNCHECKED_CAST")
      listener(snapshot.second as G)
    }
    return this
  }
}
