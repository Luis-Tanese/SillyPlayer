package dev.sillyplayer.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import dev.sillyplayer.R

private val icons =
    mapOf(
        "skip-back" to R.drawable.ic_skip_back,
        "grip-vertical" to R.drawable.ic_grip_vertical,
        "folder" to R.drawable.ic_folder,
        "ellipsis" to R.drawable.ic_ellipsis,
        "external-link" to R.drawable.ic_external_link,
        "info" to R.drawable.ic_info,
        "repeat" to R.drawable.ic_repeat,
        "settings" to R.drawable.ic_settings,
        "list-checks" to R.drawable.ic_list_checks,
        "heart" to R.drawable.ic_heart,
        "heart-filled" to R.drawable.ic_heart_filled,
        "chevron-down" to R.drawable.ic_chevron_down,
        "chevron-right" to R.drawable.ic_chevron_right,
        "chevron-up" to R.drawable.ic_chevron_up,
        "pin" to R.drawable.ic_pin,
        "arrow-up" to R.drawable.ic_arrow_up,
        "arrow-down" to R.drawable.ic_arrow_down,
        "sun" to R.drawable.ic_sun,
        "play" to R.drawable.ic_play,
        "image" to R.drawable.ic_image,
        "list-music" to R.drawable.ic_list_music,
        "moon" to R.drawable.ic_moon,
        "maximize" to R.drawable.ic_maximize,
        "x" to R.drawable.ic_x,
        "plus" to R.drawable.ic_plus,
        "music-2" to R.drawable.ic_music_2,
        "repeat-1" to R.drawable.ic_repeat_1,
        "pencil" to R.drawable.ic_pencil,
        "pause" to R.drawable.ic_pause,
        "shuffle" to R.drawable.ic_shuffle,
        "volume-2" to R.drawable.ic_volume_2,
        "search" to R.drawable.ic_search,
        "download" to R.drawable.ic_download,
        "trash-2" to R.drawable.ic_trash_2,
        "rotate-ccw" to R.drawable.ic_rotate_ccw,
        "skip-forward" to R.drawable.ic_skip_forward,
        "clock" to R.drawable.ic_clock,
        "volume-x" to R.drawable.ic_volume_x,
        "minimize" to R.drawable.ic_minimize,
        "check" to R.drawable.ic_check,
        "arrow-left" to R.drawable.ic_arrow_left,
        "arrow-right" to R.drawable.ic_arrow_right,
    )

@Composable
fun Glyph(
    name: String,
    description: String? = null,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(painterResource(icons[name] ?: R.drawable.ic_music_2), description, modifier, tint = tint)
}
