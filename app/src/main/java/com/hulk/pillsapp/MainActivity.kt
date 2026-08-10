package com.hulk.pillsapp

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        NotificationListenerState.refreshPermission(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppHome(
                        onOpenNotificationAccessPage = {
                            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            permissionLauncher.launch(intent)
                        },
                        onRefresh = { NotificationListenerState.refreshPermission(this) }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        NotificationListenerState.refreshPermission(this)
    }
}

@Composable
private fun AppHome(
    onOpenNotificationAccessPage: () -> Unit,
    onRefresh: () -> Unit,
) {
    val state by NotificationListenerState.state.collectAsState()

    val appName = stringResource(R.string.app_name)
    val appNameLabel = stringResource(R.string.app_name_label)
    val versionLabel = stringResource(R.string.version_name_label)
    val buildTimeLabel = stringResource(R.string.build_time_label)
    val statusLabel = stringResource(R.string.app_running_label)
    val permissionLabel = stringResource(R.string.notification_permission_label)
    val serviceConnectionLabel = stringResource(R.string.notification_service_label)
    val jumpButtonLabel = stringResource(R.string.go_to_access_settings_label)
    val lastConnectLabel = stringResource(R.string.notification_last_connected_label)
    val lastDisconnectLabel = stringResource(R.string.notification_last_disconnected_label)
    val permissionText = stringResource(
        R.string.notification_permission_status_format,
        NotificationListenerState.permissionLabel(state.permissionEnabled)
    )
    val serviceConnectText = stringResource(
        R.string.notification_service_connection_format,
        NotificationListenerState.serviceConnectionLabel(state.isConnected)
    )
    val lastConnectedText = stringResource(
        R.string.notification_last_connected_time_format,
        formatConnectionTime(state.lastConnectedAtMs)
    )
    val lastDisconnectedText = stringResource(
        R.string.notification_last_disconnected_time_format,
        formatConnectionTime(state.lastDisconnectedAtMs)
    )

    LaunchedEffect(Unit) { onRefresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(text = "$appNameLabel：$appName")
        Text(text = "$versionLabel：${BuildConfig.VERSION_NAME}")
        Text(text = "$buildTimeLabel：${BuildConfig.BUILD_TIME}")
        Text(text = statusLabel)
        Spacer(modifier = Modifier.height(24.dp))
        Text(text = permissionLabel)
        Text(text = permissionText)
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = serviceConnectionLabel)
        Text(text = serviceConnectText)
        Text(text = "$lastConnectLabel：$lastConnectedText")
        Text(text = "$lastDisconnectLabel：$lastDisconnectedText")
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = onOpenNotificationAccessPage,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = jumpButtonLabel)
        }
    }
}
