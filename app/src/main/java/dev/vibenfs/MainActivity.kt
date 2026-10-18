package dev.vibenfs

import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
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
                    entryProvider = entryProvider {
                        entry<Folders> { FolderListScreen(onAdd = { backStack.add(Edit(null)) }, onEdit = { backStack.add(Edit(it)) }) }
                        entry<Edit> { FolderEditScreen(it.id, onBack = back) }
                    },
                )
            }
        }
    }
}
