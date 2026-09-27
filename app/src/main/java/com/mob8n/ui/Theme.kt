package com.mob8n.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mob8n.R
import com.mob8n.ai.Risk
import com.mob8n.core.NodeKind
import com.mob8n.core.NodeStatus
import com.mob8n.core.RunStatus
import com.mob8n.core.SETTINGS_PREFS
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.pow

// ---------------------------------------------------------------------------------------------------------------------------------
// DESIGN6 §2 tokens. The schemes, MahoutColors and ThemeContrastTest all read these maps, so the UI and the test can never disagree.
// ---------------------------------------------------------------------------------------------------------------------------------

/** M3 role name -> 0xAARRGGBB (DESIGN6 §2.1, light "paper"). */
internal val LIGHT_ROLES: Map<String, Long> = linkedMapOf(
    "primary" to 0xFF0B6B61, "onPrimary" to 0xFFFFFFFF, "primaryContainer" to 0xFFC4EEE6, "onPrimaryContainer" to 0xFF00201C,
    "secondary" to 0xFF7A5900, "onSecondary" to 0xFFFFFFFF, "secondaryContainer" to 0xFFF6E7C8, "onSecondaryContainer" to 0xFF2B1F00,
    "tertiary" to 0xFF1F4E66, "onTertiary" to 0xFFFFFFFF, "tertiaryContainer" to 0xFFD2E6F2, "onTertiaryContainer" to 0xFF0A2433,
    "error" to 0xFFB3261E, "onError" to 0xFFFFFFFF, "errorContainer" to 0xFFFADAD6, "onErrorContainer" to 0xFF410E0B,
    "background" to 0xFFF7F4EC, "onBackground" to 0xFF0F2A3F, "surface" to 0xFFF7F4EC, "onSurface" to 0xFF0F2A3F,
    "surfaceVariant" to 0xFFE3E1D8, "onSurfaceVariant" to 0xFF46535D,
    "surfaceContainerLowest" to 0xFFFFFFFF, "surfaceContainerLow" to 0xFFF2EFE6, "surfaceContainer" to 0xFFECE9DF,
    "surfaceContainerHigh" to 0xFFE6E3D9, "surfaceContainerHighest" to 0xFFE0DDD3,
    "outline" to 0xFF6E7B85, "outlineVariant" to 0xFFC8C9C0,
    "inverseSurface" to 0xFF132A3A, "inverseOnSurface" to 0xFFEDF1F3, "inversePrimary" to 0xFF7FD1C5,
    "scrim" to 0xFF000000, "surfaceTint" to 0xFF0B6B61, "surfaceBright" to 0xFFFBF9F3, "surfaceDim" to 0xFFDAD7CD,
)

/** DESIGN6 §2.1, dark "night navy". */
internal val DARK_ROLES: Map<String, Long> = linkedMapOf(
    "primary" to 0xFF7FD1C5, "onPrimary" to 0xFF00332D, "primaryContainer" to 0xFF0F4F48, "onPrimaryContainer" to 0xFFA9F0E4,
    "secondary" to 0xFFE9C46A, "onSecondary" to 0xFF3B2A00, "secondaryContainer" to 0xFF4D3B10, "onSecondaryContainer" to 0xFFF6E7C8,
    "tertiary" to 0xFFA9CBE0, "onTertiary" to 0xFF0B3345, "tertiaryContainer" to 0xFF1F4A60, "onTertiaryContainer" to 0xFFD2E6F2,
    "error" to 0xFFFFB4AB, "onError" to 0xFF690005, "errorContainer" to 0xFF8C1D18, "onErrorContainer" to 0xFFFFDAD6,
    "background" to 0xFF0A1722, "onBackground" to 0xFFE4ECF0, "surface" to 0xFF0A1722, "onSurface" to 0xFFE4ECF0,
    "surfaceVariant" to 0xFF1C3346, "onSurfaceVariant" to 0xFFA7B6C1,
    "surfaceContainerLowest" to 0xFF06111A, "surfaceContainerLow" to 0xFF0E1E2B, "surfaceContainer" to 0xFF122433,
    "surfaceContainerHigh" to 0xFF172B3C, "surfaceContainerHighest" to 0xFF1C3346,
    "outline" to 0xFF71879A, "outlineVariant" to 0xFF2A4356,
    "inverseSurface" to 0xFFE4ECF0, "inverseOnSurface" to 0xFF13232F, "inversePrimary" to 0xFF0B6B61,
    "scrim" to 0xFF000000, "surfaceTint" to 0xFF7FD1C5, "surfaceBright" to 0xFF22394C, "surfaceDim" to 0xFF0A1722,
)

