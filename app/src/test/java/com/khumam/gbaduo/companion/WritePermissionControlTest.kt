package com.khumam.gbaduo.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WritePermissionControlTest {

    private fun values(state: WritePermission) = mutableMapOf<String, Any?>().also { WritePermissionControl.put(it, state) }

    @Test
    fun onlyAnAllowedPackReadsAsOn() {
        assertEquals(1L, values(WritePermission.ALLOWED)[WritePermissionControl.ALLOWED_KEY])
        for (state in WritePermission.entries - WritePermission.ALLOWED) {
            assertEquals(0L, values(state)[WritePermissionControl.ALLOWED_KEY])
        }
    }

    @Test
    fun theLabelNamesTheState() {
        assertEquals("ALLOWED", values(WritePermission.ALLOWED)[WritePermissionControl.LABEL_KEY])
        assertEquals("DENIED", values(WritePermission.DENIED)[WritePermissionControl.LABEL_KEY])
        assertEquals("NOT ASKED", values(WritePermission.ASK)[WritePermissionControl.LABEL_KEY])
        assertEquals("N/A", values(WritePermission.NONE)[WritePermissionControl.LABEL_KEY])
    }

    @Test
    fun handlesOnlyItsOwnCall() {
        assertTrue(WritePermissionControl.handles("writePermission"))
        assertFalse(WritePermissionControl.handles("ambientView"))
    }
}
