package xin.dponnood.remoteservice.core.designsystem

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Product animation timings; keep transitions in the 150–300 ms range. */
object RemoteServicesMotion {
    const val FastMillis = 150
    const val StandardMillis = 220
    const val EmphasisMillis = 300

    fun durationMillis(reduceMotion: Boolean, normal: Int = StandardMillis): Int =
        if (reduceMotion) 1 else normal
}

/**
 * Reads Android's animator scale once per composition. A scale of zero is the
 * platform's reduce-motion signal; Compose animations then use a one-frame
 * transition while layout and status changes remain visible.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }
}