/** Mahout extended colours (DESIGN6 §2.2), never dynamic. `cardLine` = the L1 card hairline (light only, §2.8). */
internal val LIGHT_EXT: Map<String, Long> = linkedMapOf(
    "success" to 0xFF1E7A4F, "onSuccess" to 0xFFFFFFFF, "successContainer" to 0xFFCDEFD9, "onSuccessContainer" to 0xFF04311C,
    "warning" to 0xFF8A5A00, "warningContainer" to 0xFFFBE3B3, "onWarningContainer" to 0xFF2E1C00,
    "info" to 0xFF2F5FA8, "infoContainer" to 0xFFD9E4F7, "onInfoContainer" to 0xFF0B2350,
    "userBubble" to 0xFF0F2A3F, "onUserBubble" to 0xFFF6E7C8, "assistantBubble" to 0xFFFFFFFF, "onAssistantBubble" to 0xFF0F2A3F,
    "card" to 0xFFFFFFFF, "cardLine" to 0x99C8C9C0, "codeBg" to 0xFF132A3A, "onCode" to 0xFFE4ECF0,
    "riskRead" to 0xFFE3E1D8, "onRiskRead" to 0xFF2E3B45, "riskWrite" to 0xFFD9E4F7, "onRiskWrite" to 0xFF0B2350,
    "riskCoding" to 0xFFFBE3B3, "onRiskCoding" to 0xFF4A3000, "riskAlways" to 0xFFFADAD6, "onRiskAlways" to 0xFF5F1410,
    "caret" to 0xFF0B6B61,
)

internal val DARK_EXT: Map<String, Long> = linkedMapOf(
    "success" to 0xFF7FD8A4, "onSuccess" to 0xFF00391F, "successContainer" to 0xFF12432C, "onSuccessContainer" to 0xFFCDEFD9,
    "warning" to 0xFFF2C063, "warningContainer" to 0xFF4D3700, "onWarningContainer" to 0xFFFBE3B3,
    "info" to 0xFFA8C4F2, "infoContainer" to 0xFF1B355C, "onInfoContainer" to 0xFFD9E4F7,
    "userBubble" to 0xFF1F4A55, "onUserBubble" to 0xFFF6E7C8, "assistantBubble" to 0xFF122433, "onAssistantBubble" to 0xFFE4ECF0,
    "card" to 0xFF122433, "cardLine" to 0x00000000, "codeBg" to 0xFF06111A, "onCode" to 0xFFD5E1E8,
    "riskRead" to 0xFF1C3346, "onRiskRead" to 0xFFC9D6DE, "riskWrite" to 0xFF1B355C, "onRiskWrite" to 0xFFD9E4F7,
    "riskCoding" to 0xFF4D3700, "onRiskCoding" to 0xFFFBE3B3, "riskAlways" to 0xFF5C1A16, "onRiskAlways" to 0xFFFFDAD6,
    "caret" to 0xFF7FD1C5,
)

/** Brand constants, both schemes (logo, onboarding, BrandMark; never text on arbitrary surfaces). */
internal val BRAND: Map<String, Long> = linkedMapOf(
    "brandNavy" to 0xFF0F2A3F, "brandNavy2" to 0xFF123C4A, "brandGoldLight" to 0xFFF6E7C8, "brandGold" to 0xFFE9C46A, "brandTeal" to 0xFF7FD1C5,
)

/** Fixed signal colours (DESIGN6 §2.3), relative luminance ~0.19: >= 3:1 on light/dark surface and surfaceContainerHigh. Never text. */
internal val SIGNAL: Map<String, Long> = linkedMapOf(
    "running" to 0xFF3B7AC8, "success" to 0xFF298957, "failed" to 0xFFD14B44, "suspended" to 0xFFA26F0F, "cancelled" to 0xFF6D7B85,
    "errorRouted" to 0xFFB86225, "trigger" to 0xFFB86225, "data" to 0xFF3B7AC8, "logic" to 0xFF8666CF, "action" to 0xFF298957, "ai" to 0xFFC4507F,
)

data class ContrastPair(val label: String, val fg: Long, val bg: Long, val min: Double)

