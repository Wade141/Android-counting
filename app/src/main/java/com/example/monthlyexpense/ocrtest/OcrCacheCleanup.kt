package com.example.monthlyexpense.ocrtest

import java.io.File

/** Process death skips finally; clean only this feature's old, direct-child cache files. */
internal fun clearAbandonedOcrImages(cacheDir: File, createdBefore: Long) {
    cacheDir.listFiles()?.forEach { file ->
        if (file.isFile && file.name.startsWith("ocr-test-") && file.name.endsWith(".image") &&
            file.lastModified() < createdBefore) file.delete()
    }
}
