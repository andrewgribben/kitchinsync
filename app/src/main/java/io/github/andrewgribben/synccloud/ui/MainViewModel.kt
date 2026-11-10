package io.github.andrewgribben.synccloud.ui

import android.accounts.Account
import android.app.Application
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.andrewgribben.synccloud.account.ICloudAccounts
import io.github.andrewgribben.synccloud.icloud.Calendar
import io.github.andrewgribben.synccloud.icloud.DiscoveryResult
import io.github.andrewgribben.synccloud.icloud.ICloudDiscovery
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Accounts : Screen
    /** [account] is set when changing the password of an existing account. */
    data class SignIn(val account: Account? = null) : Screen
    data class Choose(val appleId: String, val password: String, val result: DiscoveryResult.Success) : Screen
    data class Settings(val account: Account) : Screen
}

data class SignInState(
    val busy: Boolean = false,
    val wrongPassword: Boolean = false,
    val alreadyAdded: Boolean = false,
    val failure: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val accounts = ICloudAccounts(app)
    private val discovery = ICloudDiscovery()

    val backStack = mutableStateListOf<Screen>()
    var signIn by mutableStateOf(SignInState())
        private set

    fun start(screen: Screen) {
        if (backStack.isEmpty()) backStack += screen
    }

    fun navigate(screen: Screen) {
        signIn = SignInState()
        backStack += screen
    }

    fun back() {
        backStack.removeAt(backStack.lastIndex)
    }

    fun signIn(appleId: String, password: String, account: Account?) {
        if (account == null && accounts.exists(appleId)) {
            signIn = SignInState(alreadyAdded = true)
            return
        }
        signIn = SignInState(busy = true)
        viewModelScope.launch {
            when (val result = discovery.discover(appleId, password)) {
                is DiscoveryResult.Success -> {
                    signIn = SignInState()
                    if (account == null) {
                        back()
                        navigate(Screen.Choose(appleId, password, result))
                    } else {
                        accounts.setPassword(account, password)
                        back()
                    }
                }
                DiscoveryResult.WrongPassword -> signIn = SignInState(wrongPassword = true)
                is DiscoveryResult.Failed -> signIn = SignInState(failure = result.message)
            }
        }
    }

    /** Called once the runtime permissions for the chosen collections have been requested. */
    fun addAccount(choose: Screen.Choose, calendars: List<Calendar>, contacts: Boolean): Account {
        val account = accounts.add(choose.appleId, choose.password, calendars, choose.result.addressBookUrl)
        accounts.setSyncEnabled(account, CalendarContract.AUTHORITY, calendars.any { it.enabled })
        accounts.setSyncEnabled(account, ContactsContract.AUTHORITY, contacts)
        if (contacts) accounts.makeContactsVisible(account)
        backStack.clear()
        backStack += Screen.Accounts
        return account
    }
}
