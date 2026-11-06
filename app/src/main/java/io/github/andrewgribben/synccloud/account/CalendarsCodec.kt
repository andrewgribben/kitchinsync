package io.github.andrewgribben.synccloud.account

import io.github.andrewgribben.synccloud.icloud.Calendar
import kotlinx.serialization.json.Json

/**
 * Calendar choices are stored as JSON in the account's AccountManager userdata rather than in
 * a database: Android deletes them together with the account, so there's nothing to clean up.
 */
object CalendarsCodec {

    // Tolerate fields added or dropped by other app versions
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(calendars: List<Calendar>): String = json.encodeToString(calendars)

    fun decode(stored: String?): List<Calendar> =
        stored?.let { json.decodeFromString<List<Calendar>>(it) }.orEmpty()
}