/** Every text / non-text pairing the UI draws (DESIGN6 §2.1–2.3 + toneColors + snackbar action), light and dark, built FROM the maps. */
internal val CONTRAST_PAIRS: List<ContrastPair> = buildList {
    for ((scheme, r, e) in listOf(Triple("light", LIGHT_ROLES, LIGHT_EXT), Triple("dark", DARK_ROLES, DARK_EXT))) {
        fun text(fg: String, bg: String) = add(ContrastPair("$scheme $fg on $bg", r[fg] ?: e.getValue(fg), r[bg] ?: e.getValue(bg), 4.5))
        fun nonText(fg: String, bg: String) = add(ContrastPair("$scheme $fg on $bg (non-text)", r[fg] ?: e.getValue(fg), r[bg] ?: e.getValue(bg), 3.0))
        for (c in listOf("primary", "secondary", "tertiary", "error")) {
            val on = "on" + c.replaceFirstChar { it.uppercase() }
            text(on, c); text("${on}Container", "${c}Container")
        }
        for (c in listOf("primary", "secondary", "error")) text(c, "surface")
        text("onBackground", "background"); text("onSurfaceVariant", "surfaceVariant")
        for (bg in listOf("surface", "surfaceContainerLowest", "surfaceContainerLow", "surfaceContainer", "surfaceContainerHigh", "surfaceContainerHighest", "card")) {
            text("onSurface", bg); text("onSurfaceVariant", bg)
        }
        text("inverseOnSurface", "inverseSurface"); text("inversePrimary", "inverseSurface")
        nonText("outline", "surface"); nonText("outline", "surfaceContainerHigh")
        text("onSuccess", "success"); text("onSuccessContainer", "successContainer"); text("onWarningContainer", "warningContainer")
        text("onInfoContainer", "infoContainer"); text("onUserBubble", "userBubble"); text("onAssistantBubble", "assistantBubble")
        text("onCode", "codeBg")
        for (k in listOf("Read", "Write", "Coding", "Always")) text("onRisk$k", "risk$k")
        for (k in listOf("warning", "info", "success")) text(k, "surface")
        nonText("caret", "assistantBubble")
    }
    for ((name, v) in SIGNAL) for ((label, bg) in listOf(
        "light surface" to LIGHT_ROLES.getValue("surface"), "light surfaceContainerHigh" to LIGHT_ROLES.getValue("surfaceContainerHigh"),
        "dark surface" to DARK_ROLES.getValue("surface"), "dark surfaceContainerHigh" to DARK_ROLES.getValue("surfaceContainerHigh"),
    )) add(ContrastPair("signal $name on $label", v, bg, 3.0))
}

/** WCAG 2.x contrast ratio of two opaque 0xAARRGGBB colours (alpha ignored). */
fun contrastRatio(fg: Long, bg: Long): Double {
    fun lum(c: Long): Double {
        fun ch(shift: Int): Double {
            val v = ((c shr shift) and 0xFF) / 255.0
            return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)
    }
    val a = lum(fg); val b = lum(bg)
    return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
}

private fun Map<String, Long>.c(k: String) = Color(getValue(k))

private fun schemeOf(r: Map<String, Long>): ColorScheme = lightColorScheme(
    primary = r.c("primary"), onPrimary = r.c("onPrimary"), primaryContainer = r.c("primaryContainer"), onPrimaryContainer = r.c("onPrimaryContainer"),
    inversePrimary = r.c("inversePrimary"),
    secondary = r.c("secondary"), onSecondary = r.c("onSecondary"), secondaryContainer = r.c("secondaryContainer"), onSecondaryContainer = r.c("onSecondaryContainer"),
    tertiary = r.c("tertiary"), onTertiary = r.c("onTertiary"), tertiaryContainer = r.c("tertiaryContainer"), onTertiaryContainer = r.c("onTertiaryContainer"),
    background = r.c("background"), onBackground = r.c("onBackground"), surface = r.c("surface"), onSurface = r.c("onSurface"),
    surfaceVariant = r.c("surfaceVariant"), onSurfaceVariant = r.c("onSurfaceVariant"), surfaceTint = r.c("surfaceTint"),
    inverseSurface = r.c("inverseSurface"), inverseOnSurface = r.c("inverseOnSurface"),
    error = r.c("error"), onError = r.c("onError"), errorContainer = r.c("errorContainer"), onErrorContainer = r.c("onErrorContainer"),
    outline = r.c("outline"), outlineVariant = r.c("outlineVariant"), scrim = r.c("scrim"),
    surfaceBright = r.c("surfaceBright"), surfaceContainer = r.c("surfaceContainer"), surfaceContainerHigh = r.c("surfaceContainerHigh"),
    surfaceContainerHighest = r.c("surfaceContainerHighest"), surfaceContainerLow = r.c("surfaceContainerLow"),
    surfaceContainerLowest = r.c("surfaceContainerLowest"), surfaceDim = r.c("surfaceDim"),
)

