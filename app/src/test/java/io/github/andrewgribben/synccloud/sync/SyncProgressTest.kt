package io.github.andrewgribben.synccloud.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncProgressTest {

    @Test
    fun `fraction is current over total, clamped to 0 to 1`() {
        assertEquals(0.5f, SyncProgress.State("a", SyncProgress.Phase.CONTACTS, "x", 35, 70).fraction())
        assertEquals(1f, SyncProgress.State("a", SyncProgress.Phase.CONTACTS, "x", 930, 930).fraction())
        // defensive: a stale/over-counted current must not push the bar past full
        assertEquals(1f, SyncProgress.State("a", SyncProgress.Phase.CONTACTS, "x", 1000, 930).fraction())
    }

    @Test
    fun `null total means indeterminate (no fraction yet)`() {
        assertNull(SyncProgress.State("a", SyncProgress.Phase.CALENDARS, "Family", 0, null).fraction())
    }

    @Test
    fun `zero total (empty collection) is also indeterminate, not a division by zero`() {
        assertNull(SyncProgress.State("a", SyncProgress.Phase.CALENDARS, "Family", 0, 0).fraction())
    }

    @Test
    fun `update sets the state and clear only removes it for the matching account`() {
        SyncProgress.update("acct-1", SyncProgress.Phase.CALENDARS, "Family", 1, 4)
        assertEquals("acct-1", SyncProgress.state.value?.accountName)

        SyncProgress.clear("acct-2") // a different account's sync finishing must not wipe this one
        assertEquals("acct-1", SyncProgress.state.value?.accountName)

        SyncProgress.clear("acct-1")
        assertNull(SyncProgress.state.value)
    }
}
