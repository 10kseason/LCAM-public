package dev.lunacam.app.photon

import kotlinx.coroutines.sync.Mutex

/** AUTO and RAW share one CPU/memory-intensive processing slot, separate from capture. */
object PhotonProcessingGate {
    val mutex = Mutex()
}
