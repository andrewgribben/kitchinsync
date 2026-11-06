package io.github.andrewgribben.synccloud.sync

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.ContentValues
import android.content.SyncResult
import android.provider.CalendarContract.Calendars
import androidx.core.content.contentValuesOf
import at.bitfire.synctools.icalendar.CalendarUidSplitter
import at.bitfire.synctools.icalendar.ICalendarGenerator
import at.bitfire.synctools.icalendar.ICalendarParser
import at.bitfire.synctools.mapping.calendar.AndroidEventBuilder
import at.bitfire.synctools.mapping.calendar.AndroidEventHandler
import at.bitfire.synctools.mapping.calendar.DefaultProdIdGenerator
import at.bitfire.synctools.storage.LocalStorageClient
import at.bitfire.synctools.storage.calendar.AndroidCalendar
import at.bitfire.synctools.storage.calendar.AndroidCalendarProvider
import at.bitfire.synctools.storage.calendar.AndroidRecurringCalendar
import at.bitfire.synctools.storage.calendar.EventsContract
import io.github.andrewgribben.synccloud.icloud.Calendar
import io.ktor.client.HttpClient
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import net.fortuna.ical4j.model.component.VEvent
import java.io.StringReader
import java.io.StringWriter
import java.util.logging.Logger
import android.provider.CalendarContract.Events

/**
 * Two-way CalDAV sync for one account's enabled calendars (VEVENT only). Mirrors DAVx5's
 * CalendarSyncManager / LocalCalendar approach, trimmed to iCloud's single-collection-per-calendar
 * shape, using the vendored synctools mapping/storage classes for all iCalendar<->ContentProvider work.
 */
