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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.lino.cloud.platform.desktop.viewmodel.AppViewModel
import de.lino.cloud.platform.rest.api.dto.Dtos.AuditLogEntryResponse
import de.lino.cloud.platform.rest.api.dto.Dtos.AuthUserResponse
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Screen-local formatter - same "not shared code" convention TrashScreen.kt/DashboardScreen.kt
// each already follow for their own near-identical timestamp formatters.
private val ADMIN_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

private fun formatEpochMilli(epochMilli: Long): String = ADMIN_DATE_FORMAT.format(Instant.ofEpochMilli(epochMilli))

/**
 * Read-only admin panel (item 5's `GET /admin/authUsers` plus item 11's audit trail, both
 * admin-gated server-side) - only reachable while [AppViewModel.currentUserIsAdmin] (the sidebar
 * hides the entry otherwise; the server itself is the real enforcement point via its `requireAdmin`
 * filter, this is only UI-level convenience). Deliberately view-only: granting/revoking the admin
 * flag itself is not exposed here, or anywhere over REST - see `CLAUDE.md`'s "Admin flag and
 * `/admin/authUsers` routes" section - it stays a terminal-only operation (the `admin`/`isAdmin`
 * command), specifically to avoid reopening a privilege-escalation hole that decision closed.
 */
@Composable
fun AdminScreen(viewModel: AppViewModel) {
    LaunchedEffect(Unit) { viewModel.loadAdmin() }

    AuthenticatedShell(viewModel) {
        Column(Modifier.fillMaxSize().padding(32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AdminPanelSettings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("Admin", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Registered accounts and the persisted security audit trail. Read-only.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { viewModel.loadAdmin() }, enabled = !viewModel.busy) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Refresh")
                }
                FilterChip(
                    selected = viewModel.adminAuditLogShowAll,
                    onClick = { viewModel.loadAdmin(showAll = !viewModel.adminAuditLogShowAll) },
                    label = { Text(if (viewModel.adminAuditLogShowAll) "Showing all audit entries" else "Showing recent 20") },
                    enabled = !viewModel.busy,
                    colors = FilterChipDefaults.filterChipColors(),
                )
                if (viewModel.busy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                }
            }

            viewModel.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
            }

            Spacer(Modifier.height(20.dp))

            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f).fillMaxSize()) {
                    SectionHeader(icon = Icons.Filled.VerifiedUser, title = "Accounts (${viewModel.adminAuthUsers.size})")
                    Spacer(Modifier.height(10.dp))
                    if (viewModel.adminAuthUsers.isEmpty()) {
                        Text("No accounts.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
                            items(viewModel.adminAuthUsers, key = { it.id() }) { user -> AuthUserRow(user) }
                        }
                    }
                }

                Column(Modifier.weight(1f).fillMaxSize()) {
                    SectionHeader(icon = Icons.Filled.History, title = "Audit trail (${viewModel.adminAuditLog.size})")
                    Spacer(Modifier.height(10.dp))
                    if (viewModel.adminAuditLog.isEmpty()) {
                        Text("No audit entries.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
                            items(viewModel.adminAuditLog, key = { "${it.timestampEpochMillis()}-${it.action()}-${it.targetId()}" }) { entry ->
                                AuditLogRow(entry)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun AuthUserRow(user: AuthUserResponse) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(user.emailAddress(), fontWeight = FontWeight.Medium)
                Text(user.id(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (user.isAdmin()) {
                Icon(Icons.Filled.AdminPanelSettings, contentDescription = "Admin", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun AuditLogRow(entry: AuditLogEntryResponse) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.action(), fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(10.dp))
                Text(formatEpochMilli(entry.timestampEpochMillis()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "actor: ${entry.actorEmail() ?: "-"} - target: ${entry.targetId() ?: "-"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
