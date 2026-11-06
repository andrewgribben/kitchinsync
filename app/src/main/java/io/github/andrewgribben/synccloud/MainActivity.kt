package io.github.andrewgribben.synccloud

import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import io.github.andrewgribben.synccloud.account.ICloudAccounts
import io.github.andrewgribben.synccloud.ui.App
import io.github.andrewgribben.synccloud.ui.MainViewModel
import io.github.andrewgribben.synccloud.ui.Screen
import io.github.andrewgribben.synccloud.ui.SynccloudTheme

/**
 * Hosts all screens. Besides the launcher, it's opened by AccountManager to add an account
 * (with an authenticator response) and by Settings → Accounts to edit one (with an "account" extra).
 */
class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()
    private var authenticatorResponse: AccountAuthenticatorResponse? = null
    private var addedAccount: Account? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        authenticatorResponse = IntentCompat.getParcelableExtra(
            intent, AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, AccountAuthenticatorResponse::class.java,
        )?.also { it.onRequestContinued() }
        val account = IntentCompat.getParcelableExtra(intent, "account", Account::class.java)
            ?.takeIf { it in vm.accounts.all() }
        val reauthAccount = IntentCompat.getParcelableExtra(intent, EXTRA_REAUTH_ACCOUNT, Account::class.java)
            ?.takeIf { it in vm.accounts.all() }

        vm.start(
            when {
                authenticatorResponse != null -> Screen.SignIn()
                reauthAccount != null -> Screen.SignIn(reauthAccount)
                account != null -> Screen.Settings(account)
                else -> Screen.Accounts
            }
        )

        setContent {
            SynccloudTheme {
                App(vm, onAccountAdded = ::onAccountAdded, onExit = ::finish)
            }
        }
    }

    private fun onAccountAdded(account: Account) {
        if (authenticatorResponse != null) {
            addedAccount = account
            finish()
        }
    }

    override fun finish() {
        authenticatorResponse?.let { response ->
            val account = addedAccount
            if (account != null)
                response.onResult(Bundle().apply {
                    putString(AccountManager.KEY_ACCOUNT_NAME, account.name)
                    putString(AccountManager.KEY_ACCOUNT_TYPE, ICloudAccounts.ACCOUNT_TYPE)
                })
            else
                response.onError(AccountManager.ERROR_CODE_CANCELED, "Cancelled")
            authenticatorResponse = null
        }
        super.finish()
    }

    companion object {
        /** Set by [io.github.andrewgribben.synccloud.sync.SyncNotifications] (wireframe B4's "RE-ENTER PASSWORD"). */
        const val EXTRA_REAUTH_ACCOUNT = "reauth_account"
    }
}
