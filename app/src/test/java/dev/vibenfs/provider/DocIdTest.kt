package dev.vibenfs.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class DocIdTest {
    @Test
    fun roundTripAndNavigation() {
        val root = DocId.parse("vol-1:/")
        val file = root.child("Film").child("a:b.mkv")
        assertEquals("vol-1:/Film/a:b.mkv", file.toString())
        assertEquals(file, DocId.parse(file.toString()))
        assertEquals("a:b.mkv", file.name)
        assertEquals(DocId("vol-1", "/Film"), file.parent())
        assertEquals(root, file.parent().parent())
    }

    @Test
    fun candidateNamesKeepExtension() {
        assertEquals("a.txt", NfsDocumentsProvider.candidateName("a.txt", 0))
        assertEquals("a (2).txt", NfsDocumentsProvider.candidateName("a.txt", 2))
        assertEquals("notes (1)", NfsDocumentsProvider.candidateName("notes", 1))
        assertEquals(".env (1)", NfsDocumentsProvider.candidateName(".env", 1))
    }
}
