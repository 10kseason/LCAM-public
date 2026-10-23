package dev.lunacam.app.photon.raw

import kotlinx.coroutines.CancellationException

/** Do not retry publication when the provider's commit outcome cannot be read back. */
internal class RawPublicationUncertainException(cause: Exception) : Exception(cause)

/** Once publication may have succeeded, cleanup must not destroy the only possible result. */
internal class RawPublicationGuard {
    private var retainCandidate = false

    fun commit(publish: () -> Unit, isVisible: () -> Boolean) {
        try {
            publish()
        } catch (error: Exception) {
            val visible = try {
                isVisible()
            } catch (inspection: Exception) {
                retainCandidate = true
                throw RawPublicationUncertainException(inspection)
            }
            if (!visible) throw error
        }
        retainCandidate = true
    }

    fun cleanUnpublished(remove: () -> Unit) {
        if (!retainCandidate) runCatching(remove)
    }
}

internal data class RawOutputCommit<R>(
    val output: R,
    val usedPreferred: Boolean,
    val preferredFailure: Throwable? = null
)

/** Writer removes an unpublished failure and never throws after a confirmed commit. */
internal object RawSingleOutput {
    suspend fun <T, R> commit(
        preferred: T?,
        fallback: T,
        write: suspend (T) -> R
    ): RawOutputCommit<R> {
        if (preferred == null) return RawOutputCommit(write(fallback), false)
        val failure = try {
            return RawOutputCommit(write(preferred), true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: RawPublicationUncertainException) {
            throw error
        } catch (error: Exception) {
            error
        } catch (error: OutOfMemoryError) {
            error
        }
        return RawOutputCommit(write(fallback), false, failure)
    }
}
