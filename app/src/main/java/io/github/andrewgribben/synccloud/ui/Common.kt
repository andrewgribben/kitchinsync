package io.github.andrewgribben.synccloud.ui

import android.Manifest
import android.accounts.Account
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.andrewgribben.synccloud.R
import io.github.andrewgribben.synccloud.account.ICloudAccounts
import io.github.andrewgribben.synccloud.icloud.Calendar
import io.github.andrewgribben.synccloud.sync.SyncProgress
import io.github.andrewgribben.synccloud.sync.fraction

enum class SyncInterval(val seconds: Long?, @param:StringRes val label: Int) {
    FIFTEEN_MINUTES(15 * 60L, R.string.interval_15_min),
    HOUR(ICloudAccounts.DEFAULT_INTERVAL, R.string.interval_hour),
    FOUR_HOURS(4 * 60 * 60L, R.string.interval_4_hours),
    MANUAL(null, R.string.interval_manual);

    companion object {
        fun of(seconds: Long?) = entries.firstOrNull { it.seconds == seconds } ?: HOUR
    }
}

fun permissionsFor(calendars: Boolean, contacts: Boolean) = buildList {
    if (calendars) addAll(listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
    if (contacts) addAll(listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS))
}.toTypedArray()

@Composable
fun SectionHeader(@StringRes text: Int) = Text(
    stringResource(text),
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
)

@Composable
fun ColorDot(color: Int?) = Box(
    Modifier
        .size(14.dp)
        .background(color?.let { Color(it) } ?: MaterialTheme.colorScheme.primary, CircleShape)
)

/** A row with a trailing checkbox or switch; the whole row toggles. */
@Composable
fun ToggleItem(
    headline: String,
    supporting: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    checkbox: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) = ListItem(
    headlineContent = { Text(headline) },
    supportingContent = supporting?.let { { Text(it) } },
    leadingContent = leading,
    trailingContent = {
        if (checkbox) Checkbox(checked, onCheckedChange = null) else Switch(checked, onCheckedChange = null)
    },
    modifier = Modifier.toggleable(checked, role = if (checkbox) Role.Checkbox else Role.Switch, onValueChange = onCheckedChange),
)

@Composable
fun CalendarItem(calendar: Calendar, onCheckedChange: (Boolean) -> Unit, checkbox: Boolean = false) = ToggleItem(
    headline = calendar.displayName,
    supporting = when {
        calendar.subscribed -> stringResource(R.string.subscribed_read_only)
        calendar.readOnly -> stringResource(R.string.read_only)
        else -> null
    },
    checked = calendar.enabled,
    onCheckedChange = onCheckedChange,
    checkbox = checkbox,
    leading = { ColorDot(calendar.color) },
)

/** "Synced 2 min ago" / "Synced just now" / "Never synced" (wireframe A chip text, without the "Synced" prefix translation). */
fun lastSyncedLabel(lastSyncedMillis: Long?): String {
    if (lastSyncedMillis == null) return "Never synced"
    val minutes = (System.currentTimeMillis() - lastSyncedMillis) / 60_000
    return when {
        minutes < 1 -> "Synced just now"
        minutes < 60 -> "Synced $minutes min ago"
        minutes < 24 * 60 -> "Synced ${minutes / 60} h ago"
        else -> "Synced ${minutes / (24 * 60)} d ago"
    }
}

/**
 * One line summarising sync state: live progress while syncing, otherwise the last outcome.
 * [accounts.lastSyncFailed] must win over [lastSyncedLabel] so the UI never claims "Synced" for
 * an attempt that actually recorded an I/O or auth error (see SyncService).
 */
@Composable
fun syncStatusText(accounts: ICloudAccounts, account: Account, progress: SyncProgress.State?): String = when {
    progress?.phase == SyncProgress.Phase.CALENDARS && progress.stage == SyncProgress.Stage.LISTING ->
        if (progress.total != null) stringResource(R.string.syncing_calendars_listing, progress.label, progress.current, progress.total)
        else stringResource(R.string.syncing_calendars, progress.label)
    progress?.phase == SyncProgress.Phase.CALENDARS ->
        if (progress.total != null) stringResource(R.string.syncing_calendars_events, progress.label, progress.current, progress.total)
        else stringResource(R.string.syncing_calendars, progress.label)
    progress?.phase == SyncProgress.Phase.CONTACTS ->
        if (progress.total != null) stringResource(R.string.syncing_contacts_progress, progress.current, progress.total)
        else stringResource(R.string.syncing_contacts)
    accounts.lastSyncFailed(account) -> stringResource(R.string.sync_error)
    else -> lastSyncedLabel(accounts.lastSynced(account))
}

/** This account's live progress, or null when nothing is syncing (or another account is). */
@Composable
fun syncProgressFor(account: Account): SyncProgress.State? {
    val progress by SyncProgress.state.collectAsState()
    return progress?.takeIf { it.accountName == account.name }
}

/** Determinate when the item count is known, indeterminate otherwise (Material 3 guidance). */
@Composable
fun SyncProgressBar(progress: SyncProgress.State, modifier: Modifier = Modifier) {
    val fraction = progress.fraction()
    if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = modifier.fillMaxWidth())
    else LinearProgressIndicator(modifier = modifier.fillMaxWidth())
}
