package dev.vibenfs.nfs

import org.junit.Assert.assertEquals
import org.junit.Test

class V4PathTest {
    @Test
    fun stripsLeadingComponentsTowardThePseudoRoot() {
        assertEquals(listOf("/srv/nfs4/HDD", "/nfs4/HDD", "/HDD"), v4Candidates("/srv/nfs4/HDD"))
        assertEquals(listOf("/media"), v4Candidates("/media/"))
        assertEquals(listOf("/"), v4Candidates("/"))
    }
}
