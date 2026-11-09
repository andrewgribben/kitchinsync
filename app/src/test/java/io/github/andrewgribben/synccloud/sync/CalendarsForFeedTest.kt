package io.github.andrewgribben.synccloud.sync

import io.github.andrewgribben.synccloud.icloud.Calendar
import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarsForFeedTest {

    private fun calendar(url: String) = Calendar(url, url, color = null, readOnly = false, subscribed = false)

    @Test
    fun `null feed url returns every enabled calendar unchanged`() {
        val enabled = listOf(calendar("a"), calendar("b"), calendar("c"))

        assertEquals(enabled, calendarsForFeed(enabled, feedUrl = null))
    }

    @Test
    fun `a feed url narrows down to just the matching calendar`() {
        val enabled = listOf(calendar("a"), calendar("b"), calendar("c"))

        assertEquals(listOf(calendar("b")), calendarsForFeed(enabled, feedUrl = "b"))
    }

    @Test
    fun `a feed url that matches nothing (calendar since removed) returns an empty list`() {
        val enabled = listOf(calendar("a"), calendar("b"))

        assertEquals(emptyList<Calendar>(), calendarsForFeed(enabled, feedUrl = "gone"))
    }
}
