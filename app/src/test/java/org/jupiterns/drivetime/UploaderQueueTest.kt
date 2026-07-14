package org.jupiterns.drivetime

import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Lock down the durable queue behaviour: enqueue persists, the cached count stays
 * accurate, and the on-disk file is the line-oriented JSONL we promise. The kill
 * test for the worst regression — "we lost a fix in the queue" — is the count
 * staying exact across enqueue/queuedCount round-trips.
 */
@RunWith(RobolectricTestRunner::class)
class UploaderQueueTest {

    private lateinit var settings: Settings
    private lateinit var uploader: Uploader
    private lateinit var queueFile: File

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Clear any existing queue from a previous test run. The cached count is
        // process-global (companion), so reset it too or it leaks across tests.
        File(ctx.filesDir, "queue.jsonl").delete()
        File(ctx.filesDir, "queue.tmp").delete()
        Uploader.resetQueueCacheForTest()
        // A server URL left by a previous method would silently change what flush() does.
        ctx.getSharedPreferences("drivetime", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        settings = Settings(ctx)
        uploader = Uploader(ctx, settings)
        queueFile = File(ctx.filesDir, "queue.jsonl")
    }

    @Test fun enqueue_persistsOneJsonObjectPerLine() {
        uploader.enqueue(40.0, -75.0, 1700000000L, 12.5f, 5f, 90f)
        uploader.enqueue(40.1, -75.1, 1700000001L, 13.5f, 4f, 92f)
        val lines = queueFile.readLines().filter { it.isNotBlank() }
        assertEquals(2, lines.size)
        for (line in lines) {
            assertTrue("line not JSON-object-shaped: $line",
                line.startsWith("{") && line.endsWith("}"))
        }
    }

    @Test fun queuedCount_isExactAcrossManyEnqueues() {
        repeat(73) { i ->
            uploader.enqueue(40.0 + i * 1e-4, -75.0, 1700000000L + i, null, null, null)
        }
        assertEquals(73, uploader.queuedCount())
    }

    @Test fun queuedCount_seedsFromExistingFileOnFreshUploader() {
        // Pre-populate the queue file, then a *new* Uploader instance must learn
        // the count from disk (the seed-from-disk path that ensureCountLocked covers).
        repeat(10) {
            uploader.enqueue(40.0, -75.0, 1700000000L + it, null, null, null)
        }
        // The cached count is process-global; drop it so the fresh instance is forced
        // to re-seed from the on-disk file rather than read the warm cache.
        Uploader.resetQueueCacheForTest()
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val fresh = Uploader(ctx, Settings(ctx))
        assertEquals(10, fresh.queuedCount())
    }

    @Test fun health_returnsZeroQueueForBrandNewUploader() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        File(ctx.filesDir, "queue.jsonl").delete()
        val h = Uploader(ctx, Settings(ctx)).health()
        assertEquals(0, h.queued)
    }

    // ---- the rewrite path: acked lines leave, everything else stays ----

    /** Point the uploader at a real (mock) server so flush() runs end to end. */
    private fun serve(vararg codes: Int): MockWebServer {
        val server = MockWebServer()
        for (c in codes) server.enqueue(MockResponse().setResponseCode(c))
        server.start()
        settings.serverUrl = server.url("/").toString().removeSuffix("/")
        settings.deviceToken = "tok"
        return server
    }

    @Test fun flush_drainsTheQueue_andLeavesNoTempFileBehind() {
        val server = serve(200)
        try {
            repeat(4) { uploader.enqueue(40.0, -75.0, 1700000000L + it, null, null, null) }
            assertTrue(uploader.flush())
            assertEquals(0, uploader.queuedCount())
            assertEquals(1, server.requestCount)
            // A drained queue is deleted outright, and the temp file the atomic rewrite uses
            // must never be left lying around: a stale queue.tmp is a half-queue waiting to be
            // mistaken for a real one.
            assertFalse(queueFile.exists())
            assertFalse("queue.tmp left behind", File(queueFile.parentFile, "queue.tmp").exists())
        } finally {
            server.shutdown()
        }
    }

    @Test fun flush_keepsTheQueueIntactWhenTheServerRejectsIt() {
        val server = serve(500)
        try {
            repeat(3) { uploader.enqueue(40.0, -75.0, 1700000000L + it, null, null, null) }
            assertFalse(uploader.flush())
            // Verify-before-delete: a failed POST deletes nothing, so the fixes survive to be
            // re-sent. This is the invariant the whole durable queue exists for.
            assertEquals(3, uploader.queuedCount())
            assertEquals(3, queueFile.readLines().filter { it.isNotBlank() }.size)
            assertFalse(File(queueFile.parentFile, "queue.tmp").exists())
        } finally {
            server.shutdown()
        }
    }

    @Test fun flush_neverDropsAFixThatArrivedWhileThePostWasInFlight() {
        val server = serve()
        // A fix recorded *during* the POST is the case the content-matched drop exists for: a
        // count-based one would delete it unsent. The dispatcher appends it at the one moment
        // that is otherwise impossible to stage — while the batch is on the wire.
        var appended = false
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (!appended) {
                    appended = true
                    uploader.enqueue(41.0, -76.0, 1700009999L, null, null, null)
                }
                return MockResponse().setResponseCode(200)
            }
        }
        try {
            repeat(2) { uploader.enqueue(40.0, -75.0, 1700000000L + it, null, null, null) }
            assertTrue(uploader.flush())
            // Two POSTs: the original pair, then the latecomer — which was sent, not discarded.
            assertEquals(2, server.requestCount)
            assertEquals(0, uploader.queuedCount())
            assertFalse(queueFile.exists())
        } finally {
            server.shutdown()
        }
    }
}
