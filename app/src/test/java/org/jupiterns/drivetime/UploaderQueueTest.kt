package org.jupiterns.drivetime

import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import org.junit.Assert.assertEquals
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
}
