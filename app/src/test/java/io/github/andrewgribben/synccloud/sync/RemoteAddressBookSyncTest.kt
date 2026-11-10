package io.github.andrewgribben.synccloud.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class RemoteAddressBookSyncTest {

    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun sync() = RemoteAddressBookSync(
        HttpClient(OkHttp) { followRedirects = false },
        Url(server.url("/carddavhome/card/").toString()),
    )

    private fun multiStatus(body: String) = MockResponse.Builder()
        .code(207)
        .addHeader("Content-Type", "application/xml; charset=utf-8")
        .body(body)
        .build()

    @Test
    fun `full listing returns every member's ETag`() = runTest {
        server.enqueue(
            multiStatus(
                """<?xml version="1.0"?>
                <d:multistatus xmlns:d="DAV:">
                    <d:response>
                        <d:href>/carddavhome/card/one.vcf</d:href>
                        <d:propstat><d:prop><d:getetag>"c-etag-1"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
                    </d:response>
                </d:multistatus>
                """.trimIndent()
            )
        )

        assertEquals(listOf("c-etag-1"), sync().listAll().map { it.eTag })
        val request = server.takeRequest()
        assertEquals("REPORT", request.method)
        assertEquals("1", request.headers["Depth"])
    }

    @Test
    fun `download returns the vCard body for each member`() = runTest {
        server.enqueue(
            multiStatus(
                """<?xml version="1.0"?>
                <d:multistatus xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                    <d:response>
                        <d:href>/carddavhome/card/one.vcf</d:href>
                        <d:propstat>
                            <d:prop>
                                <d:getetag>"c-etag-1"</d:getetag>
                                <card:address-data>BEGIN:VCARD
FN:Test
END:VCARD</card:address-data>
                            </d:prop>
                            <d:status>HTTP/1.1 200 OK</d:status>
                        </d:propstat>
                    </d:response>
                </d:multistatus>
                """.trimIndent()
            )
        )

        val items = sync().download(listOf(Url(server.url("/carddavhome/card/one.vcf").toString())))

        assertEquals(1, items.size)
        assertEquals("c-etag-1", items.first().eTag)
        assertEquals(true, items.first().body.contains("FN:Test"))
    }

    @Test
    fun `412 on upload is a conflict (server wins)`() = runTest {
        server.enqueue(MockResponse.Builder().code(412).build())
        try {
            sync().upload("one.vcf", "BEGIN:VCARD\r\nEND:VCARD\r\n", ifETag = "stale")
            fail("expected RemoteConflictException")
        } catch (_: RemoteConflictException) {
            // expected
        }
    }
}
