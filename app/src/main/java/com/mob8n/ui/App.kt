package com.mob8n.ui

import android.content.Context
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mob8n.Mob8NApp
import com.mob8n.core.SETTINGS_PREFS

/** Top-level destinations (DESIGN4 §10): phone bottom bar (5) / tablet rail (5 + Settings). Workflows stays the landing screen. */
private val DESTINATIONS: List<Triple<Screen, String, ImageVector>> = listOf(
    Triple(Screen.Dashboard, "Dashboard", Icons.Rounded.Dashboard),
    Triple(Screen.List, "Workflows", Icons.Rounded.AccountTree),
    Triple(Screen.Chat(), "Chat", Icons.AutoMirrored.Rounded.Chat),
    Triple(Screen.Knowledge, "Knowledge", Icons.AutoMirrored.Rounded.MenuBook),
    Triple(Screen.Skills, "Skills", Icons.Rounded.School),
)

/** Root (DESIGN §9.1): theme, state navigation, deep links, adaptive two-pane at >= 840 dp; v6: animated destinations + predictive back. */
// ponytail: no ViewModel layer; upgrade = per-screen ViewModel when state outgrows composables
@Composable
fun App() {
    val ctx = LocalContext.current
    val app = Mob8NApp.of(ctx)
    val engine = app.engine
    val catalog = app.catalog
    var screen by rememberScreen()
    // DESIGN3P §6.8: first launch shows the permissions center full-screen (title "Set up Mahout", Skip) until settings.onboarded is set.
    val prefs = remember { ctx.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE) }
    var onboarded by rememberSaveable { mutableStateOf(prefs.getBoolean("onboarded", false)) }
    var introDone by rememberSaveable { mutableStateOf(false) }   // DESIGN6 §6.4: the intro precedes onboarding only
    val finishOnboarding = { prefs.edit().putBoolean("onboarded", true).apply(); onboarded = true }

    LaunchedEffect(Unit) {
        app.uiIntents.collect { intent ->
            val uri = intent?.data ?: return@collect
            if (uri.scheme != "mob8n") return@collect
            val id = uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: return@collect
            when (uri.host) {
                "run" -> screen = Screen.RunDetail(id)
                "workflow" -> screen = Screen.Editor(id)
                "chat" -> screen = Screen.Chat(id)   // DESIGN4 §5.7: approval notification body
                "panel" -> screen = Screen.Panels(id)   // DESIGN5 §8.3: show_panel operator tool (id = slug)
            }
            app.uiIntents.value = null // consumed
        }
    }

    Mob8NTheme {
        val motion = LocalMotion.current
        // DESIGN6 §6.2 / D14: progress-driven transform of the current screen; commit runs the normal pop transition.
        // Screen-level BackHandlers (editor guard, sheets, drawer) register later and win, unchanged.
        // ponytail: no parent peek; upgrade = SeekableTransitionState (present in animation-core 1.7.5) + rememberTransition
        var backProgress by remember { mutableFloatStateOf(0f) }
        var backEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
        PredictiveBackHandler(enabled = onboarded && screen != Screen.List) { events ->
            try { events.collect { backProgress = it.progress; backEdge = it.swipeEdge }; screen = screen.parent }
            finally { backProgress = 0f }   // cancel -> spring back
        }
        val backP by animateFloatAsState(if (motion.reduced) 0f else backProgress, motion.spatialFast(), label = "back")
        val backLayer = Modifier.graphicsLayer {
            val p = backP   // read in the layer: no recomposition per gesture frame
            if (p > 0f) {
                val e = MotionTokens.Decelerate.transform(p)
                scaleX = 1f - 0.08f * e; scaleY = scaleX
                translationX = (if (backEdge == BackEventCompat.EDGE_LEFT) 1 else -1) * 16.dp.toPx() * e
                shape = RoundedCornerShape(28.dp * e); clip = true
            }
        }
        val saveable = rememberSaveableStateHolder()
        val convs by engine.conversations().collectAsStateWithLifecycle(emptyList())
        val awaiting = remember(convs) { convs.count { it.status == "awaiting" } }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Column(Modifier.fillMaxSize()) {
            val bypassUntil = rememberBypassUntil(engine)
            BypassBanner(bypassUntil)   // DESIGN4P §4.3: rendered ONCE here so every screen (both layouts, onboarding included) shows it; no screen renders its own
            // v5 carry-over: the banner already sits below the status bar, so the screen under it must not pad for the status bar again.
            Box(Modifier.weight(1f).fillMaxWidth().then(if (bypassUntil > 0) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier)) {
            if (!onboarded) AnimatedContent(introDone, transitionSpec = { fadeIn(motion.enterEffect()) togetherWith fadeOut(motion.exitEffect()) }, label = "intro") { done ->
                if (!done) IntroScreen(onDone = { introDone = true })
                else PermissionsScreen(engine, catalog, onboarding = true, onBack = finishOnboarding)   // full-screen, before the shell
            }
            else BoxWithConstraints {
                val nav: (Screen) -> Unit = { screen = it }
                val navLabels = showNavLabels(LocalDensity.current.fontScale)
                if (maxWidth >= 840.dp) {
                    Row(Modifier.fillMaxSize()) {
                        NavigationRail(header = { BrandMark(size = 40.dp) }) {
                            for ((dest, label, icon) in DESTINATIONS + Triple(Screen.AiSettings, "Settings", Icons.Rounded.Settings))
                                NavigationRailItem(selected = if (dest == Screen.AiSettings) screen == Screen.AiSettings || screen == Screen.McpSettings else screen.section == dest && screen != Screen.AiSettings && screen != Screen.McpSettings,
                                    onClick = { screen = dest }, icon = { NavIcon(icon, if (dest is Screen.Chat) awaiting else 0) }, label = { Text(label) },
                                    modifier = navItemSemantics(label, if (dest is Screen.Chat) awaiting else 0))
                        }
                        VerticalDivider()
                        // Top-level switches fade through; inside a section only the detail pane animates (list pane stays put).
                        AnimatedContent(sectionKey(screen), Modifier.weight(1f).fillMaxHeight().then(backLayer), label = "section",
                            transitionSpec = { navTransform(if (initialState == targetState) NavKind.NONE else NavKind.FADE_THROUGH, motion, false) }) { key ->
                            // The leaving section keeps drawing its own last screen, never the new one.
                            val live = screen.takeIf { sectionKey(it) == key }
                            var last by remember { mutableStateOf(live ?: screen) }
                            SideEffect { if (live != null) last = live }
                            val s = live ?: last
                            when {
                                s is Screen.Chat -> Row(Modifier.fillMaxSize()) {
                                    ChatListPane(engine, selectedId = s.conversationId, onOpen = nav, modifier = Modifier.width(360.dp).fillMaxHeight())
                                    VerticalDivider()
                                    AnimatedScreens(s, saveable, Modifier.weight(1f).fillMaxHeight()) { d ->
                                        val id = (d as? Screen.Chat)?.conversationId
                                        if (id == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Select a chat", style = MaterialTheme.typography.titleMedium) }
                                        else ChatScreen(id, engine, catalog, onBack = { screen = Screen.Chat() }, onOpen = nav, twoPane = true)
                                    }
                                }
                                s.section == Screen.List -> Row(Modifier.fillMaxSize()) {
                                    WorkflowListScreen(engine, catalog, selectedId = (s as? Screen.Editor)?.workflowId ?: (s as? Screen.Runs)?.workflowId ?: (s as? Screen.Build)?.workflowId, onOpen = nav, modifier = Modifier.width(360.dp).fillMaxHeight())
                                    VerticalDivider()
                                    AnimatedScreens(s, saveable, Modifier.weight(1f).fillMaxHeight()) { d ->
                                        if (d == Screen.List) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Select a workflow", style = MaterialTheme.typography.titleMedium) }
                                        else ScreenContent(d, nav)
                                    }
                                }
                                else -> AnimatedScreens(s, saveable, Modifier.fillMaxSize()) { d -> ScreenContent(d, nav) }   // Dashboard, Knowledge, Skills full-width
                            }
                        }
                    }
                } else Scaffold(
                    // DESIGN6 §6.3: the shell pads only for its own bar and consumes that; each screen owns its status/nav-bar insets.
                    contentWindowInsets = WindowInsets(0),
                    bottomBar = {
                        BarVisibility(screen.topLevel) {
                            NavigationBar {
                                for ((dest, label, icon) in DESTINATIONS)
                                    // 200 % font scale would clip the 80 dp bar: labels go, the icon then carries the name (DESIGN6 §6.1).
                                    NavigationBarItem(selected = screen.section == dest, onClick = { screen = dest },
                                        icon = { NavIcon(icon, if (dest is Screen.Chat) awaiting else 0, if (navLabels) null else label) },
                                        label = if (navLabels) ({ Text(label, maxLines = 1) }) else null,
                                        modifier = navItemSemantics(label, if (dest is Screen.Chat) awaiting else 0))
                            }
                        }
                    },
                ) { pad ->
                    AnimatedScreens(screen, saveable, Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).then(backLayer)) { s ->
                        if (s == Screen.List) WorkflowListScreen(engine, catalog, selectedId = null, onOpen = nav)
                        else ScreenContent(s, nav)
                    }
                }
            }
            }
        } }
    }
}

