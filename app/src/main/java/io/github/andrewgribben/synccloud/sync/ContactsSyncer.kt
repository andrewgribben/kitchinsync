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
import io.ktor.http.Url
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.StringReader
import java.io.StringWriter
import java.util.logging.Logger

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

    /**
     * @param addressBookUrl    the account's single iCloud address book, or null if contacts sync is off
     *                          (in which case all local contacts/groups for this account are removed)
     */
    fun sync(addressBookUrl: String?, syncResult: SyncResult) {
        if (addressBookUrl == null) {
            removeAllLocalContacts()
            return
        }

        try {
            runBlocking { syncAddressBook(Url(addressBookUrl)) }
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

    private fun removeAllLocalContacts() {
        val batch = ContactsBatchOperation(storage)
        addressBook.deleteRawContacts(batch = batch)
        addressBook.deleteGroups(batch = batch)
        batch.commit()
    }

    private suspend fun syncAddressBook(collectionUrl: Url) {
        val remote = RemoteAddressBookSync(httpClient, collectionUrl)

        uploadLocalChanges(remote)

        val lastState = RemoteSyncState.decode(bytesToString(addressBook.syncState))
        when (SyncPlan.decide(lastState, syncTokenRejected = false)) {
            SyncPlan.SYNC_COLLECTION -> {
                try {
                    val result = remote.listChanges((lastState as RemoteSyncState.Token).value)
                    applyRemoteChanges(remote, result.changed.map { it.url }, result.removed.map { it.encodedPath.substringAfterLast('/') })
                    result.syncToken?.let { addressBook.syncState = RemoteSyncState.Token(it).encode().toByteArray() }
                } catch (e: InvalidSyncTokenException) {
                    fullListing(remote)
                }
            }
            SyncPlan.FULL_LISTING -> fullListing(remote)
        }

        resolvePendingGroupMemberships()
    }

    private suspend fun fullListing(remote: RemoteAddressBookSync) {
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

        val changedUrls = remoteMembers.filter { member ->
            localETagsByFileName[member.url.encodedPath.substringAfterLast('/')] != member.eTag
        }.map { it.url }
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
        for (batch in changedUrls.chunked(MULTIGET_BATCH_SIZE)) {
            for (content in remote.download(batch)) {
                val fileName = content.url.encodedPath.substringAfterLast('/')
                val vCard = parser.parse(StringReader(content.body)) ?: continue
                val contact = ContactReader.fromVCard(vCard)

                if (contact.group) {
                    val existing = findGroup(fileName)
                    if (existing != null) existing.apply { eTag = content.eTag }.update(contact)
                    else AndroidGroup(addressBook, contact, fileName, content.eTag).add()
                } else {
                    val existing = findRawContact(fileName)
                    if (existing != null) existing.apply { eTag = content.eTag }.update(contact)
                    else AndroidContact(addressBook, contact, fileName, content.eTag).add()
                }
            }
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
