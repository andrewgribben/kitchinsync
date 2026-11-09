package io.github.andrewgribben.synccloud.sync

import android.accounts.Account
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.core.content.PermissionChecker
import at.bitfire.dav4jvm.ktor.exception.UnauthorizedException
import io.github.andrewgribben.synccloud.account.ICloudAccounts
import io.github.andrewgribben.synccloud.icloud.icloudHttpClient
import java.io.IOException
import java.util.logging.Logger

private class SyncAdapter(context: Context) : AbstractThreadedSyncAdapter(context, true) {
    private val logger = Logger.getLogger(javaClass.name)
    private val accounts = ICloudAccounts(context)

    override fun onPerformSync(
        account: Account,
        extras: Bundle,
        authority: String,
        provider: ContentProviderClient,
        syncResult: SyncResult,
    ) {
        if (authority == ContactsContract.AUTHORITY)
            // Repeated here because the contacts permission may have been granted after setup
            accounts.makeContactsVisible(account)

        val client = icloudHttpClient(account.name, accounts.password(account))
        try {
            when (authority) {
                CalendarContract.AUTHORITY -> {
                    if (PermissionChecker.checkSelfPermission(context, android.Manifest.permission.WRITE_CALENDAR) != PermissionChecker.PERMISSION_GRANTED) return
                    CalendarSyncer(account, provider, client).sync(accounts.calendars(account), syncResult)
                }
                ContactsContract.AUTHORITY -> {
                    if (PermissionChecker.checkSelfPermission(context, android.Manifest.permission.WRITE_CONTACTS) != PermissionChecker.PERMISSION_GRANTED) return
                    val contactsEnabled = accounts.isSyncEnabled(account, ContactsContract.AUTHORITY)
                    val forcePhotoResync = contactsEnabled && !accounts.photosMigrated(account)
                    ContactsSyncer(account, context, provider, client)
                        .sync(accounts.addressBookUrl(account), contactsEnabled, syncResult, forcePhotoResync)
                    if (forcePhotoResync && !syncResult.hasError()) accounts.setPhotosMigrated(account, true)
                }
            }
            // Only report success (and clear any error status) when this sync didn't swallow an
            // error into syncResult; otherwise the account would show "Synced" while e.g. every
            // calendar actually timed out.
            if (!syncResult.hasError()) {
                accounts.setLastSynced(account, System.currentTimeMillis())
                accounts.setLastSyncFailed(account, false)
                if (accounts.status(account) == ICloudAccounts.STATUS_PASSWORD_NEEDED) {
                    accounts.setStatus(account, null)
                    SyncNotifications.cancelPasswordNeeded(context, account)
                }
            } else {
                accounts.setLastSyncFailed(account, true)
            }
        } catch (e: UnauthorizedException) {
            logger.warning("iCloud rejected the password for ${account.name}")
            accounts.setStatus(account, ICloudAccounts.STATUS_PASSWORD_NEEDED)
            accounts.setLastSyncFailed(account, true)
            SyncNotifications.notifyPasswordNeeded(context, account)
            syncResult.stats.numAuthExceptions++
        } catch (e: IOException) {
            logger.warning("I/O error syncing ${account.name}: ${e.message}")
            accounts.setLastSyncFailed(account, true)
            syncResult.stats.numIoExceptions++
        } finally {
            client.close()
            SyncProgress.clear(account.name)
        }
    }
}

// One service per authority, because each service can only point at one sync-adapter XML
abstract class SyncService : Service() {
    private val adapter by lazy { SyncAdapter(applicationContext) }
    override fun onBind(intent: Intent) = adapter.syncAdapterBinder
}

class CalendarSyncService : SyncService()

class ContactsSyncService : SyncService()
