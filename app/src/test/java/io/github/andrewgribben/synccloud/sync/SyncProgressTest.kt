package io.github.andrewgribben.synccloud.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncProgressTest {

    @Test
    fun `fraction is current over total, clamped to 0 to 1, when stage is ITEMS`() {
        assertEquals(0.5f, SyncProgress.State("a", SyncProgress.Phase.CONTACTS, SyncProgress.Stage.ITEMS, "x", 35, 70).fraction())
        assertEquals(1f, SyncProgress.State("a", SyncProgress.Phase.CONTACTS, SyncProgress.Stage.ITEMS, "x", 930, 930).fraction())
        // defensive: a stale/over-counted current must not push the bar past full
        assertEquals(1f, SyncProgress.State("a", SyncProgress.Phase.CONTACTS, SyncProgress.Stage.ITEMS, "x", 1000, 930).fraction())
    }

    @Test
    fun `null total means indeterminate (no fraction yet)`() {
        assertNull(SyncProgress.State("a", SyncProgress.Phase.CALENDARS, SyncProgress.Stage.ITEMS, "Family", 0, null).fraction())
    }

    @Test
    fun `zero total (empty collection) is also indeterminate, not a division by zero`() {
        assertNull(SyncProgress.State("a", SyncProgress.Phase.CALENDARS, SyncProgress.Stage.ITEMS, "Family", 0, 0).fraction())
    }

    @Test
    fun `LISTING stage is always indeterminate even with a non-null total`() {
        // "calendar 1 of 3" is a listing position, not a download fraction: must never render as 33% done
        assertNull(SyncProgress.State("a", SyncProgress.Phase.CALENDARS, SyncProgress.Stage.LISTING, "Andrew", 1, 3).fraction())
        assertNull(SyncProgress.State("a", SyncProgress.Phase.CALENDARS, SyncProgress.Stage.LISTING, "Andrew", 3, 3).fraction())
    }

    @Test
    fun `update sets the state and clear only removes it for the matching account`() {
        SyncProgress.update("acct-1", SyncProgress.Phase.CALENDARS, SyncProgress.Stage.LISTING, "Family", 1, 4)
        assertEquals("acct-1", SyncProgress.state.value?.accountName)

        SyncProgress.clear("acct-2") // a different account's sync finishing must not wipe this one
        assertEquals("acct-1", SyncProgress.state.value?.accountName)

        SyncProgress.clear("acct-1")
        assertNull(SyncProgress.state.value)
    }
}
