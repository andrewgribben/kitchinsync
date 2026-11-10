package io.github.andrewgribben.synccloud.icloud

import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ICloudDiscoveryTest {

    private val caldav = MockWebServer()
    private val partition = MockWebServer() // stands in for pNN-caldav.icloud.com
    private val contacts = MockWebServer()

    @Before
    fun start() {
        caldav.start()
        partition.start()
        contacts.start()
    }

    @After
    fun stop() {
        caldav.close()
        partition.close()
        contacts.close()
    }

    private fun discovery() = ICloudDiscovery(
        caldavUrl = Url(caldav.url("/").toString()),
        carddavUrl = Url(contacts.url("/").toString()),
    )

    private fun multiStatus(fixture: String) = MockResponse.Builder()
        .code(207)
        .addHeader("Content-Type", "application/xml; charset=utf-8")
        .body(javaClass.getResource("/icloud/$fixture")!!.readText())
        .build()

    @Test
    fun `discovers principal, home sets, calendars and address book`() = runTest {
        caldav.enqueue(multiStatus("principal.xml"))
        caldav.enqueue(
            MockResponse.Builder()
                .code(301)
                .addHeader("Location", partition.url("/1234567/principal/").toString())
                .build()
        )
        partition.enqueue(multiStatus("calendar-home-set.xml"))
        partition.enqueue(multiStatus("calendars.xml"))
        contacts.enqueue(multiStatus("principal.xml"))
        contacts.enqueue(multiStatus("addressbook-home-set.xml"))
        contacts.enqueue(multiStatus("addressbooks.xml"))

        val result = discovery().discover("andrew@icloud.com", "abcd-efgh-ijkl-mnop")

        assertEquals(
            DiscoveryResult.Success(
                calendars = listOf(
                    Calendar(
                        url = partition.url("/1234567/calendars/home/").toString(),
                        displayName = "Home",
                        color = 0xFF3B82C4.toInt(),
                        readOnly = false,
                        subscribed = false,
                    ),
                    Calendar(
                        url = partition.url("/1234567/calendars/family/").toString(),
                        displayName = "Family",
                        color = 0xFF8A6D3B.toInt(),
                        readOnly = true,
                        subscribed = false,
                    ),
                    Calendar(
                        url = partition.url("/1234567/calendars/birthdays/").toString(),
                        displayName = "Birthdays",
                        color = 0xFF5CB85C.toInt(),
                        readOnly = true,
                        subscribed = true,
                    ),
                ),
                addressBookUrl = contacts.url("/1234567/carddavhome/card/").toString(),
            ),
            result,
        )
        // subscribed calendars are opt-in
        val enabled = (result as DiscoveryResult.Success).calendars.map { it.enabled }
        assertEquals(listOf(true, true, false), enabled)
    }

    @Test
    fun `sends Basic auth and walks principal then home set then members`() = runTest {
        caldav.enqueue(multiStatus("principal.xml"))
        caldav.enqueue(multiStatus("calendar-home-set.xml"))
        caldav.enqueue(multiStatus("calendars.xml"))
        contacts.enqueue(multiStatus("principal.xml"))
        contacts.enqueue(multiStatus("addressbook-home-set.xml"))
        contacts.enqueue(multiStatus("addressbooks.xml"))

        discovery().discover("andrew@icloud.com", "abcd-efgh-ijkl-mnop")

        val requests = List(3) { caldav.takeRequest() }
        assertEquals(listOf("/", "/1234567/principal/", "/1234567/calendars/"), requests.map { it.url.encodedPath })
        assertEquals(listOf("0", "0", "1"), requests.map { it.headers["Depth"] })
        assertTrue(requests.all { it.method == "PROPFIND" })
        assertTrue(requests.all { it.headers["Authorization"]!!.startsWith("Basic ") })
    }

    @Test
    fun `401 is reported as wrong password`() = runTest {
        caldav.enqueue(
            MockResponse.Builder()
                .code(401)
                .addHeader("WWW-Authenticate", "Basic realm=\"iCloud\"")
                .build()
        )

        assertEquals(DiscoveryResult.WrongPassword, discovery().discover("andrew@icloud.com", "wrong"))
    }

    @Test
    fun `other server errors are reported as failures`() = runTest {
        caldav.enqueue(MockResponse.Builder().code(503).build())

        assertTrue(discovery().discover("andrew@icloud.com", "abcd-efgh-ijkl-mnop") is DiscoveryResult.Failed)
    }
}
