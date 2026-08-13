package com.hulk.pillsapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallbackOwnerGateTest {
    @Test
    fun newInstanceRejectsOldEventsAndOldDestroyButAcceptsCurrentEvents() {
        val gate = CallbackOwnerGate()
        val oldService = Any()
        val newService = Any()

        gate.register(oldService)
        assertEquals(CallbackOwnerAccess.CURRENT, gate.claimOrCheck(oldService))

        gate.register(newService)
        assertEquals(CallbackOwnerAccess.STALE, gate.claimOrCheck(oldService))
        assertFalse(gate.clear(oldService))
        assertEquals(CallbackOwnerAccess.CURRENT, gate.claimOrCheck(newService))
        assertTrue(gate.hasOwner())

        assertTrue(gate.clear(newService))
        assertEquals(CallbackOwnerAccess.RECOVERED, gate.claimOrCheck(newService))
    }
}
