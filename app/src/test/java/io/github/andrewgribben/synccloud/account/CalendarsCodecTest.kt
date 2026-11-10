package io.github.andrewgribben.synccloud.account

import io.github.andrewgribben.synccloud.icloud.Calendar
import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarsCodecTest {

    private val calendars = listOf(
        Calendar("https://p42-caldav.icloud.com/1/calendars/home/", "Home", 0xFF3B82C4.toInt(), readOnly = false, subscribed = false),
        Calendar("https://p42-caldav.icloud.com/1/calendars/bd/", "Birthdays", null, readOnly = true, subscribed = true),
        Calendar("https://p42-caldav.icloud.com/1/calendars/work/", "Work", 0, readOnly = false, subscribed = false, enabled = false),
    )

    @Test
    fun `round-trips every field`() {
        assertEquals(calendars, CalendarsCodec.decode(CalendarsCodec.encode(calendars)))
    }

    @Test
    fun `missing userdata means no calendars`() {
        assertEquals(emptyList<Calendar>(), CalendarsCodec.decode(null))
    }

    @Test
    fun `ignores fields written by other app versions`() {
        val stored = """[{"url":"u","displayName":"Home","color":null,"readOnly":false,"subscribed":false,"enabled":true,"ctag":"x"}]"""
        assertEquals(listOf(Calendar("u", "Home", null, readOnly = false, subscribed = false)), CalendarsCodec.decode(stored))
    }
}
