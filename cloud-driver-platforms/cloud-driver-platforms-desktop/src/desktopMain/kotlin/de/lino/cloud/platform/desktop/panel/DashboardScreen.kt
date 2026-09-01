package de.lino.cloud.platform.desktop.panel

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
                Text("Account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            InfoRow(Icons.Filled.AlternateEmail, "Email address", viewModel.currentUserEmail ?: "-")
            InfoRow(Icons.Filled.Storage, "Storage", formatStorageStatus(viewModel.currentUserUploadedBytes, viewModel.currentUserMaxBytesToUpload))
            InfoRow(Icons.Filled.Badge, "Joined", viewModel.currentUserCreatedAtEpochMillis?.let(::formatJoinedDate) ?: "-")
            InfoRow(Icons.Filled.Badge, "Account ID", viewModel.currentUserId ?: "-")
        }
    }
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
