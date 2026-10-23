package dev.lunacam.app.photon.raw

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawSingleOutputTest {
    @Test
    fun successfulFusionCommitsOnlyOneResult() = runBlocking {
        val visible = mutableListOf<String>()
        val result = RawSingleOutput.commit("fused", "baseline") { value ->
            visible += value
            value
        }
        assertEquals(listOf("fused"), visible)
        assertTrue(result.usedPreferred)
    }

    @Test
    fun disabledOrRejectedFusionCommitsOnlyBaseline() = runBlocking {
        val visible = mutableListOf<String>()
        val result = RawSingleOutput.commit(null, "baseline") { value ->
            visible += value
            value
        }
        assertEquals(listOf("baseline"), visible)
        assertFalse(result.usedPreferred)
    }

    @Test
    fun failedFusedWriteFallsBackToOneBaseline() = runBlocking {
        val attempts = mutableListOf<String>()
        val visible = mutableListOf<String>()
        val result = RawSingleOutput.commit("fused", "baseline") { value ->
            attempts += value
            if (value == "fused") throw IllegalStateException("Encoder failure before publication")
            visible += value
            value
        }
        assertEquals(listOf("fused", "baseline"), attempts)
        assertEquals(listOf("baseline"), visible)
        assertFalse(result.usedPreferred)
    }

    @Test
    fun failedBothWritesDoesNotPretendAnOutputWasSaved() = runBlocking {
        var attempts = 0
        try {
            RawSingleOutput.commit("fused", "baseline") {
                attempts++
                throw IllegalStateException("No storage")
            }
            throw AssertionError("Expected storage failure")
        } catch (_: IllegalStateException) {
            assertEquals(2, attempts)
        }
    }

    @Test
    fun cancellationNeverStartsAFallbackWrite() = runBlocking {
        var attempts = 0
        try {
            RawSingleOutput.commit("fused", "baseline") {
                attempts++
                throw CancellationException("Activity destroyed")
            }
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(1, attempts)
        }
    }

    @Test
    fun ambiguousPublicationNeverCreatesASecondVisiblePhoto() = runBlocking {
        val visible = mutableListOf<String>()
        try {
            RawSingleOutput.commit("fused", "baseline") {
                visible += it
                throw RawPublicationUncertainException(IllegalStateException("Lost provider reply"))
            }
            throw AssertionError("Expected uncertain publication")
        } catch (_: RawPublicationUncertainException) {
            assertEquals(listOf("fused"), visible)
        }
    }

    @Test
    fun fusedMemoryFailureStillAttemptsOneBaseline() = runBlocking {
        val visible = mutableListOf<String>()
        RawSingleOutput.commit("fused", "baseline") {
            if (it == "fused") throw OutOfMemoryError("Synthetic allocation failure")
            visible += it
            it
        }
        assertEquals(listOf("baseline"), visible)
    }

    @Test fun lostCommitAndInspectionRepliesPreserveOnlyCandidateAndNeverRetry() = runBlocking {
        val visible = mutableListOf<String>()
        var attempts = 0
        try {
            RawSingleOutput.commit("fused", "baseline") { candidate ->
                attempts++
                val guard = RawPublicationGuard()
                try {
                    guard.commit(publish = {
                        visible += candidate
                        throw IllegalStateException("Provider committed, reply lost")
                    }, isVisible = {
                        throw IllegalStateException("Provider temporarily unavailable")
                    })
                    candidate
                } finally {
                    guard.cleanUnpublished { visible.remove(candidate) }
                }
            }
            throw AssertionError("Uncertain result must not be reported as confirmed")
        } catch (_: RawPublicationUncertainException) {
            assertEquals(1, attempts)
            assertEquals(listOf("fused"), visible)
        }
    }

    @Test fun confirmedInvisibleFailedCandidateIsCleanedBeforeSingleFallback() = runBlocking {
        val pending = mutableSetOf<String>()
        val visible = mutableSetOf<String>()
        val saved = RawSingleOutput.commit("fused", "baseline") { candidate ->
            pending += candidate
            val guard = RawPublicationGuard()
            try {
                guard.commit(publish = {
                    if (candidate == "fused") throw IllegalStateException("Commit rejected")
                    visible += candidate
                }, isVisible = { candidate in visible })
                candidate
            } finally {
                guard.cleanUnpublished { pending.remove(candidate) }
            }
        }
        assertEquals("baseline", saved.output)
        assertEquals(setOf("baseline"), pending)
        assertEquals(setOf("baseline"), visible)
    }

    @Test
    fun nextShutterTimeRecordsCapacityReleaseOnce() {
        val job = RawCaptureJob("id", 100, 1000, 850)
        assertEquals(null, job.shutterReadyElapsedMs)
        // Full backlog: raw arrival is not equivalent to an enabled shutter.
        job.markShutterReady(1900)
        job.markShutterReady(2200)
        assertEquals(1900L, job.shutterReadyElapsedMs)
        assertEquals(1800L, job.shutterReadyElapsedMs!! - job.shutterStartedElapsedMs)
    }

    @Test
    fun staleFinalReceiptKeepsReadinessFromCapacityRelease() {
        val finalSnapshot = JSONObject().put("shutterStartedElapsedMs", 100)
            .put("shutterReadyElapsedMs", JSONObject.NULL)
        val released = JSONObject().put("shutterReadyElapsedMs", 1900).put("shutterReadyMs", 1800)
        RawJobJournal.mergeReadiness(finalSnapshot, released)
        assertEquals(1900, finalSnapshot.getLong("shutterReadyElapsedMs"))
        assertEquals(1800, finalSnapshot.getLong("shutterReadyMs"))
    }

    @Test
    fun queuedReceiptReadinessSurvivesLaterSnapshotsWithoutChangingFirstTime() {
        val finalSnapshot = JSONObject().put("shutterStartedElapsedMs", 100)
            .put("shutterReadyElapsedMs", JSONObject.NULL)
        val queued = JSONObject().put("shutterStartedElapsedMs", 100)
            .put("shutterReadyElapsedMs", 850)
        RawJobJournal.mergeReadiness(finalSnapshot, queued)
        RawJobJournal.mergeReadiness(
            finalSnapshot,
            JSONObject().put("shutterReadyElapsedMs", 1900).put("shutterReadyMs", 1800)
        )
        assertEquals(850, finalSnapshot.getLong("shutterReadyElapsedMs"))
        assertEquals(750, finalSnapshot.getLong("shutterReadyMs"))
    }
}
