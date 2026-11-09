package io.github.andrewgribben.synccloud.sync

import at.bitfire.dav4jvm.ktor.DavAddressBook
import at.bitfire.dav4jvm.ktor.MultiStatusItem
import at.bitfire.dav4jvm.property.carddav.AddressData
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
 * HTTP-only CardDAV operations for the (single, per iCloud account) address book collection.
 * See [RemoteCalendarSync] for why this has no Android/synctools dependency.
 */
class RemoteAddressBookSync(private val httpClient: HttpClient, private val collectionUrl: Url) {

    init {
        FixedAddressDataFactory.installOnce()
    }

    private fun addressBook() = DavAddressBook(httpClient, collectionUrl)

    suspend fun listChanges(syncToken: String?): ChangesResult =
        collectChanges(addressBook().reportChanges(syncToken, infiniteDepth = false, limit = null, WebDAV.GetETag))

    suspend fun listAll(): List<RemoteMember> =
        collectListing(addressBook().addressbookQuery())

    suspend fun download(urls: List<Url>): List<RemoteContent> =
        addressBook().multiget(urls, contentType = "text/vcard")
            .filterIsInstance<MultiStatusItem.Response>()
            .toList()
            .mapNotNull { item ->
                val eTag = item.response[GetETag::class.java]?.eTag ?: return@mapNotNull null
                val body = item.response[AddressData::class.java]?.card ?: return@mapNotNull null
                RemoteContent(item.response.href, eTag, body)
            }

    suspend fun upload(fileName: String, vCard: String, ifETag: String?): String? = runConditional {
        val memberUrl = collectionUrl.member(fileName)
        val headers = if (ifETag != null)
            headersOf(HttpHeaders.IfMatch, "\"$ifETag\"")
        else
            headersOf(HttpHeaders.IfNoneMatch, "*")
        DavAddressBook(httpClient, memberUrl).put(
            body = TextContent(vCard, ContentType.parse("text/vcard;charset=utf-8")),
            additionalHeaders = headers,
        ) { response -> response.headers[HttpHeaders.ETag]?.trim('"') }
    }

    suspend fun delete(fileName: String, ifETag: String?) = runConditional {
        val memberUrl = collectionUrl.member(fileName)
        val headers = ifETag?.let { headersOf(HttpHeaders.IfMatch, "\"$it\"") }
        DavAddressBook(httpClient, memberUrl).delete(additionalHeaders = headers) { }
    }
}