internal val LightScheme = schemeOf(LIGHT_ROLES)
internal val DarkScheme = schemeOf(DARK_ROLES)

@Immutable
data class MahoutColors(
    val success: Color, val onSuccess: Color, val successContainer: Color, val onSuccessContainer: Color,
    val warning: Color, val warningContainer: Color, val onWarningContainer: Color,
    val info: Color, val infoContainer: Color, val onInfoContainer: Color,
    val userBubble: Color, val onUserBubble: Color, val assistantBubble: Color, val onAssistantBubble: Color,
    val card: Color, val cardLine: Color, val codeBg: Color, val onCode: Color,
    val riskRead: Color, val onRiskRead: Color, val riskWrite: Color, val onRiskWrite: Color,
    val riskCoding: Color, val onRiskCoding: Color, val riskAlways: Color, val onRiskAlways: Color,
    val caret: Color,
    val brandNavy: Color, val brandNavy2: Color, val brandGoldLight: Color, val brandGold: Color, val brandTeal: Color,
)

private fun mahoutOf(e: Map<String, Long>): MahoutColors {
    val m = e + BRAND
    return MahoutColors(
        m.c("success"), m.c("onSuccess"), m.c("successContainer"), m.c("onSuccessContainer"),
        m.c("warning"), m.c("warningContainer"), m.c("onWarningContainer"),
        m.c("info"), m.c("infoContainer"), m.c("onInfoContainer"),
        m.c("userBubble"), m.c("onUserBubble"), m.c("assistantBubble"), m.c("onAssistantBubble"),
        m.c("card"), m.c("cardLine"), m.c("codeBg"), m.c("onCode"),
        m.c("riskRead"), m.c("onRiskRead"), m.c("riskWrite"), m.c("onRiskWrite"),
        m.c("riskCoding"), m.c("onRiskCoding"), m.c("riskAlways"), m.c("onRiskAlways"),
        m.c("caret"),
        m.c("brandNavy"), m.c("brandNavy2"), m.c("brandGoldLight"), m.c("brandGold"), m.c("brandTeal"),
    )
}

internal val LightMahout = mahoutOf(LIGHT_EXT)
internal val DarkMahout = mahoutOf(DARK_EXT)

val LocalMahoutColors: ProvidableCompositionLocal<MahoutColors> = staticCompositionLocalOf { LightMahout }

val MaterialTheme.mahout: MahoutColors
    @Composable @ReadOnlyComposable get() = LocalMahoutColors.current

/** (container, onContainer) per risk (DESIGN6 D16). The words carry the meaning; colour only underlines it. */
fun riskColors(risk: Risk, c: MahoutColors): Pair<Color, Color> = when (risk) {
    Risk.READ -> c.riskRead to c.onRiskRead
    Risk.WRITE -> c.riskWrite to c.onRiskWrite
    Risk.CODING -> c.riskCoding to c.onRiskCoding
    Risk.ALWAYS -> c.riskAlways to c.onRiskAlways
}

/** (container, onContainer) per tone: pills, tinted cards. Every pair is in CONTRAST_PAIRS. */
fun toneColors(tone: Tone, cs: ColorScheme, c: MahoutColors): Pair<Color, Color> = when (tone) {
    Tone.Primary -> cs.primaryContainer to cs.onPrimaryContainer
    Tone.Neutral -> cs.surfaceContainerHighest to cs.onSurface
    Tone.Positive -> c.successContainer to c.onSuccessContainer
    Tone.Caution -> c.warningContainer to c.onWarningContainer
    Tone.Danger -> cs.errorContainer to cs.onErrorContainer
    Tone.Info -> c.infoContainer to c.onInfoContainer
}

// ---------------------------------------------------------------------------------------------------------------------------------
// Typography (DESIGN6 §2.5). ponytail: weights via FontVariation on one variable file; upgrade = static TTF per weight if a device ignores it.
// ---------------------------------------------------------------------------------------------------------------------------------

