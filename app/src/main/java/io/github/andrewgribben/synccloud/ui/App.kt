package io.github.andrewgribben.synccloud.ui

import android.accounts.Account
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

@Composable
fun App(vm: MainViewModel, onAccountAdded: (Account) -> Unit, onExit: () -> Unit) {
    val back = { if (vm.backStack.size > 1) vm.back() else onExit() }
    BackHandler(enabled = vm.backStack.size > 1) { vm.back() }

    when (val screen = vm.backStack.lastOrNull()) {
        Screen.Accounts -> AccountsScreen(
            vm.accounts,
            onAdd = { vm.navigate(Screen.SignIn()) },
            onOpen = { vm.navigate(Screen.Settings(it)) },
        )
        is Screen.SignIn -> SignInScreen(vm.signIn, screen.account, onSignIn = vm::signIn, onBack = back)
        is Screen.Choose -> ChooseScreen(
            screen,
            onDone = { calendars, contacts -> onAccountAdded(vm.addAccount(screen, calendars, contacts)) },
            onBack = back,
        )
        is Screen.Settings -> SettingsScreen(
            vm.accounts,
            screen.account,
            onChangePassword = { vm.navigate(Screen.SignIn(screen.account)) },
            onBack = back,
        )
        null -> {}
    }
}
