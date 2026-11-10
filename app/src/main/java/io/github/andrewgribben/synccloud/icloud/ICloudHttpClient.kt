package io.github.andrewgribben.synccloud.icloud

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.basicAuth
import java.util.concurrent.TimeUnit

/**
 * Timeouts for talking to iCloud's CalDAV/CardDAV endpoints, matching DAVx5's HttpClientBuilder:
 * iCloud's REPORT responses for a calendar/address book with a lot of content can easily take
 * longer than OkHttp's ~10s default, which is what caused every calendar REPORT to time out and
 * leave 0 events synced. AbstractThreadedSyncAdapter cancels a sync after a minute of no traffic,
 * so pingInterval keeps an HTTP/2 connection alive in the meantime.
 */
fun icloudHttpClient(username: String, password: String): HttpClient =
    HttpClient(OkHttp) {
        followRedirects = false // dav4jvm follows redirects itself, keeping track of the new location
        engine {
            config {
                connectTimeout(15, TimeUnit.SECONDS)
                writeTimeout(30, TimeUnit.SECONDS)
                readTimeout(120, TimeUnit.SECONDS)
                pingInterval(45, TimeUnit.SECONDS)
            }
        }
        defaultRequest { basicAuth(username, password) }
    }