@OptIn(ExperimentalTextApi::class)
val BrandFamily: FontFamily = FontFamily(listOf(400, 500, 600, 700).map { w ->
    Font(R.font.manrope_variable, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
})

@OptIn(ExperimentalTextApi::class)
val MonoFamily: FontFamily = FontFamily(listOf(400, 600).map { w ->
    Font(R.font.jetbrains_mono_variable, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
})

private fun brand(w: Int, size: Int, line: Int, track: Double) =
    TextStyle(fontFamily = BrandFamily, fontWeight = FontWeight(w), fontSize = size.sp, lineHeight = line.sp, letterSpacing = track.sp)

val CodeStyle: TextStyle = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 20.sp, letterSpacing = 0.sp)
val CodeSmallStyle: TextStyle = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.sp)
/** headlineMedium with tabular figures: counters and countdowns never jitter in width. */
val NumericStyle: TextStyle = brand(700, 28, 36, -0.25).copy(fontFeatureSettings = "tnum")

private val MahoutTypography: Typography = Typography().let { d ->
    Typography(
        displayLarge = d.displayLarge.copy(fontFamily = BrandFamily),
        displayMedium = d.displayMedium.copy(fontFamily = BrandFamily),
        displaySmall = brand(600, 36, 44, -0.5),
        headlineLarge = d.headlineLarge.copy(fontFamily = BrandFamily, fontWeight = FontWeight(700)),
        headlineMedium = brand(700, 28, 36, -0.25),
        headlineSmall = brand(700, 24, 32, 0.0),
        titleLarge = brand(700, 22, 28, 0.0),
        titleMedium = brand(600, 16, 24, 0.1),
        titleSmall = brand(600, 14, 20, 0.1),
        bodyLarge = brand(400, 16, 24, 0.15),
        bodyMedium = brand(400, 14, 20, 0.15),
        bodySmall = brand(500, 12, 16, 0.25),
        labelLarge = brand(600, 14, 20, 0.1),
        labelMedium = brand(600, 12, 16, 0.4),
        labelSmall = brand(600, 11, 16, 0.5),
    )
}

// ---------------------------------------------------------------------------------------------------------------------------------
// Shapes and spacing (DESIGN6 §2.6, §2.7)
// ---------------------------------------------------------------------------------------------------------------------------------

object Radius { val xs: Dp = 6.dp; val s: Dp = 10.dp; val m: Dp = 16.dp; val l: Dp = 24.dp; val xl: Dp = 32.dp }

object Space {
    val xxs: Dp = 2.dp; val xs: Dp = 4.dp; val s: Dp = 8.dp; val m: Dp = 12.dp
    val l: Dp = 16.dp; val xl: Dp = 24.dp; val xxl: Dp = 32.dp; val xxxl: Dp = 48.dp
}

private val MahoutShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.xs), small = RoundedCornerShape(Radius.s), medium = RoundedCornerShape(Radius.m),
    large = RoundedCornerShape(Radius.l), extraLarge = RoundedCornerShape(Radius.xl),
)

/** 6 dp corner points at the sender (bottomEnd; mirrored in RTL). */
val BubbleUserShape: Shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 6.dp, bottomStart = 20.dp)

/** Page gutter: 16 dp below 600 dp wide, 24 dp at 600 dp and up. */
@Composable
fun pageGutter(): Dp = if (LocalConfiguration.current.screenWidthDp >= 600) 24.dp else 16.dp

// ---------------------------------------------------------------------------------------------------------------------------------
// Appearance prefs + theme
// ---------------------------------------------------------------------------------------------------------------------------------

/** SharedPreferences(SETTINGS_PREFS): "ui_dynamic_color" (default false), "ui_reduce_motion" (default false). */
object UiPrefs {
    private const val DYNAMIC = "ui_dynamic_color"
    private const val REDUCE = "ui_reduce_motion"
    private val _dynamic = MutableStateFlow(false)
    private val _reduce = MutableStateFlow(false)
    val dynamicColor: StateFlow<Boolean> = _dynamic
    val reduceMotion: StateFlow<Boolean> = _reduce

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        val p = prefs(ctx)
        _dynamic.value = p.getBoolean(DYNAMIC, false)
        _reduce.value = p.getBoolean(REDUCE, false)
    }
    fun setDynamicColor(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean(DYNAMIC, on).apply(); _dynamic.value = on }
    fun setReduceMotion(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean(REDUCE, on).apply(); _reduce.value = on }
}