/** Tablet: one AnimatedContent slot per rail section (Settings screens live in the Workflows section, as in v4). */
private fun sectionKey(s: Screen): String = s.section.contentKey

/** Depth-aware destination swap (DESIGN6 §6.1) + per-screen saveable state so Back restores scroll and fields. */
@Composable
private fun AnimatedScreens(screen: Screen, saveable: SaveableStateHolder, modifier: Modifier, content: @Composable (Screen) -> Unit) {
    val motion = LocalMotion.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    AnimatedContent(screen, modifier, transitionSpec = { navTransform(navKind(initialState, targetState), motion, rtl) }, contentKey = { it.contentKey }, label = "nav") { s ->
        saveable.SaveableStateProvider(s.contentKey) { content(s) }
    }
}

/** FADE_THROUGH for top-level switches, container-like PUSH (slide 10 % + fade + scale 0.96, D15) and its mirror POP; instant when reduced. */
internal fun navTransform(kind: NavKind, motion: MotionScheme, rtl: Boolean): ContentTransform {
    if (motion.reduced || kind == NavKind.NONE) return EnterTransition.None togetherWith ExitTransition.None
    val d = if (rtl) -1 else 1
    return when (kind) {
        NavKind.PUSH -> ContentTransform(
            slideInHorizontally(motion.spatial()) { d * it / 10 } + fadeIn(motion.effect()) + scaleIn(motion.spatial(), initialScale = 0.96f),
            slideOutHorizontally(motion.spatial()) { -d * it / 20 } + fadeOut(motion.exitEffect()),
            sizeTransform = SizeTransform(clip = false),
        )
        NavKind.POP -> ContentTransform(
            slideInHorizontally(motion.spatial()) { -d * it / 20 } + fadeIn(motion.effect()),
            slideOutHorizontally(motion.spatial()) { d * it / 10 } + fadeOut(motion.exitEffect()) + scaleOut(motion.spatial(), targetScale = 0.96f),
            targetContentZIndex = -1f,   // the leaving screen stays on top while it slides away
            sizeTransform = SizeTransform(clip = false),
        )
        else -> ContentTransform(
            fadeIn(motion.enterEffect(MotionTokens.MEDIUM1, delayMs = MotionTokens.SHORT1)) + scaleIn(motion.enterEffect(MotionTokens.MEDIUM1, delayMs = MotionTokens.SHORT1), initialScale = 0.96f),
            fadeOut(motion.exitEffect(MotionTokens.SHORT1)),
            sizeTransform = SizeTransform(clip = false),
        )
    }
}

