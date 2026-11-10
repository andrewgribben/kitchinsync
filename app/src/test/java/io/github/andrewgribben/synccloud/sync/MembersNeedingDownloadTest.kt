package io.github.andrewgribben.synccloud.sync

import io.ktor.http.Url
import org.junit.Assert.assertEquals
import org.junit.Test

class MembersNeedingDownloadTest {

    private fun member(path: String, eTag: String) = RemoteMember(Url("https://icloud.example$path"), eTag)

    @Test
    fun `unchanged ETag is skipped, changed or new ETag is downloaded`() {
        val remote = listOf(member("/a.vcf", "etag-a-2"), member("/b.vcf", "etag-b"), member("/c.vcf", "etag-c"))
        val local = mapOf("a.vcf" to "etag-a-1", "b.vcf" to "etag-b") // c.vcf is new locally

        val result = membersNeedingDownload(remote, local)

        assertEquals(listOf("a.vcf", "c.vcf"), result.map { it.encodedPath.substringAfterLast('/') })
    }

    @Test
    fun `forceAll downloads every member regardless of matching ETags`() {
        val remote = listOf(member("/a.vcf", "etag-a"), member("/b.vcf", "etag-b"))
        val local = mapOf("a.vcf" to "etag-a", "b.vcf" to "etag-b") // both unchanged

        val result = membersNeedingDownload(remote, local, forceAll = true)

        assertEquals(2, result.size)
    }

    @Test
    fun `no members means nothing to download either way`() {
        assertEquals(emptyList<Url>(), membersNeedingDownload(emptyList(), emptyMap()))
        assertEquals(emptyList<Url>(), membersNeedingDownload(emptyList(), emptyMap(), forceAll = true))
    }
}
