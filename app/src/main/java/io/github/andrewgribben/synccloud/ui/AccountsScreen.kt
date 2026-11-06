package io.github.andrewgribben.synccloud.ui

import android.accounts.Account
import android.provider.ContactsContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.andrewgribben.synccloud.R
import io.github.andrewgribben.synccloud.account.ICloudAccounts

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(accounts: ICloudAccounts, onAdd: () -> Unit, onOpen: (Account) -> Unit) {
    var list by remember { mutableStateOf(accounts.all()) }
    // Accounts can also be added or removed in system Settings
    LifecycleResumeEffect(Unit) {
        list = accounts.all()
        onPauseOrDispose {}
    }

    Scaffold(
        topBar = { LargeTopAppBar(title = { Text(stringResource(R.string.accounts_title)) }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.add_account)) },
            )
        },
    ) { padding ->
        if (list.isEmpty()) {
            EmptyState(Modifier.padding(padding))
        } else {
            LazyColumn(contentPadding = padding) {
                items(list, key = { it.name }) { account ->
                    ListItem(
                        headlineContent = { Text(account.name) },
                        supportingContent = { Text(status(accounts, account)) },
                        leadingContent = { Icon(painterResource(R.drawable.ic_cloud), contentDescription = null) },
                        modifier = Modifier.clickable { onOpen(account) },
                    )
                }
            }
        }
    }
}

@Composable
private fun status(accounts: ICloudAccounts, account: Account): String {
    val calendars = accounts.calendars(account).count { it.enabled }
    return listOfNotNull(
        pluralStringResource(R.plurals.calendar_count, calendars, calendars),
        stringResource(R.string.contacts).takeIf { accounts.isSyncEnabled(account, ContactsContract.AUTHORITY) },
        stringResource(SyncInterval.of(accounts.interval(account)).label),
    ).joinToString(" · ")
}

@Composable
private fun EmptyState(modifier: Modifier) = Column(
    modifier
        .fillMaxSize()
        .padding(horizontal = 32.dp, vertical = 48.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(12.dp),
) {
    Icon(
        painterResource(R.drawable.ic_cloud),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.outline,
        modifier = Modifier.size(64.dp),
    )
    Text(stringResource(R.string.no_accounts), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.no_accounts_body), textAlign = TextAlign.Center)
    val url = stringResource(R.string.app_specific_password_help_url)
    val link = stringResource(R.string.no_accounts_password_link)
    val linkColor = MaterialTheme.colorScheme.primary
    Text(
        buildAnnotatedString {
            append(stringResource(R.string.no_accounts_password_prefix))
            withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = linkColor)))) { append(link) }
            append(".")
        },
        textAlign = TextAlign.Center,
    )
}
