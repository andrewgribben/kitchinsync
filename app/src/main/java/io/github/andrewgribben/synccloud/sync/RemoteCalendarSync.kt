package io.github.andrewgribben.synccloud.sync

import at.bitfire.dav4jvm.ktor.DavCalendar
import at.bitfire.dav4jvm.property.caldav.CalDAV
import at.bitfire.dav4jvm.property.caldav.CalendarData
import at.bitfire.dav4jvm.property.webdav.GetETag
import at.bitfire.dav4jvm.property.webdav.WebDAV
import io.ktor.client.HttpClient
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList

/**
 * HTTP-only CalDAV operations for one calendar collection. Deliberately has no dependency on
 * [android.provider.CalendarContract] or synctools, so it can be unit-tested with MockWebServer;
 * [CalendarSyncer] is the Android-side orchestrator that calls this and writes to local storage.
 */
class RemoteCalendarSync(private val httpClient: HttpClient, private val collectionUrl: Url) {

    private fun calendar() = DavCalendar(httpClient, collectionUrl)

    /** RFC 6578 incremental sync. Throws [InvalidSyncTokenException] if the server rejected [syncToken]. */
    suspend fun listChanges(syncToken: String?): ChangesResult =
        collectChanges(calendar().reportChanges(syncToken, infiniteDepth = false, limit = null, WebDAV.GetETag))

    /** Full listing fallback: every VEVENT's href + ETag, no content. */
    suspend fun listAll(): List<RemoteMember> =
        collectListing(calendar().calendarQuery(component = "VEVENT", start = null, end = null))

    /** Downloads the full iCalendar body for each of [urls] (batched by the caller). */
    suspend fun download(urls: List<Url>): List<RemoteContent> =
        calendar().multiget(urls, contentType = "text/calendar")
            .filterIsInstance<at.bitfire.dav4jvm.ktor.MultiStatusItem.Response>()
            .toList()
            .mapNotNull { item ->
                val eTag = item.response[GetETag::class.java]?.eTag ?: return@mapNotNull null
                val body = item.response[CalendarData::class.java]?.iCalendar ?: return@mapNotNull null
                RemoteContent(item.response.href, eTag, body)
            }

    /**
     * Creates ([ifETag] == null) or updates ([ifETag] = current local ETag) an event.
     * @return the ETag the server assigned, if returned
     */
    suspend fun upload(fileName: String, iCalendar: String, ifETag: String?): String? = runConditional {
        val memberUrl = collectionUrl.member(fileName)
        val headers = if (ifETag != null)
            headersOf(HttpHeaders.IfMatch, "\"$ifETag\"")
        else
            headersOf(HttpHeaders.IfNoneMatch, "*")
        DavCalendar(httpClient, memberUrl).put(
            body = TextContent(iCalendar, ContentType.parse("text/calendar;charset=utf-8")),
            additionalHeaders = headers,
        ) { response -> response.headers[HttpHeaders.ETag]?.trim('"') }
    }

    suspend fun delete(fileName: String, ifETag: String?) = runConditional {
        val memberUrl = collectionUrl.member(fileName)
        val headers = ifETag?.let { headersOf(HttpHeaders.IfMatch, "\"$it\"") }
        DavCalendar(httpClient, memberUrl).delete(additionalHeaders = headers) { }
    }
}

/** Resolves a member file name against a (possibly non-slash-terminated) collection URL. */
fun Url.member(fileName: String): Url {
    val base = if (encodedPath.endsWith("/")) toString() else "$this/"
    return Url(base + fileName)
}
