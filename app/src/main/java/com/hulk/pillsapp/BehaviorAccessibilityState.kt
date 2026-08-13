package com.hulk.pillsapp

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BehaviorAccessibilityStatus(
    val permissionEnabled: Boolean = false,
    val serviceConnected: Boolean = false,
)

object BehaviorAccessibilityState {
    private val _state = MutableStateFlow(BehaviorAccessibilityStatus())
    val state = _state.asStateFlow()

    fun refreshPermission(context: Context) {
        _state.value = _state.value.copy(permissionEnabled = isEnabled(context))
    }

    fun setConnected(context: Context, connected: Boolean) {
        _state.value = BehaviorAccessibilityStatus(
            permissionEnabled = isEnabled(context),
            serviceConnected = connected,
        )
    }

    fun isServiceConnected(): Boolean = _state.value.serviceConnected

    fun isEnabled(context: Context): Boolean {
        val expected = ComponentName(context, PaymentBehaviorAccessibilityService::class.java)
            .flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}
