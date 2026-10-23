package com.hulk.manualledger

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import java.io.File

class CityIconPresentationTest {
    @get:Rule val compose=createComposeRule()
    @Test fun customVectorsAndAdaptiveForegroundsRenderAtSmallAndLargeSizes() {
        compose.setContent { LedgerTheme {
            Surface(color=MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp),
                    verticalArrangement=Arrangement.spacedBy(18.dp)) {
                    Text("本地账本 · 图标设计",style=MaterialTheme.typography.titleLarge)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                        listOf(R.drawable.ic_launcher_foreground to Color(0xFF963F51),
                            R.drawable.ic_launcher_foreground_green to Color(0xFF285C50)).forEach { (id,color) ->
                            Surface(shape=MaterialTheme.shapes.large,color=color) {
                                Image(painterResource(id),"桌面图标预览",Modifier.size(104.dp))
                            }
                        }
                    }
                    Text("导航、分类与操作",style=MaterialTheme.typography.titleMedium)
                    LedgerIcons.all.chunked(5).forEach { icons ->
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            icons.forEach { icon ->
                                Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surface) {
                                    Box(Modifier.size(52.dp),contentAlignment=Alignment.Center) {
                                        Icon(icon,icon.name,Modifier.size(30.dp),tint=MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                    Text("放大细节",style=MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                        Icon(LedgerIcons.Food,null,Modifier.size(72.dp),tint=Color(0xFFB5813F))
                        Icon(LedgerIcons.Home,null,Modifier.size(72.dp),tint=Color(0xFF4C9182))
                        Icon(LedgerIcons.Gift,null,Modifier.size(72.dp),tint=Color(0xFFB57385))
                    }
                }
            }
        } }
        compose.onNodeWithContentDescription("LedgerHome").assertIsDisplayed()
        compose.onNodeWithContentDescription("LedgerTrash").assertIsDisplayed()
        val context=ApplicationProvider.getApplicationContext<Context>()
        File(context.cacheDir,"city-icons.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)
        }
    }
}
