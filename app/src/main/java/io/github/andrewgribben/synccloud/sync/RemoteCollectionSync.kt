package io.github.andrewgribben.synccloud.sync

import at.bitfire.dav4jvm.ktor.DavCollection
import at.bitfire.dav4jvm.ktor.MultiStatusItem
import at.bitfire.dav4jvm.ktor.Response
import at.bitfire.dav4jvm.ktor.exception.HttpException
import at.bitfire.dav4jvm.property.webdav.GetETag
import at.bitfire.dav4jvm.property.webdav.SyncToken
import at.bitfire.dav4jvm.property.webdav.WebDAV
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.flow.toList

/** One remote member as reported by a listing/change REPORT: its URL and current ETag. */
data class RemoteMember(val url: Url, val eTag: String)

/** One remote member together with its downloaded body (iCalendar or vCard text), from multiget. */
data class RemoteContent(val url: Url, val eTag: String, val body: String)

/** Outcome of a `sync-collection` REPORT (RFC 6578). */
data class ChangesResult(
    val changed: List<RemoteMember>,
    val removed: List<Url>,
    val syncToken: String?,
    val furtherResults: Boolean,
)

/** Thrown when the server rejected our sync-token (RFC 6578 3.2: `valid-sync-token` precondition). */
class InvalidSyncTokenException(message: String) : Exception(message)

/** Thrown when an upload/delete's conditional header (If-Match/If-None-Match) didn't match: the server wins. */
class RemoteConflictException(message: String) : Exception(message)

/**
 * Turns a `sync-collection` REPORT's flow into a [ChangesResult], throwing [InvalidSyncTokenException]
 * if the server rejected the token (fallback to full listing is the caller's job).
 */
suspend fun collectChanges(flow: kotlinx.coroutines.flow.Flow<MultiStatusItem>): ChangesResult {
    val changed = mutableListOf<RemoteMember>()
    val removed = mutableListOf<Url>()
    var syncToken: String? = null
    var furtherResults = false

    try {
        for (item in flow.toList()) {
            when (item) {
                is MultiStatusItem.ExtraProperty ->
                    (item.property as? SyncToken)?.token?.let { syncToken = it }

                is MultiStatusItem.Response -> {
                    val response = item.response
                    when (response.status) {
                        HttpStatusCode.NotFound, HttpStatusCode.Gone -> removed += response.href
                        else -> response[GetETag::class.java]?.eTag?.let { eTag ->
                            changed += RemoteMember(response.href, eTag)
                        }
                    }
                }
            }
        }
    } catch (e: HttpException) {
        if (e.errors.any { it.name == WebDAV.ValidSyncToken })
            throw InvalidSyncTokenException(e.message ?: "invalid sync-token")
        throw e
    }

    return ChangesResult(changed, removed, syncToken, furtherResults)
}

/** Extracts (href, ETag) pairs from a full listing REPORT (calendar-query / addressbook-query). */
suspend fun collectListing(flow: kotlinx.coroutines.flow.Flow<MultiStatusItem>): List<RemoteMember> =
    flow.toList()
        .filterIsInstance<MultiStatusItem.Response>()
        .mapNotNull { item ->
            item.response[GetETag::class.java]?.eTag?.let { eTag -> RemoteMember(item.response.href, eTag) }
        }

/**
 * Which full-listing members actually need their content downloaded: those whose ETag changed
 * since last sync, or every one of them when [forceAll] is set (used for the one-time contact
 * photo migration: an unchanged ETag would otherwise skip contacts whose vCard was already
 * synced before photo downloading existed).
 */
fun membersNeedingDownload(
    remoteMembers: List<RemoteMember>,
    localETagsByFileName: Map<String, String?>,
    forceAll: Boolean = false,
): List<Url> = remoteMembers
    .filter { forceAll || localETagsByFileName[it.url.encodedPath.substringAfterLast('/')] != it.eTag }
    .map { it.url }

/** Checks a conditional PUT/DELETE's response and turns a 412 into [RemoteConflictException]. */
suspend fun <T> runConditional(block: suspend () -> T): T =
    try {
        block()
    } catch (e: at.bitfire.dav4jvm.ktor.exception.PreconditionFailedException) {
        throw RemoteConflictException("Precondition failed (412): server wins, re-fetching")
    } catch (e: at.bitfire.dav4jvm.ktor.exception.ConflictException) {
        // Some servers report the same situation as 409 (RFC 4791 5.3.2.1 / RFC 6352 6.3.2.1)
        throw RemoteConflictException("Conflict (409): server wins, re-fetching")
    }
