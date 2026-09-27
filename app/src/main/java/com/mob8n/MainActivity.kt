package com.mob8n

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mob8n.ui.App

/** Shell only: theme + root composable live in the ui lane. Launch/new intents are forwarded via Mob8NApp.uiIntents. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Mob8NApp.of(this).uiIntents.value = intent
        setContent { App() }
    }

    // uiMode is in the manifest's configChanges (no recreate on light/dark switch): re-derive the status/nav bar icon colours,
    // otherwise a dark -> light switch leaves white status-bar icons on the paper background (device phase v6).
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 35) {   // the tablet's app handle (caption bar) keeps its old colour otherwise; APPEARANCE_LIGHT_CAPTION_BARS = 1 shl 8 (API 35, compileSdk 34)
            val dark = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            window.insetsController?.setSystemBarsAppearance(if (dark) 0 else 1 shl 8, 1 shl 8)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Mob8NApp.of(this).uiIntents.value = intent
    }
}
