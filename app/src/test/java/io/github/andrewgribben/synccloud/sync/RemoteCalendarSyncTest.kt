package io.github.andrewgribben.synccloud.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class RemoteCalendarSyncTest {

    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun client() = HttpClient(OkHttp) { followRedirects = false }

    private fun sync() = RemoteCalendarSync(client(), Url(server.url("/calendars/home/").toString()))

    private fun multiStatus(body: String) = MockResponse.Builder()
        .code(207)
        .addHeader("Content-Type", "application/xml; charset=utf-8")
        .body(body)
        .build()

    @Test
    fun `sync-collection reports changed, removed members and the new sync-token`() = runTest {
        server.enqueue(
            multiStatus(
                """<?xml version="1.0"?>
                <d:multistatus xmlns:d="DAV:">
                    <d:response>
                        <d:href>/calendars/home/changed.ics</d:href>
                        <d:propstat><d:prop><d:getetag>"etag-2"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
                    </d:response>
                    <d:response>
                        <d:href>/calendars/home/removed.ics</d:href>
                        <d:status>HTTP/1.1 404 Not Found</d:status>
                    </d:response>
                    <d:sync-token>https://icloud.example/sync/2</d:sync-token>
                </d:multistatus>
                """.trimIndent()
            )
        )

        val result = sync().listChanges("https://icloud.example/sync/1")

        assertEquals(1, result.changed.size)
        assertEquals("etag-2", result.changed.first().eTag)
        assertEquals(1, result.removed.size)
        assertTrue(result.removed.first().toString().endsWith("removed.ics"))
        assertEquals("https://icloud.example/sync/2", result.syncToken)

        val request = server.takeRequest()
        assertEquals("REPORT", request.method)
        assertTrue(request.body!!.utf8().contains("https://icloud.example/sync/1"))
    }

    @Test
    fun `invalid sync-token is reported so the caller can fall back to a full listing`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(400)
                .addHeader("Content-Type", "application/xml; charset=utf-8")
                .body(
                    """<?xml version="1.0"?>
                    <d:error xmlns:d="DAV:"><d:valid-sync-token/></d:error>
                    """.trimIndent()
                )
                .build()
        )

        try {
            sync().listChanges("stale-token")
            fail("expected InvalidSyncTokenException")
        } catch (_: InvalidSyncTokenException) {
            // expected
        }
    }

    @Test
    fun `full listing returns href and ETag for every member`() = runTest {
        server.enqueue(
            multiStatus(
                """<?xml version="1.0"?>
                <d:multistatus xmlns:d="DAV:">
                    <d:response>
                        <d:href>/calendars/home/one.ics</d:href>
                        <d:propstat><d:prop><d:getetag>"etag-1"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
                    </d:response>
                </d:multistatus>
                """.trimIndent()
            )
        )

        val members = sync().listAll()

        assertEquals(1, members.size)
        assertEquals("etag-1", members.first().eTag)
    }

    @Test
    fun `upload of a dirty item sends If-Match with the local ETag and returns the new one`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(204)
                .addHeader("ETag", "\"etag-new\"")
                .build()
        )

        val newETag = sync().upload("event.ics", "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n", ifETag = "etag-old")

        assertEquals("etag-new", newETag)
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("\"etag-old\"", request.headers["If-Match"])
        assertNull(request.headers["If-None-Match"])
    }

    @Test
    fun `upload of a new item sends If-None-Match star`() = runTest {
        server.enqueue(MockResponse.Builder().code(201).build())

        sync().upload("new-event.ics", "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n", ifETag = null)

        val request = server.takeRequest()
        assertEquals("*", request.headers["If-None-Match"])
        assertNull(request.headers["If-Match"])
    }

    @Test
    fun `412 on upload is reported as a conflict so the caller re-fetches (server wins)`() = runTest {
        server.enqueue(MockResponse.Builder().code(412).build())

        try {
            sync().upload("event.ics", "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n", ifETag = "stale-etag")
            fail("expected RemoteConflictException")
        } catch (_: RemoteConflictException) {
            // expected
        }
    }

    @Test
    fun `delete sends If-Match with the local ETag`() = runTest {
        server.enqueue(MockResponse.Builder().code(204).build())

        sync().delete("event.ics", ifETag = "etag-old")

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("\"etag-old\"", request.headers["If-Match"])
    }
}
