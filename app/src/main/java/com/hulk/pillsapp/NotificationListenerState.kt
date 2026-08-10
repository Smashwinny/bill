package com.hulk.pillsapp

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ListenerStatusState(
    val permissionEnabled: Boolean = false,
    val isConnected: Boolean = false,
    val lastConnectedAtMs: Long? = null,
    val lastDisconnectedAtMs: Long? = null,
)

private val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

object NotificationListenerState {
    private val _state = MutableStateFlow(ListenerStatusState())
    val state: StateFlow<ListenerStatusState> = _state.asStateFlow()

    fun refreshPermission(context: Context) {
        val enabled = isNotificationListenerEnabled(context)
        _state.value = _state.value.copy(permissionEnabled = enabled)
        if (!enabled) {
            _state.value = _state.value.copy(isConnected = false)
        }
    }

    fun setConnected(nowMillis: Long = System.currentTimeMillis()) {
        _state.value = _state.value.copy(
            isConnected = true,
            lastConnectedAtMs = nowMillis
        )
    }

    fun setDisconnected(nowMillis: Long = System.currentTimeMillis()) {
        _state.value = _state.value.copy(
            isConnected = false,
            lastDisconnectedAtMs = nowMillis
        )
    }

    fun isNotificationListenerEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?: return false

        val expected = ComponentName(context, NotificationListenerService::class.java).flattenToString()
        return enabled
            .split(":")
            .map(String::trim)
            .any { it.isNotEmpty() && it == expected }
    }

    fun permissionLabel(enabled: Boolean): String {
        return if (enabled) "已开启" else "未开启"
    }

    fun serviceConnectionLabel(isConnected: Boolean): String {
        return if (isConnected) "已连接" else "未连接"
    }
}

fun formatConnectionTime(timestampMs: Long?, zoneId: ZoneId = ZoneId.systemDefault()): String {
    return timestampMs?.let {
        Instant.ofEpochMilli(it)
            .atZone(zoneId)
            .format(formatter)
    } ?: "未发生"
}
