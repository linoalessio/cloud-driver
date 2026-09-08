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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.lino.cloud.platform.desktop.viewmodel.AppViewModel
import de.lino.cloud.platform.rest.api.ApiClient
import de.lino.cloud.platform.rest.api.dto.Dtos.WebhookDeliveryAttemptResponse
import de.lino.cloud.platform.rest.api.dto.Dtos.WebhookSubscriptionCreatedResponse
import de.lino.cloud.platform.rest.api.dto.Dtos.WebhookSubscriptionSummaryResponse
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val WEBHOOK_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

private val WEBHOOK_EVENT_TYPES = listOf("FILE_UPLOADED", "FILE_DELETED", "FILE_SHARED")

/**
 * Manages registered webhook subscriptions (desktop-only - this app's own scope decision for
 * where this feature is worth building first): a registration form, the list of currently
 * registered webhooks with "Revoke", and a table of recent delivery attempts. Reached from the
 * Dashboard's account settings menu.
 */
@Composable
fun WebhooksScreen(viewModel: AppViewModel) {
    val scope = rememberCoroutineScope()
    var webhooks by remember { mutableStateOf<List<WebhookSubscriptionSummaryResponse>>(emptyList()) }
    var deliveries by remember { mutableStateOf<List<WebhookDeliveryAttemptResponse>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var unavailable by remember { mutableStateOf(false) }
    var screenError by remember { mutableStateOf<String?>(null) }

    var urlInput by remember { mutableStateOf("") }
    val selectedEventTypes = remember { mutableStateListOf<String>() }
    var registering by remember { mutableStateOf(false) }

    // The just-registered subscription's secret, shown exactly once - the server never returns it
    // again after this response, matching its own "shown once" contract (the same shape a
    // freshly-generated ApiKey/PublicShareLink already follow elsewhere in this system).
    var justCreated by remember { mutableStateOf<WebhookSubscriptionCreatedResponse?>(null) }

    suspend fun reload() {
        loading = true
        screenError = null
        try {
            webhooks = viewModel.client.listWebhooks()
            deliveries = viewModel.client.listWebhookDeliveries()
        } catch (e: ApiClient.ApiException) {
            if (e.statusCode() == 503) unavailable = true else screenError = e.message ?: "Failed to load webhooks"
        } catch (e: Exception) {
            screenError = e.message ?: "Failed to load webhooks"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    fun register() {
        val url = urlInput.trim()
        if (url.isEmpty() || selectedEventTypes.isEmpty() || registering) return
        registering = true
        scope.launch {
            try {
                justCreated = viewModel.client.registerWebhook(url, selectedEventTypes.toList())
                urlInput = ""
                selectedEventTypes.clear()
                reload()
            } catch (e: Exception) {
                screenError = e.message ?: "Failed to register webhook"
            } finally {
                registering = false
            }
        }
    }

    fun revoke(id: String) {
        scope.launch {
            try {
                viewModel.client.revokeWebhook(id)
                reload()
            } catch (e: Exception) {
                screenError = e.message ?: "Failed to revoke webhook"
            }
        }
    }

    AuthenticatedShell(viewModel) {
        Column(Modifier.fillMaxSize().padding(32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Extension, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("Webhooks", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Get an HTTP notification whenever a file is uploaded, deleted, or shared.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))

            when {
                loading -> CircularProgressIndicator(modifier = Modifier.size(20.dp))
                unavailable -> Text("Webhooks aren't available on this server.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    screenError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 12.dp)) }

                    Text("Register a new webhook", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        label = { Text("https://your-server.example/hook") },
                        singleLine = true,
                        enabled = !registering,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        for (eventType in WEBHOOK_EVENT_TYPES) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = eventType in selectedEventTypes,
                                    onCheckedChange = { checked ->
                                        if (checked) selectedEventTypes.add(eventType) else selectedEventTypes.remove(eventType)
                                    },
                                    enabled = !registering,
                                )
                                Text(eventType)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = ::register, enabled = !registering && urlInput.isNotBlank() && selectedEventTypes.isNotEmpty()) {
                        Text("Register")
                    }

                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(16.dp))

                    Text("Registered webhooks", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    if (webhooks.isEmpty()) {
                        Text("No webhooks registered yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        for (webhook in webhooks) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(webhook.url())
                                    Text(
                                        webhook.eventTypes().joinToString(", "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { revoke(webhook.id()) }) {
                                    Icon(Icons.Filled.PersonRemove, contentDescription = "Revoke", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(16.dp))

                    Text("Recent deliveries", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    if (deliveries.isEmpty()) {
                        Text("No deliveries yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyColumn(Modifier.weight(1f)) {
                            items(deliveries, key = { "${it.webhookId()}-${it.attemptedAtEpochMillis()}-${it.attemptNumber()}" }) { delivery ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        if (delivery.succeeded()) Icons.Filled.VerifiedUser else Icons.Filled.ErrorOutline,
                                        contentDescription = null,
                                        tint = if (delivery.succeeded()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("${delivery.eventType()} - ${delivery.targetId()}")
                                        Text(
                                            "${WEBHOOK_DATE_FORMAT.format(Instant.ofEpochMilli(delivery.attemptedAtEpochMillis()))} - attempt ${delivery.attemptNumber()}" +
                                                (delivery.responseStatusCode()?.let { " - HTTP $it" } ?: ""),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    justCreated?.let { created ->
        WebhookSecretDialog(created = created, onDismiss = { justCreated = null })
    }
}

/**
 * Shows a freshly-registered webhook's signing secret exactly once, with an explicit warning that
 * it won't be shown again - mirrors the server's own `WebhookSubscriptionCreated`/`PublicShareLink`
 * "shown once" contract.
 */
@Composable
private fun WebhookSecretDialog(created: WebhookSubscriptionCreatedResponse, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Webhook registered") },
        text = {
            Column {
                Text(
                    "This is the only time this signing secret will be shown. Store it now - it verifies the X-Webhook-Signature header on every delivery.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(created.secret(), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = { clipboard.setText(AnnotatedString(created.secret())) }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy secret")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("I've saved it") }
        },
    )
}
