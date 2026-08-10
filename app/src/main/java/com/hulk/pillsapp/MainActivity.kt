package com.hulk.pillsapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppHome()
                }
            }
        }
    }
}

@Composable
private fun AppHome() {
    val appName = stringResource(R.string.app_name)
    val appNameLabel = stringResource(R.string.app_name_label)
    val versionLabel = stringResource(R.string.version_name_label)
    val buildTimeLabel = stringResource(R.string.build_time_label)
    val statusLabel = stringResource(R.string.app_running_label)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(text = "${appNameLabel}：$appName")
        Text(text = "$versionLabel：${BuildConfig.VERSION_NAME}")
        Text(text = "$buildTimeLabel：${BuildConfig.BUILD_TIME}")
        Text(text = statusLabel)
    }
}
