package com.hulk.pillsapp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val eventTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshState(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshState(this)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppHome(
                        onOpenNotificationAccessPage = {
                            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            permissionLauncher.launch(intent)
                        },
                        onRefresh = {
                            refreshState(this@MainActivity)
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState(this)
    }

    private fun refreshState(context: Context) {
        NotificationListenerState.refreshPermission(context)
        NotificationEventRepository.refreshPermissionPackages(context)
        NotificationEventRepository.refreshEvents(context)
    }
}

@Composable
private fun AppHome(
    onOpenNotificationAccessPage: () -> Unit,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    val state by NotificationListenerState.state.collectAsState()
    val events by NotificationEventRepository.events.collectAsState()
    val enabledPackages by NotificationEventRepository.enabledPackages.collectAsState()
    var packageInput by remember { mutableStateOf("") }
    var showClearDialog by remember { mutableStateOf(false) }
    var packageInputError by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    var packageHint by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { onRefresh() }

    val appNameLabel = stringResource(R.string.app_name_label)
    val versionLabel = stringResource(R.string.version_name_label)
    val buildTimeLabel = stringResource(R.string.build_time_label)
    val statusLabel = stringResource(R.string.app_running_label)
    val permissionLabel = stringResource(R.string.notification_permission_label)
    val serviceConnectionLabel = stringResource(R.string.notification_service_label)
    val jumpButtonLabel = stringResource(R.string.go_to_access_settings_label)
    val lastConnectLabel = stringResource(R.string.notification_last_connected_label)
    val lastDisconnectLabel = stringResource(R.string.notification_last_disconnected_label)
    val packageSectionTitle = stringResource(R.string.enabled_packages_title)
    val packageLabel = stringResource(R.string.package_name_label)
    val addPackageLabel = stringResource(R.string.add_package_label)
    val eventsTitle = stringResource(R.string.notification_events_title)
    val clearTestData = stringResource(R.string.clear_test_data_label)
    val privacyHint = stringResource(R.string.t03_privacy_notice)
    val noEventsHint = stringResource(R.string.no_events)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(text = "$appNameLabel：${stringResource(R.string.app_name)}")
        Text(text = "$versionLabel：${BuildConfig.VERSION_NAME}")
        Text(text = "$buildTimeLabel：${BuildConfig.BUILD_TIME}")
        Text(text = statusLabel)

        Spacer(modifier = Modifier.height(16.dp))
        Text(text = permissionLabel)
        Text(
            text = stringResource(
                R.string.notification_permission_status_format,
                NotificationListenerState.permissionLabel(state.permissionEnabled),
            )
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = serviceConnectionLabel)
        Text(
            text = stringResource(
                R.string.notification_service_connection_format,
                NotificationListenerState.serviceConnectionLabel(state.isConnected),
            )
        )
        Text(text = "$lastConnectLabel：${formatEventTime(state.lastConnectedAtMs)}")
        Text(text = "$lastDisconnectLabel：${formatEventTime(state.lastDisconnectedAtMs)}")
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onOpenNotificationAccessPage, modifier = Modifier.fillMaxWidth()) {
            Text(text = jumpButtonLabel)
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text(text = packageSectionTitle)
        OutlinedTextField(
            value = packageInput,
            onValueChange = {
                packageInput = it
                packageInputError = null
                packageHint = validatePackageFormat(context, it)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(packageLabel) },
            isError = packageInputError != null || packageHint != null,
            supportingText = {
                val tip = packageInputError ?: packageHint
                if (tip != null) {
                    Text(text = tip)
                }
            },
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                val trimmed = packageInput.trim()
                val canAdd = trimmed.isNotBlank() && packageHint == null
                if (!canAdd) {
                    packageInputError = packageInput.trim().ifBlank {
                        context.getString(R.string.package_name_required_error)
                    }
                    return@Button
                }
                NotificationEventRepository.addEnabledPackage(context, trimmed)
                packageInput = ""
                packageHint = null
                packageInputError = null
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(addPackageLabel)
        }

        Spacer(modifier = Modifier.height(8.dp))
        if (enabledPackages.isEmpty()) {
            Text(text = stringResource(R.string.no_enabled_packages))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
            ) {
                items(enabledPackages) { packageName ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = packageName,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            NotificationEventRepository.removeEnabledPackage(context, packageName)
                        }) {
                            Text(text = stringResource(R.string.remove_package_label))
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text(text = eventsTitle)
        if (events.isEmpty()) {
            Text(text = noEventsHint)
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
            ) {
                items(events) { event ->
                    Card(
                        colors = CardDefaults.cardColors(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(text = "${event.packageName} · ${event.notificationKey}")
                            Text(text = "标题：${event.title}")
                            Text(text = "正文：${event.body}")
                            Text(text = "发布：${formatEventTime(event.postedAtMs)}")
                            Text(text = "接收：${formatEventTime(event.receivedAtMs)}")
                            Text(text = "内容哈希：${event.contentHash}")
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = { showClearDialog = true }, modifier = Modifier.fillMaxWidth()) {
            Text(clearTestData)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(privacyHint)

        if (showClearDialog) {
            AlertDialog(
                onDismissRequest = { showClearDialog = false },
                title = { Text(stringResource(R.string.clear_data_confirm_title)) },
                text = { Text(stringResource(R.string.clear_data_confirm_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showClearDialog = false
                            coroutineScope.launch {
                                NotificationEventRepository.clearAll(context)
                                onRefresh()
                            }
                        }
                    ) {
                        Text(stringResource(R.string.confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }
}

private fun formatEventTime(timestampMs: Long?, zoneId: ZoneId = ZoneId.systemDefault()): String {
    return timestampMs?.let {
        Instant.ofEpochMilli(it).atZone(zoneId).format(eventTimeFormatter)
    } ?: "未发生"
}

private fun validatePackageFormat(context: Context, value: String): String? {
    val pkg = value.trim()
    if (pkg.isBlank()) return null
    val regex = Regex("^[a-zA-Z][a-zA-Z0-9_\\-\\.]*[a-zA-Z0-9_\\-]\$")
    if (!regex.matches(pkg)) {
        return context.getString(R.string.package_name_format_error)
    }
    return try {
        context.packageManager.getPackageInfo(pkg, 0)
        if (pkg.contains("..")) {
            context.getString(R.string.package_name_format_error)
        } else {
            null
        }
    } catch (_: PackageManager.NameNotFoundException) {
        context.getString(R.string.package_not_found_warning)
    }
}