class CalendarSyncer(
    private val account: Account,
    providerClient: ContentProviderClient,
    private val httpClient: HttpClient,
) {
    private val logger = Logger.getLogger(javaClass.name)
    private val storage = LocalStorageClient(providerClient)
    private val calendarProvider = AndroidCalendarProvider(account, storage)
    private val eventHandler = AndroidEventHandler(account.name, DefaultProdIdGenerator(PROD_ID))
    private val parser = ICalendarParser()
    private val generator = ICalendarGenerator()
    private val splitter = CalendarUidSplitter<VEvent>()

    /** Reconciles local calendars with [choices], then syncs the content of every enabled one. */
    fun sync(choices: List<Calendar>, syncResult: SyncResult) {
        reconcileCalendars(choices)

        for (choice in choices.filter { it.enabled }) {
            val local = calendarProvider.findFirstCalendar(
                "${Calendars.NAME}=?", arrayOf(choice.url)
            ) ?: continue
            try {
                runBlocking { syncCalendar(local, choice) }
            } catch (e: RemoteConflictException) {
                // already resolved (server wins, we re-fetched) - nothing more to count as an error
                logger.info("Resolved conflict in calendar ${choice.url}: ${e.message}")
            } catch (e: java.io.IOException) {
                syncResult.stats.numIoExceptions++
                logger.warning("I/O error syncing calendar ${choice.url}: ${e.message}")
            } catch (e: Exception) {
                syncResult.stats.numParseExceptions++
                logger.warning("Error syncing calendar ${choice.url}: ${e.message}")
            }
        }
    }

    /** Creates/updates local calendars for enabled choices; deletes local calendars that are no longer enabled. */
    private fun reconcileCalendars(choices: List<Calendar>) {
        val wantedByUrl = choices.filter { it.enabled }.associateBy { it.url }
        val existing = calendarProvider.findCalendars()

        for (local in existing) {
            val url = local.name
            if (url == null || url !in wantedByUrl)
                local.delete() // disabled or removed on server: local-only deletion, never touches iCloud
        }

        for (choice in wantedByUrl.values) {
            val values = contentValuesOf(
                Calendars.ACCOUNT_NAME to account.name,
                Calendars.ACCOUNT_TYPE to account.type,
                Calendars.NAME to choice.url,
                Calendars.CALENDAR_DISPLAY_NAME to choice.displayName,
                Calendars.CALENDAR_COLOR to choice.color,
                Calendars.CALENDAR_ACCESS_LEVEL to
                    if (choice.readOnly) Calendars.CAL_ACCESS_READ else Calendars.CAL_ACCESS_OWNER,
                Calendars.OWNER_ACCOUNT to account.name,
                Calendars.SYNC_EVENTS to 1,
                Calendars.VISIBLE to 1,
                AndroidCalendarProvider.COLUMN_CALENDAR_URL to choice.url,
            )
            val existingLocal = existing.firstOrNull { it.name == choice.url }
            if (existingLocal != null) existingLocal.update(values) else calendarProvider.createCalendar(values)
        }
    }

    private suspend fun syncCalendar(local: AndroidCalendar, choice: Calendar) {
        val remote = RemoteCalendarSync(httpClient, Url(choice.url))
        val recurring = AndroidRecurringCalendar(local)

        if (!choice.readOnly)
            uploadLocalChanges(local, recurring, remote)

        val lastState = RemoteSyncState.decode(local.readSyncState())
        when (SyncPlan.decide(lastState, syncTokenRejected = false)) {
            SyncPlan.SYNC_COLLECTION -> {
                try {
                    val result = remote.listChanges((lastState as RemoteSyncState.Token).value)
                    applyRemoteChanges(local, recurring, remote, result.changed.map { it.url }, result.removed)
                    result.syncToken?.let { local.writeSyncState(RemoteSyncState.Token(it).encode()) }
                } catch (e: InvalidSyncTokenException) {
                    fullListing(local, recurring, remote)
                }
            }
            SyncPlan.FULL_LISTING -> fullListing(local, recurring, remote)
        }
    }

    /** CTag/ETag-comparison fallback: lists every remote member, compares to locally stored ETags. */
    private suspend fun fullListing(local: AndroidCalendar, recurring: AndroidRecurringCalendar, remote: RemoteCalendarSync) {
        val remoteMembers = remote.listAll()
        val localETagsByFileName = mutableMapOf<String, Pair<Long, String?>>() // fileName -> (id, eTag)
        local.iterateEventRows(
            arrayOf(Events._ID, Events._SYNC_ID, EventsContract.COLUMN_ETAG),
            "${Events.ORIGINAL_ID} IS NULL", null
        ) { row ->
            val fileName = row.getAsString(Events._SYNC_ID) ?: return@iterateEventRows
            localETagsByFileName[fileName] = row.getAsLong(Events._ID) to row.getAsString(EventsContract.COLUMN_ETAG)
        }

        val changedUrls = remoteMembers.filter { member ->
            val fileName = member.url.encodedPath.substringAfterLast('/')
            localETagsByFileName[fileName]?.second != member.eTag
        }.map { it.url }

        val remoteFileNames = remoteMembers.map { it.url.encodedPath.substringAfterLast('/') }.toSet()
        val removedFileNames = localETagsByFileName.keys - remoteFileNames

        applyRemoteChanges(local, recurring, remote, changedUrls, emptyList())
        for (fileName in removedFileNames)
            localETagsByFileName[fileName]?.first?.let { recurring.deleteEventAndExceptions(it) }
    }

    private suspend fun applyRemoteChanges(
        local: AndroidCalendar,
        recurring: AndroidRecurringCalendar,
        remote: RemoteCalendarSync,
        changedUrls: List<Url>,
        removedUrls: List<Url>,
    ) {
        for (url in removedUrls) {
            val fileName = url.encodedPath.substringAfterLast('/')
            val main = recurring.findEventAndExceptions("${Events._SYNC_ID}=?", arrayOf(fileName))
            main?.main?.entityValues?.getAsLong(Events._ID)?.let { recurring.deleteEventAndExceptions(it) }
        }

        if (changedUrls.isEmpty()) return
        for (batch in changedUrls.chunked(MULTIGET_BATCH_SIZE)) {
            for (content in remote.download(batch)) {
                val fileName = content.url.encodedPath.substringAfterLast('/')
                val calendar = parser.parse(StringReader(content.body))
                val associated = splitter.associateByUid(calendar, "VEVENT").values.firstOrNull() ?: continue
                val builder = AndroidEventBuilder(local, syncId = fileName, eTag = content.eTag, scheduleTag = null, flags = 0)
                val built = builder.build(associated)

                val existing = recurring.findEventAndExceptions("${Events._SYNC_ID}=?", arrayOf(fileName))
                val existingId = existing?.main?.entityValues?.getAsLong(Events._ID)
                if (existingId != null)
                    recurring.updateEventAndExceptions(existingId, built)
                else
                    recurring.addEventAndExceptions(built)
            }
        }
    }

    /** Uploads dirty (new/changed) events and deletes events marked DELETED, with If-Match/If-None-Match. */
    private suspend fun uploadLocalChanges(local: AndroidCalendar, recurring: AndroidRecurringCalendar, remote: RemoteCalendarSync) {
        // link exception dirty/delete state to their main event before uploading (synctools helper)
        recurring.processDeletedExceptions()
        recurring.processDirtyExceptions()

        // deletions first
        val toDelete = mutableListOf<Pair<Long, String?>>() // id, fileName
        local.iterateEventRows(
            arrayOf(Events._ID, Events._SYNC_ID, EventsContract.COLUMN_ETAG),
            "${Events.DELETED} AND ${Events.ORIGINAL_ID} IS NULL", null
        ) { row -> toDelete += row.getAsLong(Events._ID) to row.getAsString(Events._SYNC_ID) }
        for ((id, fileName) in toDelete) {
            if (fileName != null) {
                try {
                    remote.delete(fileName, ifETag = null)
                } catch (e: RemoteConflictException) {
                    // already changed/removed on server: fine, we wanted it gone locally anyway
                }
            }
            recurring.deleteEventAndExceptions(id)
        }

        // dirty (new or changed) main events, with their exceptions
        val dirtyIds = mutableListOf<Long>()
        local.iterateEventRows(
            arrayOf(Events._ID),
            "${Events.DIRTY} AND NOT ${Events.DELETED} AND ${Events.ORIGINAL_ID} IS NULL", null
        ) { row -> dirtyIds += row.getAsLong(Events._ID) }

        for (id in dirtyIds) {
            val eventAndExceptions = recurring.getById(id) ?: continue
            val mainValues = eventAndExceptions.main.entityValues
            val mapping = eventHandler.mapToVEvents(eventAndExceptions)

            var fileName = mainValues.getAsString(Events._SYNC_ID)
            val isNew = fileName == null
            if (isNew) fileName = "${mapping.uid}.ics"

            val writer = StringWriter()
            generator.write(mapping.associatedEvents, writer)

            val localETag = mainValues.getAsString(EventsContract.COLUMN_ETAG)
            val newETag = try {
                remote.upload(fileName!!, writer.toString(), ifETag = if (isNew) null else localETag)
            } catch (e: RemoteConflictException) {
                // server wins: refetch and let the next sync's download path apply the authoritative version
                null
            }

            local.updateEventRow(
                id,
                contentValuesOf(
                    Events._SYNC_ID to fileName,
                    Events.DIRTY to 0,
                    EventsContract.COLUMN_ETAG to newETag,
                )
            )
        }
    }

    companion object {
        private const val PROD_ID = "-//Synccloud//Synccloud Android//EN"
        private const val MULTIGET_BATCH_SIZE = 30
    }
}
