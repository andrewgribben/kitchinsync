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

    // The system's per-account Calendar/Contacts switches (Settings → Accounts) are the source of truth
    fun isSyncEnabled(account: Account, authority: String) =
        ContentResolver.getSyncAutomatically(account, authority)

    fun setSyncEnabled(account: Account, authority: String, enabled: Boolean) =
        ContentResolver.setSyncAutomatically(account, authority, enabled)

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
