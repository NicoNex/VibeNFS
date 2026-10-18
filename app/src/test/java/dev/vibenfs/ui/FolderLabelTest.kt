package dev.vibenfs.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderLabelTest {
    @Test
    fun namesFromLastSegmentWithParentOnClash() {
        val picks = listOf("/srv/nfs4/HDD", "/srv/nfs4/Documenti")
        assertEquals("HDD", folderLabel("/srv/nfs4/HDD", picks, ""))
        assertEquals("Casa Documenti", folderLabel("/srv/nfs4/Documenti", picks, "Casa"))
        val clash = listOf("/a/media", "/b/media")
        assertEquals("a/media", folderLabel("/a/media", clash, ""))
        assertEquals("b/media", folderLabel("/b/media", clash, ""))
    }
}
