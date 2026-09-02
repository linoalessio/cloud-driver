package de.lino.cloud.platform.desktop.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.lino.cloud.platform.desktop.utils.formatBytes
import de.lino.cloud.platform.desktop.viewmodel.AppViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// DateTimeFormatter is immutable/thread-safe (unlike SimpleDateFormat), so one shared instance is safe -
// same pattern FileBrowserScreen.kt's own ENTRY_DATE_FORMAT uses.
private val JOINED_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

private fun formatJoinedDate(epochMilli: Long): String = JOINED_DATE_FORMAT.format(Instant.ofEpochMilli(epochMilli))

/**
 * Formats the account's storage status as `"<used> / <limit>"`, mirroring the server's own
 * `ICloudUser#getCurrentUploadedBytes()`/`#getMaxBytesToUpload()` pattern (see `CloudUserCommand`'s
 * terminal equivalent) - both values via [formatBytes], the client-side port of `Constraints`'s
 * `resolveBytesToUnit` this module already uses everywhere else (see `ByteFormat.kt`'s own Javadoc
 * on why this module ports that algorithm by hand instead of depending on `cloud-driver-api`).
 * `"-"` while either value hasn't loaded yet.
 */
private fun formatStorageStatus(uploadedBytes: Long?, maxBytes: Long?): String {
    if (uploadedBytes == null || maxBytes == null) return "-"
    return "${formatBytes(uploadedBytes)} / ${formatBytes(maxBytes)}"
}

/** The after-login account overview - email/account id, plus aggregate file/folder/storage stats (see [AccountStats]). */
@Composable
fun DashboardScreen(viewModel: AppViewModel) {
    LaunchedEffect(Unit) { viewModel.loadDashboardStats() }

    var showUninstallConfirmation by remember { mutableStateOf(false) }

    AuthenticatedShell(viewModel) {
        Column(Modifier.fillMaxSize().padding(32.dp)) {
            Text("Dashboard", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "An overview of your cloud-driver account.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(28.dp))

            AccountInfoCard(viewModel)

            Spacer(Modifier.height(20.dp))

            viewModel.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 12.dp))
            }

            val stats = viewModel.dashboardStats
            if (stats == null) {
                if (viewModel.busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Adding up your files and folders...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatCard(Icons.AutoMirrored.Filled.InsertDriveFile, "Uploaded files", stats.fileCount.toString(), Modifier.weight(1f))
                    StatCard(Icons.Filled.Folder, "Folders", stats.folderCount.toString(), Modifier.weight(1f))
                    StatCard(Icons.Filled.Storage, "Used storage", formatBytes(stats.totalBytes), Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(20.dp))

            DangerZoneCard(busy = viewModel.busy, onUninstallClick = { showUninstallConfirmation = true })
        }
    }

    if (showUninstallConfirmation) {
        UninstallConfirmationDialog(
            onConfirm = {
                showUninstallConfirmation = false
                viewModel.uninstall()
            },
            onDismiss = { showUninstallConfirmation = false },
        )
    }
}

/**
 * A "Danger Zone" card housing the app's one destructive, machine-wide action: uninstalling
 * itself. Kept visually separate (its own bordered card, an error-tinted button) from the
 * account-info/stat cards above so it doesn't read as just another piece of account information.
 */
@Composable
private fun DangerZoneCard(busy: Boolean, onUninstallClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Danger zone", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Permanently remove cloud-driver and its local settings from this computer. This does not delete anything from your account.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(16.dp))
            Button(
                onClick = onUninstallClick,
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Filled.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Uninstall")
            }
        }
    }
}