/** The phone bar collapses away on depth screens (outside any ColumnScope, so the plain AnimatedVisibility resolves). */
@Composable
private fun BarVisibility(visible: Boolean, content: @Composable () -> Unit) {
    val motion = LocalMotion.current
    AnimatedVisibility(visible, enter = motion.expand(), exit = motion.collapse()) { content() }
}

@Composable
private fun NavIcon(icon: ImageVector, badge: Int, contentDescription: String? = null) {
    val motion = LocalMotion.current
    BadgedBox(badge = { AnimatedVisibility(badge > 0, enter = motion.enter(), exit = ExitTransition.None) { Badge { Text("$badge") } } }) {
        Icon(icon, contentDescription = contentDescription)
    }
}

/** The Chat badge is a number: say what it counts (never number-only). */
private fun navItemSemantics(label: String, badge: Int): Modifier =
    if (badge > 0) Modifier.semantics { contentDescription = "$label, $badge awaiting approval" } else Modifier

@Composable
private fun ScreenContent(screen: Screen, nav: (Screen) -> Unit) {
    val app = Mob8NApp.of(LocalContext.current)
    val engine = app.engine
    val catalog = app.catalog
    val back = { nav(screen.parent) }
    when (screen) {
        Screen.List -> {}
        is Screen.Editor -> EditorScreen(screen.workflowId, screen.focusNodeId, engine, catalog, onBack = back, onOpen = nav)
        is Screen.Runs -> RunsScreen(screen.workflowId, engine, onBack = back, onOpen = nav)
        is Screen.RunDetail -> RunDetailScreen(screen.runId, engine, onBack = back, onOpen = nav)
        Screen.Permissions -> PermissionsScreen(engine, catalog, onBack = back)
        Screen.AiSettings -> AiSettingsScreen(onBack = back, onOpen = nav)
        Screen.McpSettings -> McpSettingsScreen(onBack = back)
        Screen.Notes -> NotesScreen(engine, onBack = back, onOpen = nav)
        Screen.Playlist -> PlaylistScreen(engine, onBack = back)
        Screen.Knowledge -> KnowledgeScreen(engine, onBack = back)
        is Screen.Build -> BuildWithAiScreen(screen.workflowId, engine, catalog, onBack = back, onOpen = nav)
        // v4 (DESIGN4 §10)
        Screen.Dashboard -> DashboardScreen(engine, catalog, onOpen = nav)
        is Screen.Chat -> if (screen.conversationId == null) ChatListPane(engine, selectedId = null, onOpen = nav) else ChatScreen(screen.conversationId, engine, catalog, onBack = back, onOpen = nav)
        Screen.Skills -> SkillsScreen(engine, catalog, onBack = back, onOpen = nav)
        is Screen.Panels -> PanelsScreen(screen.slug, onBack = back)
    }
}
