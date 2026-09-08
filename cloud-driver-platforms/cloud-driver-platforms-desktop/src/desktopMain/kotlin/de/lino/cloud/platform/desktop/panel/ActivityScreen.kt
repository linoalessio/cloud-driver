package de.lino.cloud.platform.desktop.panel

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.lino.cloud.platform.desktop.viewmodel.AppViewModel
import de.lino.cloud.platform.rest.api.dto.Dtos.ActivityEntryResponse
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ACTIVITY_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

/** One row of an audit-trail entry - shared by the global [ActivityFeedScreen] and a per-file/folder scoped activity dialog (`FileBrowserScreen.kt`'s `EntryActivityDialog`). */
@Composable
fun ActivityEntryRow(entry: ActivityEntryResponse) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(entry.action().replace('_', ' '), fontWeight = FontWeight.SemiBold)
            entry.targetId()?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            ACTIVITY_DATE_FORMAT.format(Instant.ofEpochMilli(entry.timestampEpochMillis())),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The global activity feed (`Screen.Activity`) - every recorded action across every file/folder
 * the signed-in account can see, newest first, "Load more" pagination matching the existing
 * folder-view pattern (an explicit button rather than auto-load-on-scroll, so fetching the next
 * page - a real network round trip - only ever happens on a deliberate click).
 */
@Composable
fun ActivityFeedScreen(viewModel: AppViewModel) {
    var entries by remember { mutableStateOf<List<ActivityEntryResponse>>(emptyList()) }
    var cursor by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var screenError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        loading = true
        screenError = null
        try {
            val firstPage = viewModel.client.listActivity(null, 50)
            entries = firstPage.items()
            cursor = firstPage.nextCursor()
        } catch (e: Exception) {
            screenError = e.message ?: "Failed to load activity"
        } finally {
            loading = false
        }
    }

    AuthenticatedShell(viewModel) {
        Column(Modifier.fillMaxSize().padding(32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Timeline, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("Activity", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Everything recorded across every file/folder you can see.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            when {
                loading -> CircularProgressIndicator(modifier = Modifier.size(20.dp))
                screenError != null -> Text(screenError!!, color = MaterialTheme.colorScheme.error)
                entries.isEmpty() -> Text("No recorded activity yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    LazyColumn(Modifier.weight(1f)) {
                        items(entries, key = { "${it.timestampEpochMillis()}-${it.action()}-${it.targetId()}" }) { entry ->
                            ActivityEntryRow(entry)
                            HorizontalDivider()
                        }
                        if (cursor != null) {
                            item(key = "__load_more_activity__") {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    if (loadingMore) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                    } else {
                                        OutlinedButton(onClick = {
                                            val c = cursor ?: return@OutlinedButton
                                            loadingMore = true
                                            scope.launch {
                                                try {
                                                    val nextPage = viewModel.client.listActivity(c, 50)
                                                    entries = entries + nextPage.items()
                                                    cursor = nextPage.nextCursor()
                                                } catch (e: Exception) {
                                                    screenError = e.message ?: "Failed to load more activity"
                                                } finally {
                                                    loadingMore = false
                                                }
                                            }
                                        }) { Text("Load more") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