/** Blocks the "Uninstall" action from ever firing without an explicit, separate confirmation click - the app is deleted and the process exits the moment [onConfirm] runs. */
@Composable
private fun UninstallConfirmationDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Uninstall cloud-driver?") },
        text = {
            Text(
                "This removes the cloud-driver app and its local settings from this computer, then closes it. " +
                    "Your uploaded files and account are not affected and remain in the cloud. This cannot be undone.",
            )
        },
        confirmButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Text("Uninstall")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun AccountInfoCard(viewModel: AppViewModel) {
    // Local, transient UI state - which of the settings-menu dialogs (if any) is currently open.
    // Not on AppViewModel: purely dialog visibility, the same "local remember, not view-model
    // state" reasoning FileBrowserScreen.kt's own moveDialogEntry/showUninstallConfirmation use.
    var settingsMenuExpanded by remember { mutableStateOf(false) }
    var showChangeEmailDialog by remember { mutableStateOf(false) }
    var showTwoFactorDialog by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CloudQueue, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Text("Account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))

                // The account settings entry point - a gear icon opening "Reset Password"/"Change
                // Email", the two account-security actions this card exposes beyond plain display.
                Box {
                    IconButton(onClick = { settingsMenuExpanded = true }, enabled = !viewModel.busy) {
                        Icon(Icons.Filled.Settings, contentDescription = "Account settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = settingsMenuExpanded, onDismissRequest = { settingsMenuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Reset Password") },
                            leadingIcon = { Icon(Icons.Filled.LockReset, contentDescription = null) },
                            onClick = {
                                settingsMenuExpanded = false
                                viewModel.currentUserEmail?.let { viewModel.requestPasswordReset(it) }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Change Email") },
                            leadingIcon = { Icon(Icons.Filled.AlternateEmail, contentDescription = null) },
                            onClick = {
                                settingsMenuExpanded = false
                                showChangeEmailDialog = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Two-Factor Authentication") },
                            leadingIcon = { Icon(Icons.Filled.Security, contentDescription = null) },
                            onClick = {
                                settingsMenuExpanded = false
                                showTwoFactorDialog = true
                            },
                        )
                    }
                }
            }
            InfoRow(Icons.Filled.AlternateEmail, "Email address", viewModel.currentUserEmail ?: "-")
            InfoRow(Icons.Filled.Storage, "Storage", formatStorageStatus(viewModel.currentUserUploadedBytes, viewModel.currentUserMaxBytesToUpload))
            InfoRow(Icons.Filled.Badge, "Joined", viewModel.currentUserCreatedAtEpochMillis?.let(::formatJoinedDate) ?: "-")
            InfoRow(Icons.Filled.Badge, "Account ID", viewModel.currentUserId ?: "-")
        }
    }

    if (showChangeEmailDialog) {
        ChangeEmailDialog(viewModel = viewModel, onDismiss = { showChangeEmailDialog = false })
    }
    if (showTwoFactorDialog) {
        TwoFactorAuthDialog(
            viewModel = viewModel,
            onDismiss = {
                viewModel.cancelTwoFactorSetup()
                showTwoFactorDialog = false
            },
        )
    }
}

/**
 * The "Change Email" settings action - a two-step dialog mirroring [AppViewModel.requestEmailChange]/
 * [AppViewModel.confirmEmailChange]'s own two-step shape: step one collects the new address and
 * e-mails a code there, step two collects that code and actually applies the change. Which step is
 * showing is driven directly by [AppViewModel.pendingEmailChangeAddress] rather than separate local
 * state, so the dialog always reflects the real flow state (e.g. if it's reopened while a request
 * from an earlier open is still pending). [enteredCodeStep] only exists to notice the *transition*
 * from step two back to "no pending change" (a successful confirmation) so the dialog can close
 * itself automatically - [AppViewModel.cancelEmailChangeRequest]/a failed [AppViewModel.confirmEmailChange]
 * both leave [AppViewModel.pendingEmailChangeAddress] in a state this same effect handles correctly
 * (dismissed explicitly, or simply left in step two with [AppViewModel.errorMessage] set).
 */
