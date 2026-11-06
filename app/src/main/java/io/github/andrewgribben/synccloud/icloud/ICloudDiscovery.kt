package io.github.andrewgribben.synccloud.icloud

import at.bitfire.dav4jvm.Property
import at.bitfire.dav4jvm.ktor.DavResource
import at.bitfire.dav4jvm.ktor.Response
import at.bitfire.dav4jvm.ktor.exception.DavException
import at.bitfire.dav4jvm.ktor.exception.UnauthorizedException
import at.bitfire.dav4jvm.ktor.resolve
import at.bitfire.dav4jvm.ktor.responses
import at.bitfire.dav4jvm.property.caldav.CalDAV
import at.bitfire.dav4jvm.property.caldav.CalendarColor
import at.bitfire.dav4jvm.property.caldav.SupportedCalendarComponentSet
import at.bitfire.dav4jvm.property.carddav.CardDAV
import at.bitfire.dav4jvm.property.common.HrefListProperty
import at.bitfire.dav4jvm.property.webdav.CurrentUserPrincipal
import at.bitfire.dav4jvm.property.webdav.CurrentUserPrivilegeSet
import at.bitfire.dav4jvm.property.webdav.DisplayName
import at.bitfire.dav4jvm.property.webdav.ResourceType
import at.bitfire.dav4jvm.property.webdav.WebDAV
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.basicAuth
import io.ktor.http.Url
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import java.io.IOException

/** A calendar collection on iCloud plus the user's choice whether to sync it. */
@Serializable
data class Calendar(
    val url: String,
    val displayName: String,
    val color: Int?,
    val readOnly: Boolean,
    val subscribed: Boolean,
    val enabled: Boolean = !subscribed,
)

sealed interface DiscoveryResult {
    data class Success(val calendars: List<Calendar>, val addressBookUrl: String?) : DiscoveryResult
    data object WrongPassword : DiscoveryResult
    data class Failed(val message: String) : DiscoveryResult
}

class ICloudDiscovery(
    private val caldavUrl: Url = Url("https://caldav.icloud.com/"),
    private val carddavUrl: Url = Url("https://contacts.icloud.com/"),
) {

    suspend fun discover(appleId: String, password: String): DiscoveryResult =
        HttpClient(OkHttp) {
            followRedirects = false // dav4jvm follows redirects itself, keeping track of the new location
            defaultRequest { basicAuth(appleId, password) }
        }.use { client ->
            try {
                val calendarHome = homeSet(client, caldavUrl, CalDAV.CalendarHomeSet)
                val addressBookHome = homeSet(client, carddavUrl, CardDAV.AddressbookHomeSet)
                DiscoveryResult.Success(
                    calendars = calendars(client, calendarHome),
                    addressBookUrl = members(client, addressBookHome, WebDAV.ResourceType)
                        .firstOrNull { CardDAV.Addressbook in it.types() }
                        ?.href?.toString(),
                )
            } catch (_: UnauthorizedException) {
                DiscoveryResult.WrongPassword
            } catch (e: DavException) {
                DiscoveryResult.Failed(e.message ?: e.toString())
            } catch (e: IOException) {
                DiscoveryResult.Failed(e.message ?: e.toString())
            }
        }

    /** current-user-principal → home set, resolving each href against wherever redirects ended up. */
    private suspend fun homeSet(client: HttpClient, start: Url, homeSet: Property.Name): Url {
        val root = DavResource(client, start)
        val principalHref = root.propfind(0, WebDAV.CurrentUserPrincipal).responses().toList()
            .firstNotNullOfOrNull { it[CurrentUserPrincipal::class.java]?.href }
            ?: throw DavException("No current-user-principal at $start")

        val principal = DavResource(client, root.location.resolve(principalHref)!!)
        val homeHref = principal.propfind(0, homeSet).responses().toList()
            .flatMap { it.properties }
            .firstNotNullOfOrNull { (it as? HrefListProperty)?.hrefs?.firstOrNull() }
            ?: throw DavException("No $homeSet at ${principal.location}")
        return principal.location.resolve(homeHref)!!
    }

    private suspend fun calendars(client: HttpClient, home: Url): List<Calendar> =
        members(
            client, home,
            WebDAV.ResourceType, WebDAV.DisplayName, CalDAV.CalendarColor,
            CalDAV.SupportedCalendarComponentSet, WebDAV.CurrentUserPrivilegeSet,
        ).mapNotNull { response ->
            val types = response.types()
            val subscribed = CalDAV.Subscribed in types
            if (CalDAV.Calendar !in types && !subscribed) return@mapNotNull null
            // A missing component set means "all components" (RFC 4791 5.2.3)
            if (response[SupportedCalendarComponentSet::class.java]?.supportsEvents == false) return@mapNotNull null

            Calendar(
                url = response.href.toString(),
                displayName = response[DisplayName::class.java]?.displayName ?: response.hrefName(),
                color = response[CalendarColor::class.java]?.color,
                readOnly = subscribed || response[CurrentUserPrivilegeSet::class.java]?.mayWriteContent != true,
                subscribed = subscribed,
            )
        }

    private suspend fun members(client: HttpClient, collection: Url, vararg props: Property.Name): List<Response> =
        DavResource(client, collection).propfind(1, *props).responses().toList()

    private fun Response.types() = this[ResourceType::class.java]?.types.orEmpty()
}
