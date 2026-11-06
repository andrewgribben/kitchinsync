package io.github.andrewgribben.synccloud.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.andrewgribben.synccloud.R
import io.github.andrewgribben.synccloud.icloud.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChooseScreen(
    screen: Screen.Choose,
    onDone: (calendars: List<Calendar>, contacts: Boolean) -> Unit,
    onBack: () -> Unit,
) {
    var calendars by remember { mutableStateOf(screen.result.calendars) }
    val hasAddressBook = screen.result.addressBookUrl != null
    var contacts by rememberSaveable { mutableStateOf(hasAddressBook) }
    // Ask only for what was picked, and before sync is switched on
    val requestPermissions = rememberLauncherForActivityResult(RequestMultiplePermissions()) {
        onDone(calendars, contacts)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.choose_what_to_sync)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            item { SectionHeader(R.string.calendars_header) }
            items(calendars, key = { it.url }) { calendar ->
                CalendarItem(calendar, checkbox = true, onCheckedChange = { enabled ->
                    calendars = calendars.map { if (it.url == calendar.url) it.copy(enabled = enabled) else it }
                })
            }
            if (hasAddressBook) {
                item { SectionHeader(R.string.contacts_header) }
                item {
                    ToggleItem(stringResource(R.string.icloud_contacts), null, contacts, onCheckedChange = { contacts = it })
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
                    Button(onClick = {
                        requestPermissions.launch(permissionsFor(calendars.any { it.enabled }, contacts))
                    }) { Text(stringResource(R.string.done)) }
                }
            }
        }
    }
}
