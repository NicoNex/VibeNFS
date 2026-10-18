package dev.vibenfs.ui.theme

import android.content.res.AssetManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// Google Sans Flex is variable: wdth makes posters condensed, ROND softens UI text.
// Asset fonts, not res/font: on this Compose BOM, resource fonts ignored variationSettings (poster width
// stayed at 100 on an API 37 emulator), while asset fonts go through Typeface.Builder and keep them.
private fun flex(assets: AssetManager, weight: Int, width: Float = 100f, round: Float = 100f) = Font(
    "fonts/google_sans_flex.ttf",
    assets,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.width(width),
        FontVariation.Setting("ROND", round),
    ),
)

private fun mono(assets: AssetManager, weight: Int) =
    Font("fonts/google_sans_code.ttf", assets, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

/** Google Sans Code, the "data" voice: stats, hosts, paths. */
val LocalMonoFamily = staticCompositionLocalOf<FontFamily> { FontFamily.Monospace }

private fun editorialTypography(assets: AssetManager): Typography {
    // Condensed heavy cut for editorial headlines (volume names, hero numbers).
    val poster = FontFamily(flex(assets, 800, width = 25f, round = 0f), flex(assets, 900, width = 25f, round = 0f))
    val flex = FontFamily(flex(assets, 400), flex(assets, 500), flex(assets, 600), flex(assets, 700))
    val base = Typography()
    fun TextStyle.flex() = copy(fontFamily = flex)
    fun TextStyle.semi() = copy(fontFamily = flex, fontWeight = FontWeight.SemiBold)
    fun TextStyle.poster(size: Int, line: Int) =
        copy(fontFamily = poster, fontWeight = FontWeight(900), letterSpacing = (-0.02).em, fontSize = size.sp, lineHeight = line.sp)
    return Typography(
        displayLarge = base.displayLarge.poster(88, 84),
        displayMedium = base.displayMedium.poster(64, 62),
        displaySmall = base.displaySmall.poster(48, 48),
        headlineLarge = base.headlineLarge.semi(),
        headlineMedium = base.headlineMedium.semi(),
        headlineSmall = base.headlineSmall.semi(),
        titleLarge = base.titleLarge.semi(),
        titleMedium = base.titleMedium.flex(),
        titleSmall = base.titleSmall.flex(),
        bodyLarge = base.bodyLarge.flex(),
        bodyMedium = base.bodyMedium.flex(),
        bodySmall = base.bodySmall.flex(),
        labelLarge = base.labelLarge.flex(),
        labelMedium = base.labelMedium.flex(),
        labelSmall = base.labelSmall.flex(),
    )
}

// ponytail: minSdk 34 always has Material You, so colors come only from the system wallpaper.
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VibeTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = remember(dark, context) { if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context) }
    val assets = context.assets
    val typography = remember(assets) { editorialTypography(assets) }
    val mono = remember(assets) { FontFamily(mono(assets, 400), mono(assets, 500), mono(assets, 700)) }
    CompositionLocalProvider(LocalMonoFamily provides mono) {
        MaterialExpressiveTheme(
            colorScheme = colors,
            typography = typography,
            motionScheme = remember { MotionScheme.expressive() },
            content = content,
        )
    }
}
