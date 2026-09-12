package com.hulk.manualledger

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LedgerSkinSettingsTest {
    @Test fun switchingSkinsPersistsAndKeepsExactlyOneLauncher() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // This test changes appearance: only ever run on the explicitly isolated package.
        assumeTrue(context.packageName == "com.hulk.manualledger.acceptance")
        val original = LedgerSkinSettings.load(context)
        try {
            listOf(LedgerSkin.GREEN, LedgerSkin.RED).forEach { skin ->
                LedgerSkinSettings.select(context, skin)
                assertEquals(skin, LedgerSkinSettings.load(context))
                val launchers = context.packageManager.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setPackage(context.packageName), 0)
                assertEquals(1, launchers.size)
                val launcher = launchers.single()
                assertEquals("${context.packageName}.launcher.${skin.launcher}", launcher.activityInfo.name)
                assertEquals(if (skin == LedgerSkin.RED) R.mipmap.ic_launcher else R.mipmap.ic_launcher_green,
                    launcher.activityInfo.iconResource)
                val icon = launcher.loadIcon(context.packageManager)
                val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                icon.setBounds(0, 0, 512, 512)
                icon.draw(Canvas(bitmap))
                File(context.cacheDir, "skin-${skin.name.lowercase()}.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        } finally {
            LedgerSkinSettings.select(context, original)
        }
    }
}
