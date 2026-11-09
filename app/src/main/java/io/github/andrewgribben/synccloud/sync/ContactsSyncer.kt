package io.github.andrewgribben.synccloud.sync

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.ContentValues
import android.content.SyncResult
import android.provider.ContactsContract.Groups
import android.provider.ContactsContract.RawContacts
import androidx.core.content.contentValuesOf
import at.bitfire.synctools.mapping.contacts.Contact
import at.bitfire.synctools.mapping.contacts.ContactReader
import at.bitfire.synctools.mapping.contacts.ContactWriter
import at.bitfire.synctools.mapping.contacts.PendingMemberships
import at.bitfire.synctools.storage.LocalStorageClient
import at.bitfire.synctools.storage.contacts.AddressContract
import at.bitfire.synctools.storage.contacts.AndroidAddressBook
import at.bitfire.synctools.storage.contacts.AndroidContact
import at.bitfire.synctools.storage.contacts.AndroidGroup
import at.bitfire.synctools.storage.contacts.ContactsBatchOperation
import at.bitfire.synctools.vcard.GroupMethod
import at.bitfire.synctools.vcard.VCardGenerator
import at.bitfire.synctools.vcard.VCardParser
import ezvcard.VCardVersion
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.StringReader
import java.io.StringWriter
import java.util.logging.Logger

/**
 * Downloads a vCard PHOTO's `https://pNN-contacts.icloud.com/...` URL using the account's
 * authenticated client (iCloud serves photos as a separate Basic-auth request, not inline data).
 * Mirrors DAVx5 core's PhotoDownloader.
 */
private class IcloudPhotoDownloader(private val httpClient: HttpClient) : Contact.Downloader {
    private val logger = Logger.getLogger(javaClass.name)

    override suspend fun download(url: String, accepts: String): ByteArray? = try {
        val response = httpClient.get(url) { headers.append(HttpHeaders.Accept, accepts) }
        if (response.status.isSuccess()) response.body<ByteArray>() else null
    } catch (e: Exception) {
        logger.warning("Could not download contact photo from $url: ${e.message}")
        null
    }
}

/**
 * Two-way CardDAV sync for one account's single address book. Groups are mapped to/from vCard4
 * KIND:group + X-ADDRESSBOOKSERVER-MEMBER (DAVx5's "GROUP_VCARDS" method), so apps that read
 * ContactsContract.Groups (e.g. WhatsApp) see the same grouping as iCloud.
 */
