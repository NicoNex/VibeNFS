package dev.vibenfs

import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import dev.vibenfs.ui.FolderEditScreen
import dev.vibenfs.ui.FolderListScreen
import dev.vibenfs.ui.theme.VibeTheme
import kotlinx.serialization.Serializable

@Serializable private data object Folders : NavKey
@Serializable private data class Edit(val id: String?) : NavKey

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Keep the system splash up until volumes are loaded, instead of showing a blank first frame.
        val content = findViewById<View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw() = (mounts.volumes.value != null).also {
                if (it) content.viewTreeObserver.removeOnPreDrawListener(this)
            }
        })
        setContent {
            VibeTheme {
                val backStack = rememberNavBackStack(Folders)
                // Guarded so a double tap (Save then Back mid-transition) never pops the root.
                val back: () -> Unit = { if (backStack.size > 1) backStack.removeLastOrNull() }
                NavDisplay(
                    backStack = backStack,
                    onBack = back,
                    // Forward: the new screen slides over the old, which drifts a little behind it.
                    transitionSpec = { slideInHorizontally { it } togetherWith slideOutHorizontally { -it / 4 } },
                    popTransitionSpec = { popTransform(predictive = false) },
                    predictivePopTransitionSpec = { popTransform(predictive = true) },
                    entryProvider = entryProvider {
                        entry<Folders> { ScreenFrame { FolderListScreen(onAdd = { backStack.add(Edit(null)) }, onEdit = { backStack.add(Edit(it)) }) } }
                        entry<Edit> { ScreenFrame { FolderEditScreen(it.id, onBack = back) } }
                    },
                )
            }
        }
    }
}

/**
 * Back, by button or gesture: the current screen leaves on top of the previous one. During the
 * predictive gesture it follows the finger like the system's cross-app animation: it shrinks and
 * drifts toward the edge over the still previous screen, then fades away once released.
 */
private const val POP_MS = 300

/** Rounds and lifts a screen as it leaves, so the shrinking back preview reads as a card. */
@Composable
private fun ScreenFrame(content: @Composable () -> Unit) {
    val corner by LocalNavAnimatedContentScope.current.transition.animateDp(
        transitionSpec = { tween(POP_MS, easing = LinearEasing) }, label = "corner",
    ) { if (it == EnterExitState.PostExit) 48.dp else 0.dp }
    // Both screens share one background color: the shadow is what separates the card from the one below.
    val shape = RoundedCornerShape(corner)
    Box(Modifier.shadow(corner / 3, shape).clip(shape)) { content() }
}

private fun popTransform(predictive: Boolean): ContentTransform =
    (if (predictive) EnterTransition.None togetherWith
        scaleOut(tween(POP_MS, easing = LinearEasing), targetScale = 0.7f) +
        slideOutHorizontally(tween(POP_MS, easing = LinearEasing)) { it / 8 } +
        // Opaque while the finger is down, gone only in the last stretch after release.
        fadeOut(keyframes { durationMillis = POP_MS; 1f at POP_MS * 3 / 4 })
    else slideInHorizontally { -it / 4 } togetherWith slideOutHorizontally { it })
        .apply { targetContentZIndex = -1f }
