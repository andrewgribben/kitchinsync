package io.github.andrewgribben.synccloud.sync

import android.accounts.Account
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.os.Bundle
import android.provider.ContactsContract
import android.util.Log
import io.github.andrewgribben.synccloud.account.ICloudAccounts

/** Stage 1 stub: registers the Calendar and Contacts sync switches with Android but doesn't sync data yet. */
private class SyncAdapter(context: Context) : AbstractThreadedSyncAdapter(context, true) {

    override fun onPerformSync(
        account: Account,
        extras: Bundle,
        authority: String,
        provider: ContentProviderClient,
        syncResult: SyncResult,
    ) {
        Log.i("SyncAdapter", "Sync requested for $authority (not implemented yet)")
        // Repeated here because the contacts permission may have been granted after setup
        if (authority == ContactsContract.AUTHORITY) ICloudAccounts(context).makeContactsVisible(account)
    }
}

// One service per authority, because each service can only point at one sync-adapter XML
abstract class SyncService : Service() {
    private val adapter by lazy { SyncAdapter(applicationContext) }
    override fun onBind(intent: Intent) = adapter.syncAdapterBinder
}

class CalendarSyncService : SyncService()

class ContactsSyncService : SyncService()
