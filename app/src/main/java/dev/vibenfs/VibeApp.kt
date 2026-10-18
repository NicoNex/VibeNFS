package dev.vibenfs

import android.app.Application
import android.content.Context
import dev.vibenfs.mount.MountManager

// ponytail: manual DI, one singleton; switch to Hilt when the graph grows.
class VibeApp : Application() {
    val mounts by lazy { MountManager(this) }
}

val Context.mounts: MountManager get() = (applicationContext as VibeApp).mounts
