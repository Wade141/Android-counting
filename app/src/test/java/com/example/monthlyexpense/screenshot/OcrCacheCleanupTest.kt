package com.example.monthlyexpense.screenshot

import com.example.monthlyexpense.ocrtest.clearAbandonedOcrImages
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OcrCacheCleanupTest {
    @get:Rule val directory = TemporaryFolder()
    @Test fun removesOnlyOwnOrphansOlderThanProcessStart() {
        val orphan = directory.newFile("ocr-test-123.image").apply { writeText("private image"); setLastModified(1000) }
        val unrelated = directory.newFile("receipt.image").apply { setLastModified(1000) }
        val active = directory.newFile("ocr-test-456.image").apply { setLastModified(3000) }
        val subdirectory = directory.newFolder("ocr-test-folder.image")
        clearAbandonedOcrImages(directory.root, 2000)
        assertFalse(orphan.exists())
        assertTrue(unrelated.exists())
        assertTrue(active.exists())
        assertTrue(subdirectory.exists())
    }
}
