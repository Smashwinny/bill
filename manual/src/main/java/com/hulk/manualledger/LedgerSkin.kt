package com.hulk.manualledger

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

enum class LedgerSkin(val label: String, val launcher: String) {
    RED("醒目红", "Red"), GREEN("经典绿", "Green");
}

/** Appearance preferences and launcher components only; never opens the ledger database. */
object LedgerSkinSettings {
    private const val PREFS = "manual_appearance"
    private const val KEY = "skin"

    fun load(context: Context): LedgerSkin =
        LedgerSkin.entries.firstOrNull {
            it.name == context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        } ?: LedgerSkin.RED

    fun select(context: Context, skin: LedgerSkin) {
        val previous = load(context)
        try {
            applyLauncher(context, skin)
            check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, skin.name).commit()) { "皮肤设置未保存，请重试" }
        } catch (error: Exception) {
            runCatching { applyLauncher(context, previous) }
            throw error
        }
    }

    private fun applyLauncher(context: Context, selected: LedgerSkin) {
        val manager = context.packageManager
        fun component(skin: LedgerSkin) = ComponentName(context.packageName,
            "${context.packageName}.launcher.${skin.launcher}")
        // Enable the replacement before hiding the old icon, so a launcher always remains.
        manager.setComponentEnabledSetting(component(selected),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        LedgerSkin.entries.filter { it != selected }.forEach { skin ->
            manager.setComponentEnabledSetting(component(skin),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }
}
