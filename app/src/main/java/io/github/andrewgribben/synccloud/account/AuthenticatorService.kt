package io.github.andrewgribben.synccloud.account

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import io.github.andrewgribben.synccloud.MainActivity

class AuthenticatorService : Service() {

    private val authenticator by lazy { Authenticator(this) }

    override fun onBind(intent: Intent) = authenticator.iBinder

    /** Only account creation is supported; CalDAV/CardDAV use the stored password, not auth tokens. */
    private class Authenticator(private val context: Context) : AbstractAccountAuthenticator(context) {

        override fun addAccount(
            response: AccountAuthenticatorResponse,
            accountType: String,
            authTokenType: String?,
            requiredFeatures: Array<out String>?,
            options: Bundle?,
        ) = Bundle().apply {
            putParcelable(
                AccountManager.KEY_INTENT,
                Intent(context, MainActivity::class.java)
                    .putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response),
            )
        }

        override fun editProperties(response: AccountAuthenticatorResponse, accountType: String) = null
        override fun confirmCredentials(response: AccountAuthenticatorResponse, account: Account, options: Bundle?) = null
        override fun getAuthToken(response: AccountAuthenticatorResponse, account: Account, authTokenType: String, options: Bundle?) = null
        override fun getAuthTokenLabel(authTokenType: String) = null
        override fun updateCredentials(response: AccountAuthenticatorResponse, account: Account, authTokenType: String?, options: Bundle?) = null
        override fun hasFeatures(response: AccountAuthenticatorResponse, account: Account, features: Array<out String>) =
            Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false) }
    }
}
