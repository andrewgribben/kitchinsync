package io.github.andrewgribben.synccloud.ui

import android.accounts.Account
import android.util.Patterns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.andrewgribben.synccloud.R
import io.github.andrewgribben.synccloud.icloud.AppSpecificPassword

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInScreen(
    state: SignInState,
    account: Account?,
    onSignIn: (appleId: String, password: String, account: Account?) -> Unit,
    onBack: () -> Unit,
) {
    var appleId by rememberSaveable { mutableStateOf(account?.name.orEmpty()) }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    // Only nag about the format once the user has tried to sign in
    var submitted by rememberSaveable { mutableStateOf(false) }

    val appleIdValid = Patterns.EMAIL_ADDRESS.matcher(appleId.trim()).matches()
    val normalizedPassword = AppSpecificPassword.normalize(password)
    val appleIdError = when {
        submitted && !appleIdValid -> stringResource(R.string.error_apple_id)
        state.alreadyAdded -> stringResource(R.string.error_already_added)
        else -> null
    }
    val passwordError = when {
        submitted && normalizedPassword == null -> stringResource(R.string.error_password_format)
        state.wrongPassword -> stringResource(R.string.error_wrong_password)
        else -> null
    }

    fun submit() {
        submitted = true
        if (appleIdValid && normalizedPassword != null) onSignIn(appleId.trim(), normalizedPassword, account)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (account == null) R.string.add_icloud_account else R.string.change_password)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))

            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = appleId,
                    onValueChange = { appleId = it },
                    label = { Text(stringResource(R.string.apple_id)) },
                    enabled = account == null && !state.busy,
                    isError = appleIdError != null,
                    supportingText = appleIdError?.let { { Text(it) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.app_specific_password)) },
                    enabled = !state.busy,
                    isError = passwordError != null,
                    supportingText = { Text(passwordError ?: stringResource(R.string.password_help)) },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = { showPassword = !showPassword }) {
                            Text(stringResource(if (showPassword) R.string.hide_password else R.string.show_password))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                val uriHandler = LocalUriHandler.current
                val helpUrl = stringResource(R.string.app_specific_password_help_url)
                TextButton(onClick = { uriHandler.openUri(helpUrl) }) {
                    Text(stringResource(R.string.password_help_link))
                }

                when {
                    state.busy -> Text(stringResource(R.string.discovering), style = MaterialTheme.typography.bodyMedium)
                    state.failure != null -> Text(
                        stringResource(R.string.error_failed, state.failure),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.cancel)) }
                    Button(onClick = ::submit, enabled = !state.busy) {
                        Text(stringResource(if (account == null) R.string.sign_in else R.string.save))
                    }
                }
            }
        }
    }
}