class ContactsSyncer(
    private val account: Account,
    context: android.content.Context,
    providerClient: ContentProviderClient,
    private val httpClient: HttpClient,
) {
    private val logger = Logger.getLogger(javaClass.name)
    private val storage = LocalStorageClient(providerClient)
    private val addressBook = AndroidAddressBook(context, account, storage, GroupMethod.GROUP_VCARDS)
    private val parser = VCardParser()
    private val generator = VCardGenerator(VCardVersion.V3_0, includeTrailingSemicolons = true)
    private val photoDownloader = IcloudPhotoDownloader(httpClient)

    /**
     * @param addressBookUrl    the account's single iCloud address book, or null if iCloud has none
     * @param enabled           whether contacts sync is switched on; when false, any pending local
     *                          edits are uploaded first (best effort), then all local contacts/groups
     *                          for this account are removed
     * @param forcePhotoRedownload  re-downloads every contact's content once even if its ETag hasn't
     *                          changed, so contacts synced before photo downloading existed get their
     *                          photo too (see [ICloudAccounts.photosMigrated])
     * @param uploadOnly        when set (from [android.content.ContentResolver.SYNC_EXTRAS_UPLOAD]),
     *                          only uploads local dirty/deleted contacts; skips listing/downloading
     *                          remote changes, so a local edit doesn't also trigger a full resync.
     */
    fun sync(addressBookUrl: String?, enabled: Boolean, syncResult: SyncResult, forcePhotoRedownload: Boolean = false, uploadOnly: Boolean = false) {
        if (!enabled) {
            if (addressBookUrl != null) uploadPendingChangesBeforeRemoval(Url(addressBookUrl))
            removeAllLocalContacts()
            return
        }
        if (addressBookUrl == null) {
            removeAllLocalContacts()
            return
        }

        try {
            runBlocking { syncAddressBook(Url(addressBookUrl), forcePhotoRedownload, uploadOnly) }
        } catch (e: RemoteConflictException) {
            logger.info("Resolved contacts conflict: ${e.message}")
        } catch (e: java.io.IOException) {
            syncResult.stats.numIoExceptions++
            logger.warning("I/O error syncing contacts: ${e.message}")
        } catch (e: Exception) {
            syncResult.stats.numParseExceptions++
            logger.warning("Error syncing contacts: ${e.message}")
        }
    }

    /** Best-effort upload of dirty/deleted contacts before [removeAllLocalContacts] wipes the local copy. */
    private fun uploadPendingChangesBeforeRemoval(addressBookUrl: Url) {
        try {
            uploadLocalChanges(RemoteAddressBookSync(httpClient, addressBookUrl))
        } catch (e: Exception) {
            logger.warning("Could not upload pending local contacts before disabling sync: ${e.message}")
        }
    }

    private fun removeAllLocalContacts() {
        val batch = ContactsBatchOperation(storage)
        addressBook.deleteRawContacts(batch = batch)
        addressBook.deleteGroups(batch = batch)
        batch.commit()
    }

    private suspend fun syncAddressBook(collectionUrl: Url, forcePhotoRedownload: Boolean, uploadOnly: Boolean) {
        val remote = RemoteAddressBookSync(httpClient, collectionUrl)

        uploadLocalChanges(remote)
        // forcePhotoRedownload must still complete the full listing+download even on an
        // upload-triggered sync, otherwise SyncService would mark photosMigrated=true without
        // ever having actually re-downloaded photos.
        if (uploadOnly && !forcePhotoRedownload) return

        // The forced resync always needs the full listing, since sync-collection would only
        // report members that actually changed since the last token.
        val lastState = if (forcePhotoRedownload) null else RemoteSyncState.decode(bytesToString(addressBook.syncState))
        when (SyncPlan.decide(lastState, syncTokenRejected = false)) {
            SyncPlan.SYNC_COLLECTION -> {
                try {
                    val result = remote.listChanges((lastState as RemoteSyncState.Token).value)
                    applyRemoteChanges(remote, result.changed.map { it.url }, result.removed.map { it.encodedPath.substringAfterLast('/') })
                    result.syncToken?.let { addressBook.syncState = RemoteSyncState.Token(it).encode().toByteArray() }
                } catch (e: InvalidSyncTokenException) {
                    fullListing(remote, forcePhotoRedownload)
                }
            }
            SyncPlan.FULL_LISTING -> fullListing(remote, forcePhotoRedownload)
        }

        resolvePendingGroupMemberships()
    }

    private suspend fun fullListing(remote: RemoteAddressBookSync, forceAll: Boolean) {
        val remoteMembers = remote.listAll()
        val localETagsByFileName = mutableMapOf<String, String?>()
        addressBook.queryRawContactRows().toList().forEach { row ->
            row.getAsString(AddressContract.RawContactColumns.FILENAME)?.let { fileName ->
                localETagsByFileName[fileName] = row.getAsString(AddressContract.RawContactColumns.ETAG)
            }
        }
        addressBook.queryGroupRows().toList().forEach { row ->
            row.getAsString(AddressContract.GroupColumns.FILENAME)?.let { fileName ->
                localETagsByFileName[fileName] = row.getAsString(AddressContract.GroupColumns.ETAG)
            }
        }

        val changedUrls = membersNeedingDownload(remoteMembers, localETagsByFileName, forceAll)
        val remoteFileNames = remoteMembers.map { it.url.encodedPath.substringAfterLast('/') }.toSet()
        val removedFileNames = localETagsByFileName.keys - remoteFileNames

        applyRemoteChanges(remote, changedUrls, removedFileNames.toList())
    }

    private suspend fun applyRemoteChanges(remote: RemoteAddressBookSync, changedUrls: List<Url>, removedFileNames: List<String>) {
        for (fileName in removedFileNames) {
            findRawContact(fileName)?.delete()
            findGroup(fileName)?.delete()
        }

        if (changedUrls.isEmpty()) return
        var downloaded = 0
        for (batch in changedUrls.chunked(MULTIGET_BATCH_SIZE)) {
            for (content in remote.download(batch)) {
                val fileName = content.url.encodedPath.substringAfterLast('/')
                val vCard = parser.parse(StringReader(content.body)) ?: continue
                val contact = ContactReader.fromVCard(vCard, photoDownloader)

                if (contact.group) {
                    val existing = findGroup(fileName)
                    if (existing != null) existing.apply { eTag = content.eTag }.update(contact)
                    else AndroidGroup(addressBook, contact, fileName, content.eTag).add()
                } else {
                    val existing = findRawContact(fileName)
                    if (existing != null) existing.apply { eTag = content.eTag }.update(contact)
                    else AndroidContact(addressBook, contact, fileName, content.eTag).add()
                }
                downloaded++
            }
            SyncProgress.update(
                account.name, SyncProgress.Phase.CONTACTS, SyncProgress.Stage.ITEMS,
                "Contacts", downloaded, changedUrls.size,
            )
        }
    }

    /** Resolves every group's [AddressContract.GroupColumns.PENDING_MEMBERS] (set by [applyRemoteChanges]) into actual memberships. */
    private suspend fun resolvePendingGroupMemberships() {
        val batch = ContactsBatchOperation(storage)
        addressBook.queryGroupRows().toList().forEach { row ->
            val pendingRaw = row.getAsString(AddressContract.GroupColumns.PENDING_MEMBERS) ?: return@forEach
            val uids = PendingMemberships.fromString(pendingRaw).uids
            if (uids.isEmpty()) return@forEach
            val groupId = row.getAsLong(Groups._ID)

            for (uid in uids) {
                val rawContactRow = addressBook.getRawContactRowOrNull(
                    "${AddressContract.RawContactColumns.UID}=?", arrayOf(uid)
                ) ?: continue
                AndroidContact(addressBook, rawContactRow).addToGroup(batch, groupId)
            }
            batch += at.bitfire.synctools.storage.BatchOperation.CpoBuilder
                .newUpdate(addressBook.groupsSyncUri())
                .withSelection("${Groups._ID}=?", arrayOf(groupId.toString()))
                .withValue(AddressContract.GroupColumns.PENDING_MEMBERS, null)
        }
        batch.commit()
    }

    private fun uploadLocalChanges(remote: RemoteAddressBookSync) {
        // deletions
        addressBook.queryRawContactRows(
            "${RawContacts.DELETED}=1"
        ).let { flow -> runBlocking { flow.toList() } }.forEach { row ->
            val fileName = row.getAsString(AddressContract.RawContactColumns.FILENAME)
            if (fileName != null) {
                try { runBlocking { remote.delete(fileName, ifETag = null) } }
                catch (e: RemoteConflictException) { /* already gone/changed remotely: fine */ }
            }
            AndroidContact(addressBook, row).delete()
        }

        // dirty (new/changed)
        addressBook.queryRawContactRows(
            "${RawContacts.DIRTY}=1 AND ${RawContacts.DELETED}=0"
        ).let { flow -> runBlocking { flow.toList() } }.forEach { row ->
            val androidContact = AndroidContact(addressBook, row)
            val contact = androidContact.getContact()
            if (contact.uid == null) contact.uid = java.util.UUID.randomUUID().toString()

            var fileName = androidContact.fileName
            val isNew = fileName == null
            if (isNew) fileName = "${contact.uid}.vcf"

            val writer = ContactWriter(contact, VCardVersion.V3_0, PROD_ID)
            val output = StringWriter()
            generator.write(writer.vCard, output)

            val newETag = try {
                runBlocking { remote.upload(fileName!!, output.toString(), ifETag = if (isNew) null else androidContact.eTag) }
            } catch (e: RemoteConflictException) {
                null // server wins: next sync's download will apply the authoritative version
            }

            androidContact.update(
                contentValuesOf(
                    AddressContract.RawContactColumns.FILENAME to fileName,
                    AddressContract.RawContactColumns.ETAG to newETag,
                    RawContacts.DIRTY to 0,
                )
            )
        }
    }

    private fun findRawContact(fileName: String): AndroidContact? =
        addressBook.getRawContactRowOrNull("${AddressContract.RawContactColumns.FILENAME}=?", arrayOf(fileName))
            ?.let { AndroidContact(addressBook, it) }

    private fun findGroup(fileName: String): AndroidGroup? =
        addressBook.getGroupOrNull("${AddressContract.GroupColumns.FILENAME}=?", arrayOf(fileName))
            ?.let { AndroidGroup(addressBook, it) }

    private fun bytesToString(bytes: ByteArray?): String? = bytes?.toString(Charsets.UTF_8)

    companion object {
        private const val PROD_ID = "-//Synccloud//Synccloud Android//EN"
        private const val MULTIGET_BATCH_SIZE = 30
    }
}
