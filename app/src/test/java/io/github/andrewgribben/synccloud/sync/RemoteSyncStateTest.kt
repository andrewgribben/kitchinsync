package io.github.andrewgribben.synccloud.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteSyncStateTest {

    @Test
    fun `token round-trips`() {
        val state = RemoteSyncState.Token("https://example.com/sync/123")
        assertEquals(state, RemoteSyncState.decode(state.encode()))
    }

    @Test
    fun `ctag round-trips`() {
        val state = RemoteSyncState.CTag("\"abc123\"")
        assertEquals(state, RemoteSyncState.decode(state.encode()))
    }

    @Test
    fun `null decodes to null`() {
        assertNull(RemoteSyncState.decode(null))
    }

    @Test
    fun `unrecognised prefix decodes to null so a full listing is forced`() {
        assertNull(RemoteSyncState.decode("garbage"))
    }
}

class SyncPlanTest {

    @Test
    fun `continues sync-collection when a token is stored and wasn't rejected this run`() {
        val plan = SyncPlan.decide(RemoteSyncState.Token("abc"), syncTokenRejected = false)
        assertEquals(SyncPlan.SYNC_COLLECTION, plan)
    }

    @Test
    fun `falls back to full listing when the server rejected the stored token`() {
        val plan = SyncPlan.decide(RemoteSyncState.Token("abc"), syncTokenRejected = true)
        assertEquals(SyncPlan.FULL_LISTING, plan)
    }

    @Test
    fun `falls back to full listing when there is no stored token (first sync, or CTag collection)`() {
        assertEquals(SyncPlan.FULL_LISTING, SyncPlan.decide(null, syncTokenRejected = false))
        assertEquals(SyncPlan.FULL_LISTING, SyncPlan.decide(RemoteSyncState.CTag("v1"), syncTokenRejected = false))
    }
}
