package org.jupiterns.drivetime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * The backup pipeline's storage half (BACKUP.md). The round-trip test is the one that
 * matters: an archive written by writeArchive must restore on a clean install — settings
 * applied, buffers adopted, app data staged byte-identical to what the SPA pushed.
 * Retention (namesToPrune) and format detection (sniff) are pure and locked separately.
 */
@RunWith(RobolectricTestRunner::class)
class BackupStoreTest {

    private lateinit var ctx: Context
    private lateinit var settings: Settings

    @Before fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("drivetime", Context.MODE_PRIVATE).edit().clear().commit()
        settings = Settings(ctx)
        BackupStore.snapshotFile(ctx).delete()
        BackupStore.stagedFile(ctx).delete()
        BackupStore.preRestoreFile(ctx).delete()
        File(ctx.filesDir, "web_fixes.jsonl").delete()
        File(ctx.filesDir, "web_markers.jsonl").delete()
        File(ctx.filesDir, "web_vehicles.jsonl").delete()
    }

    // ---- naming + retention (pure) ----

    @Test fun archiveNames_sortChronologically_andPruneKeepsTheNewest() {
        val names = listOf(
            "drivetime-backup-2026-07-10-090000.zip",
            "drivetime-backup-2026-07-12-090000.zip",
            "drivetime-backup-2026-07-11-090000.zip",
            "holiday-photos.jpg",                       // stray user file: never a candidate
            "drivetime-backup-2026-06-01-235959.zip",
        )
        val doomed = BackupStore.namesToPrune(names, 2)
        assertEquals(
            listOf("drivetime-backup-2026-07-10-090000.zip", "drivetime-backup-2026-06-01-235959.zip"),
            doomed)
        assertTrue(BackupStore.namesToPrune(names, 10).isEmpty())
        // keep is floored at 1 — a zero/negative setting must never delete everything
        assertEquals(3, BackupStore.namesToPrune(names, 0).size)
    }

    @Test fun archiveName_matchesItsOwnPattern() {
        val name = BackupStore.archiveName(1752332400000L)
        assertTrue(name, BackupStore.isArchiveName(name))
        assertFalse(BackupStore.isArchiveName("drivetime-settings.json"))
        assertFalse(BackupStore.isArchiveName(null))
    }

    // ---- format sniffing (pure) ----

    @Test fun sniff_decidesByBytesNotExtension() {
        assertEquals("zip", BackupStore.sniff(byteArrayOf(0x50, 0x4b)))
        assertEquals("gzip", BackupStore.sniff(byteArrayOf(0x1f, 0x8b.toByte())))
        assertEquals("json", BackupStore.sniff("{\"a\":1}".toByteArray()))
        assertEquals("unknown", BackupStore.sniff(byteArrayOf()))
        assertEquals("unknown", BackupStore.sniff("hello".toByteArray()))
    }

    // ---- snapshot sink ----

    @Test fun snapshotSink_assemblesChunks_gzipped_andStampsFreshness() {
        assertTrue(BackupStore.beginSnapshot(ctx))
        assertTrue(BackupStore.appendChunk("{\"kind\":\"drivetime-data\","))
        assertTrue(BackupStore.appendChunk("\"stores\":{}}"))
        assertTrue(BackupStore.endSnapshot(ctx, settings, 1234L))
        assertEquals(1234L, settings.backupSnapshotAt)
        val text = GZIPInputStream(BackupStore.snapshotFile(ctx).inputStream())
            .readBytes().toString(Charsets.UTF_8)
        assertEquals("{\"kind\":\"drivetime-data\",\"stores\":{}}", text)
        // a chunk with no begin() is refused, not silently dropped
        assertFalse(BackupStore.appendChunk("orphan"))
    }

    // ---- archive round-trip ----

    @Test fun archive_roundTrips_settings_buffers_andAppData() {
        // a populated phone…
        settings.serverUrl = "https://drivetime.example.org"
        settings.deviceToken = "tok-123"
        settings.backupSchedule = "daily"
        settings.backupKeep = 14
        BackupStore.beginSnapshot(ctx)
        BackupStore.appendChunk("{\"kind\":\"drivetime-data\",\"stores\":{\"fixes\":[{\"ts\":1}]}}")
        BackupStore.endSnapshot(ctx, settings, 99L)
        WebFixBuffer.append(ctx, 40.0, -83.0, 100L, 5.0f)
        WebMarkerBuffer.append(ctx, "m-1", 100L, 40.0, -83.0)
        WebVehicleBuffer.append(ctx, 100L, "AA:BB:CC:DD:EE:FF")

        val archive = ByteArrayOutputStream()
        BackupStore.writeArchive(ctx, settings, archive)

        // every expected entry is present
        val entries = mutableSetOf<String>()
        ZipInputStream(archive.toByteArray().inputStream()).use { zip ->
            var e: ZipEntry? = zip.nextEntry
            while (e != null) { entries.add(e.name); e = zip.nextEntry }
        }
        assertEquals(
            setOf("manifest.json", "app.json.gz", "settings.json",
                "pending_fixes.jsonl", "pending_markers.jsonl", "pending_vehicles.jsonl"),
            entries)

        // …restored onto a clean install
        ctx.getSharedPreferences("drivetime", Context.MODE_PRIVATE).edit().clear().commit()
        val fresh = Settings(ctx)
        File(ctx.filesDir, "web_fixes.jsonl").delete()
        File(ctx.filesDir, "web_markers.jsonl").delete()
        File(ctx.filesDir, "web_vehicles.jsonl").delete()
        BackupStore.stagedFile(ctx).delete()

        val r = BackupStore.restore(ctx, fresh, archive.toByteArray().inputStream())
        assertNull(r.error)
        assertEquals("archive", r.kind)
        assertTrue(r.stagedAppData)
        assertTrue("settings restored", r.settingsApplied > 0)
        assertEquals("https://drivetime.example.org", fresh.serverUrl)
        assertEquals("tok-123", fresh.deviceToken)
        assertEquals("daily", fresh.backupSchedule)
        assertEquals(14, fresh.backupKeep)
        // buffers adopted, so the SPA's normal drain repopulates the replica
        assertTrue(WebFixBuffer.pullSince(ctx, 0.0).contains("\"ts\":100"))
        assertTrue(WebMarkerBuffer.pullSince(ctx, 0.0).contains("m-1"))
        assertTrue(WebVehicleBuffer.pullSince(ctx, 0.0).contains("AA:BB:CC:DD:EE:FF"))
        // app data staged byte-identical for the SPA to fetch and import
        assertEquals(
            "{\"kind\":\"drivetime-data\",\"stores\":{\"fixes\":[{\"ts\":1}]}}",
            BackupStore.stagedFile(ctx).readText())
    }

    @Test fun restore_dispatchesABareDataSnapshot_andALegacySettingsFile() {
        // bare data snapshot → staged, no settings touched
        val data = "{\"kind\":\"drivetime-data\",\"schema\":1,\"stores\":{}}"
        val r1 = BackupStore.restore(ctx, settings, data.toByteArray().inputStream())
        assertEquals("data", r1.kind)
        assertTrue(r1.stagedAppData)
        assertEquals(data, BackupStore.stagedFile(ctx).readText())

        // legacy settings JSON → applied natively, nothing staged
        BackupStore.stagedFile(ctx).delete()
        val legacy = "{\"schema\":1,\"server_url\":\"https://x.example.org\",\"interval_sec\":5}"
        val r2 = BackupStore.restore(ctx, settings, legacy.toByteArray().inputStream())
        assertEquals("settings", r2.kind)
        assertFalse(r2.stagedAppData)
        assertEquals("https://x.example.org", settings.serverUrl)
        assertEquals(5, settings.intervalSec)

        // garbage → a readable error, not a crash
        val r3 = BackupStore.restore(ctx, settings, "not a backup".toByteArray().inputStream())
        assertEquals("unknown", r3.kind)
    }

    // ---- half-written archives (the .part rename) ----

    @Test fun tempName_isNeverCountedAsAnArchive_andIsAlwaysPruned() {
        val real = BackupStore.archiveName(1752332400000L)
        val tmp = BackupStore.tempName(real)
        assertTrue(BackupStore.isTempName(tmp))
        // The whole point: a half-written file must not be eligible to be one of the kept N.
        // (It still ends in .zip — a SAF provider appends an extension that matches the MIME
        // type, so the temp-ness has to live in the prefix.)
        assertFalse(BackupStore.isArchiveName(tmp))
        assertTrue(tmp.endsWith(BackupStore.ARCHIVE_SUFFIX))

        val names = listOf(
            "drivetime-backup-2026-07-10-090000.zip",
            "drivetime-backup-2026-07-11-090000.zip",
            "tmp-drivetime-backup-2026-07-12-090000.zip",   // a run that died mid-write
            "holiday-photos.jpg",
        )
        // keep=2 keeps both real archives; the corpse is swept regardless, and the stray
        // user file is still untouchable.
        assertEquals(
            listOf("tmp-drivetime-backup-2026-07-12-090000.zip"),
            BackupStore.namesToPrune(names, 2))
        assertTrue(BackupStore.namesToPrune(names, 2).none { it == "holiday-photos.jpg" })
    }

    // ---- the undo for a restore ----

    @Test fun restore_archivesTheCurrentInstallFirst_andThatArchiveRestores() {
        BackupStore.preRestoreFile(ctx).delete()
        // what's on the phone now
        settings.serverUrl = "https://before.example.org"
        WebFixBuffer.append(ctx, 40.0, -83.0, 100L, 5.0f)
        BackupStore.beginSnapshot(ctx)
        BackupStore.appendChunk("{\"kind\":\"drivetime-data\",\"stores\":{\"before\":1}}")
        BackupStore.endSnapshot(ctx, settings, 1L)

        // the incoming (wrong) archive: a different phone entirely. Settings is a view onto one
        // SharedPreferences, so the archive is built from a state we then put back.
        settings.serverUrl = "https://after.example.org"
        val incoming = ByteArrayOutputStream()
        BackupStore.writeArchive(ctx, settings, incoming)
        settings.serverUrl = "https://before.example.org"

        BackupStore.restore(ctx, settings, incoming.toByteArray().inputStream())
        assertEquals("https://after.example.org", settings.serverUrl)

        val undo = BackupStore.preRestoreFile(ctx)
        assertTrue("pre-restore archive written", undo.exists() && undo.length() > 0)

        // …and it is a real archive: restoring it puts the phone back
        val back = BackupStore.restore(ctx, settings, undo.inputStream())
        assertEquals("archive", back.kind)
        assertEquals("https://before.example.org", settings.serverUrl)
        assertTrue(WebFixBuffer.pullSince(ctx, 0.0).contains("\"ts\":100"))
    }

    @Test fun restore_writesNoUndoForAFileItRefuses() {
        BackupStore.preRestoreFile(ctx).delete()
        val r = BackupStore.restore(ctx, settings, "not a backup".toByteArray().inputStream())
        assertEquals("unknown", r.kind)
        // Nothing was applied, so there is nothing to undo — and writing a multi-MB archive
        // every time someone picks the wrong file would be its own bug.
        assertFalse(BackupStore.preRestoreFile(ctx).exists())
    }
}