private tailrec fun Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

// ponytail: follows the system light/dark only (D20); upgrade = UiPrefs.theme
@Composable
fun Mob8NTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    remember { UiPrefs.load(ctx) }                     // synchronous: the first frame already has the right scheme and motion
    val dynamic by UiPrefs.dynamicColor.collectAsStateWithLifecycle()
    val reduce by UiPrefs.reduceMotion.collectAsStateWithLifecycle()
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkScheme
        else -> LightScheme
    }
    val reduced = motionReduced(rememberSystemAnimatorScale(), reduce)
    val motion = remember(reduced) { MotionScheme(reduced) }
    // M6: bar icon colour follows the theme actually drawn (dark icons on the cream paper), not whatever enableEdgeToEdge inferred at its last call.
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        view.context.findActivity()?.window?.let { w ->
            WindowCompat.getInsetsController(w, w.decorView).run { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
        }
    }
    CompositionLocalProvider(
        LocalMahoutColors provides if (dark) DarkMahout else LightMahout,   // safety colours never follow the wallpaper (D2)
        LocalMotion provides motion,
    ) {
        MaterialTheme(colorScheme = scheme, typography = MahoutTypography, shapes = MahoutShapes, content = content)
    }
}

private fun sig(k: String) = Color(SIGNAL.getValue(k))

/** Fixed per-kind signal colours (stripe, palette sections), DESIGN6 §2.3: >= 3:1 on light and dark surfaces. */
fun kindColor(kind: NodeKind): Color = when (kind) {
    NodeKind.TRIGGER -> sig("trigger")
    NodeKind.DATA -> sig("data")
    NodeKind.LOGIC -> sig("logic")
    NodeKind.ACTION -> sig("action")
    NodeKind.AI -> sig("ai")
}

fun kindIcon(kind: NodeKind): ImageVector = when (kind) {
    NodeKind.TRIGGER -> Icons.Default.Bolt
    NodeKind.DATA -> Icons.Default.Storage
    NodeKind.LOGIC -> Icons.AutoMirrored.Filled.CallSplit
    NodeKind.ACTION -> Icons.Default.TouchApp
    NodeKind.AI -> Icons.Default.Psychology
}

/** Icon for a node type; keyword match on the spec id, kind icon as fallback. */
fun nodeIcon(specId: String, kind: NodeKind): ImageVector = when {
    "notification" in specId -> Icons.Default.Notifications
    "playing" in specId || "media" in specId || "playlist" in specId -> Icons.Default.MusicNote
    "schedule" in specId || "cron" in specId || "interval" in specId || "time" in specId -> Icons.Default.Schedule
    "alarm" in specId -> Icons.Default.Alarm
    "http" in specId || "webhook" in specId || "cloud" in specId -> Icons.Default.Cloud
    "wifi" in specId || "network" in specId || "bluetooth" in specId -> Icons.Default.Wifi
    "sensor" in specId || "shake" in specId || "light" in specId || "step" in specId -> Icons.Default.Sensors
    "share" in specId -> Icons.Default.Share
    else -> kindIcon(kind)
}

fun statusColor(s: RunStatus?): Color = when (s) {
    RunStatus.RUNNING -> sig("running")
    RunStatus.SUCCESS -> sig("success")
    RunStatus.FAILED -> sig("failed")
    RunStatus.SUSPENDED -> sig("suspended")
    RunStatus.CANCELLED, null -> sig("cancelled")
}

fun nodeStatusColor(s: NodeStatus?): Color = when (s) {
    NodeStatus.SUCCESS -> sig("success")
    NodeStatus.FAILED, NodeStatus.TIMEOUT -> sig("failed")
    NodeStatus.ERROR_ROUTED -> sig("errorRouted")
    NodeStatus.SUSPENDED -> sig("suspended")
    NodeStatus.SKIPPED, null -> sig("cancelled")
}

private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm:ss")
fun fmtTime(ms: Long?): String = if (ms == null || ms <= 0) "—" else TIME_FMT.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
fun fmtDuration(ms: Long?): String = when {
    ms == null -> "—"
    ms < 1000 -> "$ms ms"
    ms < 60_000 -> "%.1f s".format(ms / 1000.0)
    else -> "${ms / 60_000} min ${(ms % 60_000) / 1000} s"
}

/** Increments every time the activity resumes: recompute Gate.granted() after the user returns from Settings. */
@Composable
fun rememberResumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    return tick
}
