package io.github.andrewgribben.synccloud.account

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.core.content.contentValuesOf
import io.github.andrewgribben.synccloud.icloud.Calendar

/** iCloud accounts as stored by AccountManager, plus their sync settings in the system sync framework. */
class ICloudAccounts(private val context: Context) {

    companion object {
        const val ACCOUNT_TYPE = "io.github.andrewgribben.synccloud.icloud"
        val AUTHORITIES = listOf(CalendarContract.AUTHORITY, ContactsContract.AUTHORITY)
        const val DEFAULT_INTERVAL = 60 * 60L

        private const val KEY_CALENDARS = "calendars"
        private const val KEY_ADDRESS_BOOK = "address_book_url"
        private const val KEY_LAST_SYNCED = "last_synced_millis"
        private const val KEY_LAST_SYNC_FAILED = "last_sync_failed"
        private const val KEY_PHOTOS_MIGRATED = "photos_migrated_v3"
        private const val KEY_STATUS = "status"
        const val STATUS_PASSWORD_NEEDED = "password_needed"
    }

    private val manager = AccountManager.get(context)

    fun all(): List<Account> = manager.getAccountsByType(ACCOUNT_TYPE).toList()

    fun exists(appleId: String) = all().any { it.name.equals(appleId, ignoreCase = true) }

    fun add(appleId: String, password: String, calendars: List<Calendar>, addressBookUrl: String?): Account {
        val account = Account(appleId, ACCOUNT_TYPE)
        manager.addAccountExplicitly(
            account,
            PasswordCipher.encrypt(password),
            Bundle().apply {
                putString(KEY_CALENDARS, CalendarsCodec.encode(calendars))
                putString(KEY_ADDRESS_BOOK, addressBookUrl)
            },
        )
        setInterval(account, DEFAULT_INTERVAL)
        return account
    }

    fun remove(account: Account) = manager.removeAccountExplicitly(account)

    fun password(account: Account): String = PasswordCipher.decrypt(manager.getPassword(account))

    fun setPassword(account: Account, password: String) =
        manager.setPassword(account, PasswordCipher.encrypt(password))

    fun calendars(account: Account): List<Calendar> =
        CalendarsCodec.decode(manager.getUserData(account, KEY_CALENDARS))

    fun setCalendars(account: Account, calendars: List<Calendar>) =
        manager.setUserData(account, KEY_CALENDARS, CalendarsCodec.encode(calendars))

    fun addressBookUrl(account: Account): String? = manager.getUserData(account, KEY_ADDRESS_BOOK)

    /** Timestamp (epoch millis) of the last sync attempt that completed without error, or null if never. */
    fun lastSynced(account: Account): Long? = manager.getUserData(account, KEY_LAST_SYNCED)?.toLongOrNull()

    fun setLastSynced(account: Account, millis: Long) =
        manager.setUserData(account, KEY_LAST_SYNCED, millis.toString())

    /** Whether the most recent sync attempt (any authority) recorded an error, so "Synced" would be misleading. */
    fun lastSyncFailed(account: Account): Boolean = manager.getUserData(account, KEY_LAST_SYNC_FAILED) == "1"

    fun setLastSyncFailed(account: Account, failed: Boolean) =
        manager.setUserData(account, KEY_LAST_SYNC_FAILED, if (failed) "1" else null)

    /** Whether contacts have already had their one-time post-upgrade photo re-download (see [ContactsSyncer]). */
    fun photosMigrated(account: Account): Boolean = manager.getUserData(account, KEY_PHOTOS_MIGRATED) == "1"

    fun setPhotosMigrated(account: Account, migrated: Boolean) =
        manager.setUserData(account, KEY_PHOTOS_MIGRATED, if (migrated) "1" else null)

    /** Null when everything is fine; [STATUS_PASSWORD_NEEDED] after a 401 from iCloud. */
    fun status(account: Account): String? = manager.getUserData(account, KEY_STATUS)

    fun setStatus(account: Account, status: String?) = manager.setUserData(account, KEY_STATUS, status)

    // The system's per-account Calendar/Contacts switches (Settings → Accounts) are the source of truth
    fun isSyncEnabled(account: Account, authority: String) =
        ContentResolver.getSyncAutomatically(account, authority)

    fun setSyncEnabled(account: Account, authority: String, enabled: Boolean) {
        ContentResolver.setIsSyncable(account, authority, 1) // required for setSyncAutomatically/requestSync to have any effect
        ContentResolver.setSyncAutomatically(account, authority, enabled)
    }

    /** Periodic sync interval in seconds, or null for manual sync only. */
    fun interval(account: Account): Long? =
        ContentResolver.getPeriodicSyncs(account, CalendarContract.AUTHORITY).firstOrNull()?.period

    fun setInterval(account: Account, seconds: Long?) {
        for (authority in AUTHORITIES) {
            // addPeriodicSync with the same (empty) extras replaces the previous period
            if (seconds == null) ContentResolver.removePeriodicSync(account, authority, Bundle.EMPTY)
            else ContentResolver.addPeriodicSync(account, authority, Bundle.EMPTY, seconds)
        }
    }

    fun syncNow(account: Account) {
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        for (authority in AUTHORITIES) ContentResolver.requestSync(account, authority, extras)
    }

    /**
     * Contacts in an account without a visible group are hidden from most apps (WhatsApp among
     * them) unless the account's ungrouped contacts are marked visible.
     */
    fun makeContactsVisible(account: Account) {
        if (context.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) return
        context.contentResolver.insert(
            ContactsContract.Settings.CONTENT_URI.buildUpon()
                .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                .build(),
            contentValuesOf(
                ContactsContract.Settings.ACCOUNT_NAME to account.name,
                ContactsContract.Settings.ACCOUNT_TYPE to account.type,
                ContactsContract.Settings.UNGROUPED_VISIBLE to 1,
            ),
        )
    }
}
