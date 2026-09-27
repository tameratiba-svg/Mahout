package com.mob8n

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.mob8n.ai.ChatRunner
import com.mob8n.ai.OperatorTools
import com.mob8n.ai.SkillPresets
import com.mob8n.ai.Triage
import com.mob8n.ai.Usage
import com.mob8n.apps.JsRuntime
import com.mob8n.core.Catalog
import com.mob8n.core.SETTINGS_PREFS
import com.mob8n.core.asTextOrNull
import com.mob8n.engine.Engine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class Mob8NApp : Application() {
    val catalog: Catalog by lazy {
        Catalog(listOf(
            com.mob8n.triggers.TriggerNodes.all,
            com.mob8n.data.DataNodes.all,
            com.mob8n.logic.LogicNodes.all,
            com.mob8n.actions.ActionNodes.all,
            com.mob8n.ai.AiNodes.all,
            com.mob8n.apps.AppNodes.all,
        ))
    }
    val engine: Engine by lazy { Engine(this, catalog) }
    /** MainActivity pushes launch/new intents here; ui.App() collects (deep links mob8n://run/{id}, mob8n://workflow/{id}, mob8n://chat/{id}). */
    val uiIntents = MutableStateFlow<Intent?>(null)
    /** Started (visible) activities. Device phase: process importance is FOREGROUND_SERVICE (125 <= VISIBLE) whenever the chat holds the host, so importance cannot tell "backgrounded" — ChatRunner reads this instead. */
    @Volatile var visibleActivities = 0

    override fun onCreate() {
        super.onCreate()
        com.mob8n.ai.OpenAiCompat.debugLog = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) { visibleActivities++ }
            override fun onActivityStopped(a: Activity) { visibleActivities-- }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        // DESIGN5 §3.6: System 1 triage on event-fired runs (no-op unless a trigger sets triageEngine), show_panel -> Panels deep link.
        // Set before engine.start() so no event fired during start-up skips its triage.
        engine.preFilter = { wf, n, items -> Triage.filter(this, wf, n, items, engine.catalog.spec(n.type)?.param(Triage.STATE)?.default.asTextOrNull()) }
        OperatorTools.openPanel = { slug -> uiIntents.value = Intent(Intent.ACTION_VIEW, Uri.parse("mob8n://panel/$slug")) }
        engine.start()
        // DESIGN4 §2 integrator wiring: usage funnel -> Room, notification Approve/Deny -> the chat runner, preset skills once.
        Usage.sink = { u -> engine.scope.launch { runCatching { engine.recordAiUsage(u) } } }
        engine.chatDecision = { id, ok -> ChatRunner.decide(this, id, ok) }
        engine.scope.launch {
            engine.awaitReady()
            runCatching { engine.seedSkills(SkillPresets.ALL) }.onFailure { Log.w("Mob8N", "seed skills: ${it.message}") }
            // D11: existing installs get ONLY the 4th preset (uav-isr-operator), once; a later deletion sticks.
            val prefs = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_SKILLS_SEEDED_V2, false)) runCatching {
                engine.restoreSkills(listOf(SkillPresets.ALL[3]))
                prefs.edit().putBoolean(KEY_SKILLS_SEEDED_V2, true).apply()
            }.onFailure { Log.w("Mob8N", "seed skills v2: ${it.message}") }
        }
    }

    /** V20: the hidden JS WebView is the one large optional allocation; drop it when the app goes to the background. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND) JsRuntime.release()
    }

    companion object {
        const val KEY_SKILLS_SEEDED_V2 = "skills_seeded_v2"
        fun of(ctx: Context): Mob8NApp = ctx.applicationContext as Mob8NApp
    }
}
