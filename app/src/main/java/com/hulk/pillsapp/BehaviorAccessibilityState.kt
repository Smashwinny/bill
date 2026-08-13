package com.hulk.pillsapp

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.hulk.pillsapp.ledger.LedgerKernel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BehaviorAccessibilityStatus(
    val permissionEnabled: Boolean = false,
    val serviceConnected: Boolean = false,
)

object BehaviorAccessibilityState {
    private val _state = MutableStateFlow(BehaviorAccessibilityStatus())
    val state = _state.asStateFlow()
    private val callbackOwnerGate = CallbackOwnerGate()

    fun refreshPermission(context: Context) {
        val enabled = isEnabled(context)
        _state.value = BehaviorAccessibilityStatus(
            permissionEnabled = enabled,
            // 进程重建后系统可能保持服务绑定而不会立刻再次回调；新鲜持久心跳用于恢复展示。
            serviceConnected = enabled && (callbackOwnerGate.hasOwner() || LedgerKernel.isA11yHeartbeatFresh()),
        )
    }

    /** 新连接实例接管所有权，使旧实例的迟到 onDestroy 无法误报断开。 */
    fun registerCallbackOwner(context: Context, owner: Any) {
        callbackOwnerGate.register(owner)
        _state.value = BehaviorAccessibilityStatus(
            permissionEnabled = isEnabled(context),
            serviceConnected = true,
        )
    }

    /** 原子判定当前、无 owner 恢复或旧实例，防止旧实例继续生成重复候选。 */
    internal fun claimCallbackOwner(context: Context, owner: Any): CallbackOwnerAccess {
        val access = callbackOwnerGate.claimOrCheck(owner)
        if (access == CallbackOwnerAccess.RECOVERED) {
            _state.value = BehaviorAccessibilityStatus(
                permissionEnabled = isEnabled(context),
                serviceConnected = true,
            )
        }
        return access
    }

    /** 只有当前回调拥有者能将服务标记为断开。 */
    fun clearCallbackConnected(context: Context, owner: Any): Boolean {
        if (!callbackOwnerGate.clear(owner)) return false
        _state.value = BehaviorAccessibilityStatus(
            permissionEnabled = isEnabled(context),
            serviceConnected = false,
        )
        return true
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

internal enum class CallbackOwnerAccess {
    CURRENT,
    RECOVERED,
    STALE,
}

/** 不依赖 Android 的实例所有权门，便于对服务交替竞态做确定性测试。 */
internal class CallbackOwnerGate {
    private var owner: Any? = null

    @Synchronized
    fun register(candidate: Any) {
        owner = candidate
    }

    @Synchronized
    fun claimOrCheck(candidate: Any): CallbackOwnerAccess = when {
        owner === candidate -> CallbackOwnerAccess.CURRENT
        owner == null -> {
            owner = candidate
            CallbackOwnerAccess.RECOVERED
        }
        else -> CallbackOwnerAccess.STALE
    }

    @Synchronized
    fun clear(candidate: Any): Boolean {
        if (owner !== candidate) return false
        owner = null
        return true
    }

    @Synchronized
    fun hasOwner(): Boolean = owner != null
}
