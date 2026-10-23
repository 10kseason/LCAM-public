package dev.lunacam.feature.diagnostics

import android.os.Handler
import android.os.Looper
import dev.lunacam.domain.capability.CapabilityRepository
import dev.lunacam.domain.capability.CapabilitySnapshot
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

sealed interface CapabilityExplorerState {
    data object Idle : CapabilityExplorerState

    data object Loading : CapabilityExplorerState

    data class Ready(val snapshot: CapabilitySnapshot, val isRefreshing: Boolean) :
        CapabilityExplorerState

    data class Failure(val message: String, val cachedSnapshot: CapabilitySnapshot?) :
        CapabilityExplorerState
}

class CapabilityExplorerController(
    private val repository: CapabilityRepository,
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
) : AutoCloseable {
    private val executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "LunaCam-CapabilityExplorer")
        }
    private val generation = AtomicInteger(0)

    fun explore(forceRefresh: Boolean, onState: (CapabilityExplorerState) -> Unit) {
        val activeGeneration = generation.incrementAndGet()
        onState(CapabilityExplorerState.Loading)
        executor.execute {
            var cachedSnapshot: CapabilitySnapshot? = null
            if (!forceRefresh) {
                cachedSnapshot = repository.loadCached()
                cachedSnapshot?.let { cached ->
                    post(
                        activeGeneration,
                        onState,
                        CapabilityExplorerState.Ready(cached, isRefreshing = true)
                    )
                }
            }

            try {
                val liveSnapshot = repository.refresh()
                post(
                    activeGeneration,
                    onState,
                    CapabilityExplorerState.Ready(liveSnapshot, isRefreshing = false)
                )
            } catch (error: Exception) {
                post(
                    activeGeneration,
                    onState,
                    CapabilityExplorerState.Failure(
                        message = "Capability discovery failed (${error.javaClass.simpleName})",
                        cachedSnapshot = cachedSnapshot
                    )
                )
            }
        }
    }

    fun cancel() {
        generation.incrementAndGet()
    }

    override fun close() {
        generation.incrementAndGet()
        executor.shutdownNow()
    }

    private fun post(
        expectedGeneration: Int,
        onState: (CapabilityExplorerState) -> Unit,
        state: CapabilityExplorerState
    ) {
        mainHandler.post {
            if (generation.get() == expectedGeneration) onState(state)
        }
    }
}
