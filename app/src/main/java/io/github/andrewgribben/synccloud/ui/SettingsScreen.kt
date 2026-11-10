package io.github.andrewgribben.synccloud.ui

import android.accounts.Account
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.andrewgribben.synccloud.R
import io.github.andrewgribben.synccloud.account.ICloudAccounts

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    accounts: ICloudAccounts,
    account: Account,
    onChangePassword: () -> Unit,
    onBack: () -> Unit,
) {
    var calendars by remember(account) { mutableStateOf(accounts.calendars(account)) }
    var contacts by remember(account) { mutableStateOf(accounts.isSyncEnabled(account, ContactsContract.AUTHORITY)) }
    var interval by remember(account) { mutableStateOf(SyncInterval.of(accounts.interval(account))) }
    var menuOpen by remember { mutableStateOf(false) }
    var intervalSheetOpen by rememberSaveable { mutableStateOf(false) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }

    // The account may have been removed in system Settings meanwhile
    LifecycleResumeEffect(account) {
        if (account !in accounts.all()) onBack()
        onPauseOrDispose {}
    }
    val requestPermissions = rememberLauncherForActivityResult(RequestMultiplePermissions()) {
        if (contacts) accounts.makeContactsVisible(account)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(account.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.more_options))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.change_password)) },
                            onClick = { menuOpen = false; onChangePassword() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.remove_account)) },
                            onClick = { menuOpen = false; confirmRemove = true },
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            item {
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    val progress = syncProgressFor(account)
                    Column {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                syncStatusText(accounts, account, progress),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            FilledTonalButton(onClick = { accounts.syncNow(account) }) {
                                Text(stringResource(R.string.sync_now))
                            }
                        }
                        if (progress != null) SyncProgressBar(progress, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                }
            }
            if (accounts.status(account) == ICloudAccounts.STATUS_PASSWORD_NEEDED) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.password_needed)) },
                        modifier = Modifier.clickable(onClick = onChangePassword),
                    )
                }
            }

            item { SectionHeader(R.string.calendars_header) }
            items(calendars, key = { it.url }) { calendar ->
                CalendarItem(calendar, onCheckedChange = { enabled ->
                    calendars = calendars.map { if (it.url == calendar.url) it.copy(enabled = enabled) else it }
                    accounts.setCalendars(account, calendars)
                    accounts.setSyncEnabled(account, CalendarContract.AUTHORITY, calendars.any { it.enabled })
                    if (enabled) requestPermissions.launch(permissionsFor(calendars = true, contacts = false))
                    accounts.syncNow(account)
                })
            }

            item { SectionHeader(R.string.contacts_header) }
            item {
                ToggleItem(stringResource(R.string.icloud_contacts), null, contacts, onCheckedChange = { enabled ->
                    contacts = enabled
                    accounts.setSyncEnabled(account, ContactsContract.AUTHORITY, enabled)
                    if (enabled) requestPermissions.launch(permissionsFor(calendars = false, contacts = true))
                    accounts.syncNow(account)
                })
            }

            item { SectionHeader(R.string.sync_header) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.sync_interval)) },
                    supportingContent = { Text(stringResource(interval.label)) },
                    modifier = Modifier.clickable { intervalSheetOpen = true },
                )
            }
        }
    }

    if (intervalSheetOpen) {
        ModalBottomSheet(onDismissRequest = { intervalSheetOpen = false }) {
            Text(
                stringResource(R.string.sync_interval),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            for (option in SyncInterval.entries) {
                ListItem(
                    headlineContent = {
                        Text(stringResource(if (option == SyncInterval.HOUR) R.string.interval_hour_recommended else option.label))
                    },
                    trailingContent = { if (option == interval) Icon(Icons.Default.Check, contentDescription = null) },
                    modifier = Modifier.clickable {
                        interval = option
                        accounts.setInterval(account, option.seconds)
                        intervalSheetOpen = false
                    },
                )
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.remove_account_title, account.name)) },
            text = { Text(stringResource(R.string.remove_account_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    accounts.remove(account)
                    onBack()
                }) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