@Composable
private fun ChangeEmailDialog(viewModel: AppViewModel, onDismiss: () -> Unit) {
    var newEmail by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var enteredCodeStep by remember { mutableStateOf(viewModel.pendingEmailChangeAddress != null) }

    LaunchedEffect(viewModel.pendingEmailChangeAddress) {
        if (viewModel.pendingEmailChangeAddress != null) {
            enteredCodeStep = true
        } else if (enteredCodeStep) {
            onDismiss()
        }
    }

    val pendingAddress = viewModel.pendingEmailChangeAddress

    AlertDialog(
        onDismissRequest = {
            if (pendingAddress != null) viewModel.cancelEmailChangeRequest()
            onDismiss()
        },
        icon = { Icon(Icons.Filled.AlternateEmail, contentDescription = null) },
        title = { Text(if (pendingAddress == null) "Change email address" else "Confirm new email") },
        text = {
            Column {
                if (pendingAddress == null) {
                    Text(
                        "Enter the new address you'd like to use. We'll e-mail a verification code there first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newEmail,
                        onValueChange = { newEmail = it },
                        label = { Text("New email address") },
                        singleLine = true,
                        enabled = !viewModel.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        "Enter the code we sent to $pendingAddress.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text("Verification code") },
                        singleLine = true,
                        enabled = !viewModel.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                viewModel.errorMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (pendingAddress == null) viewModel.requestEmailChange(newEmail) else viewModel.confirmEmailChange(code) },
                enabled = !viewModel.busy && (if (pendingAddress == null) newEmail.isNotBlank() else code.isNotBlank()),
            ) {
                Text(if (pendingAddress == null) "Send code" else "Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                if (pendingAddress != null) viewModel.cancelEmailChangeRequest()
                onDismiss()
            }) { Text("Cancel") }
        },
    )
}

/**
 * The "Two-Factor Authentication" settings action (item 12, see `architecture/SERVICES.md`) - one
 * dialog covering both directions, since the client has no way to know up front whether the
 * account currently has two-factor authentication enabled (there is no `GET /me`-style field for
 * it, the same reasoning [AppViewModel.currentUserEmail] documents for itself): "Enable" starts
 * [AppViewModel.beginTwoFactorSetup]/[AppViewModel.confirmTwoFactorSetup]'s two-step flow (secret
 * display, then a code to confirm), driven by [AppViewModel.pendingTwoFactorSetup] the same way
 * [ChangeEmailDialog] is driven by [AppViewModel.pendingEmailChangeAddress]; "Disable" is a single
 * password field firing [AppViewModel.disableTwoFactor] and closing immediately (fire-and-forget,
 * matching the settings menu's own "Reset Password" entry) - a wrong password surfaces via this
 * screen's own general [AppViewModel.errorMessage] banner rather than keeping the dialog open.
 */
@Composable
private fun TwoFactorAuthDialog(viewModel: AppViewModel, onDismiss: () -> Unit) {
    var disableMode by remember { mutableStateOf(false) }
    var confirmCode by remember { mutableStateOf("") }
    var disablePassword by remember { mutableStateOf("") }
    var enteredSetupStep by remember { mutableStateOf(viewModel.pendingTwoFactorSetup != null) }

    val pendingSetup = viewModel.pendingTwoFactorSetup

    // Auto-close once a setup started in this dialog completes (pendingSetup goes back to null) -
    // the same "notice the transition, don't just check for null" shape ChangeEmailDialog's own
    // enteredCodeStep uses, needed since pendingSetup also starts out null before setup begins.
    LaunchedEffect(pendingSetup) {
        if (pendingSetup != null) enteredSetupStep = true
        else if (enteredSetupStep) onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Security, contentDescription = null) },
        title = {
            Text(
                when {
                    pendingSetup != null -> "Confirm two-factor authentication"
                    disableMode -> "Disable two-factor authentication"
                    else -> "Two-factor authentication"
                }
            )
        },
        text = {
            Column {
                when {
                    pendingSetup != null -> {
                        Text(
                            "Scan this into your authenticator app, or enter the secret manually, then enter the code it shows.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(pendingSetup.secretBase32(), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = confirmCode,
                            onValueChange = { confirmCode = it },
                            label = { Text("Authentication code") },
                            singleLine = true,
                            enabled = !viewModel.busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    disableMode -> {
                        Text(
                            "Enter your password to disable two-factor authentication for this account.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = disablePassword,
                            onValueChange = { disablePassword = it },
                            label = { Text("Password") },
                            singleLine = true,
                            enabled = !viewModel.busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    else -> {
                        Text(
                            "Enable two-factor authentication to require a code from an authenticator app on every sign-in, "
                                + "or disable it if it's already on.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                viewModel.errorMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            when {
                pendingSetup != null -> Button(
                    onClick = { viewModel.confirmTwoFactorSetup(confirmCode) },
                    enabled = !viewModel.busy && confirmCode.isNotBlank(),
                ) { Text("Confirm") }
                disableMode -> Button(
                    onClick = {
                        viewModel.disableTwoFactor(disablePassword)
                        onDismiss()
                    },
                    enabled = !viewModel.busy && disablePassword.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Disable") }
                else -> Button(onClick = { viewModel.beginTwoFactorSetup() }, enabled = !viewModel.busy) { Text("Enable") }
            }
        },
        dismissButton = {
            when {
                pendingSetup != null -> TextButton(onClick = onDismiss) { Text("Cancel") }
                disableMode -> TextButton(onClick = { disableMode = false }) { Text("Back") }
                else -> TextButton(onClick = { disableMode = true }) { Text("Disable instead") }
            }
        },
    )
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(140.dp))
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StatCard(icon: ImageVector, label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = modifier,
    ) {
        Column(Modifier.padding(20.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(12.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
