package dev.sillyplayer.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import dev.sillyplayer.data.Preferences

// shared palette values with qml/Theme.qml
val Palettes = linkedMapOf(
    "Graphite" to listOf("181818", "141414", "303030", "ffffff", "b0b0b0", "339cff", "ffffff", "f6f6f6", "e8e8e8", "0d0d0d", "626262", "0285ff"),
    "Catppuccin" to listOf("1e1e2e", "181825", "313244", "cdd6f4", "a6adc8", "cba6f7", "eff1f5", "e6e9ef", "ccd0da", "4c4f69", "6c6f85", "8839ef"),
    "Tokyo Night" to listOf("1a1b26", "16161e", "292e42", "c0caf5", "9aa5ce", "7aa2f7", "e1e2e7", "d5d6db", "c4c8da", "343b58", "565a6e", "34548a"),
    "Nord" to listOf("2e3440", "272c36", "434c5e", "eceff4", "d8dee9", "88c0d0", "eceff4", "e5e9f0", "d8dee9", "2e3440", "4c566a", "397789"),
    "Dracula" to listOf("282a36", "21222c", "44475a", "f8f8f2", "b9b5cc", "bd93f9", "f5f2fa", "ebe6f3", "dcd4e8", "282a36", "615775", "7543a4"),
    "Gruvbox" to listOf("282828", "1d2021", "3c3836", "ebdbb2", "bdae93", "d8a657", "fbf1c7", "f2e5bc", "ebdbb2", "3c3836", "665c54", "9d5b16"),
    "One Dark" to listOf("282c34", "21252b", "3e4451", "abb2bf", "9da5b4", "61afef", "fafafa", "f0f0f0", "dedede", "383a42", "626772", "286caa"),
    "Solarized" to listOf("002b36", "00212b", "073642", "eee8d5", "93a1a1", "2aa198", "fdf6e3", "eee8d5", "ddd6c1", "586e75", "657b83", "167d77"),
    "1337" to listOf("000000", "101010", "303030", "ffffff", "b0b0b0", "ffe000", "ffffff", "f6f8fa", "e5e9ef", "24292e", "586069", "0366d6"),
    "ANSI" to listOf("202020", "181818", "404040", "e0e0e0", "a0a0a0", "55aaff", "ffffff", "f6f8fa", "e5e9ef", "24292e", "586069", "0366d6"),
    "Base16" to listOf("181818", "101010", "383838", "d8d8d8", "a0a0a0", "7cafc2", "ffffff", "f6f8fa", "e5e9ef", "24292e", "586069", "0366d6"),
    "Base16 256" to listOf("181818", "101010", "383838", "d8d8d8", "a0a0a0", "ab4642", "ffffff", "f6f8fa", "e5e9ef", "24292e", "586069", "0366d6"),
    "Base16 Eighties" to listOf("2d2d2d", "262626", "515151", "d3d0c8", "a09f93", "f99157", "ffffff", "f6f8fa", "e5e9ef", "24292e", "586069", "0366d6"),
    "Base16 Mocha" to listOf("3b3228", "302820", "645240", "d0c8c6", "b8a99a", "a89bb9", "ffffff", "f6f8fa", "e5e9ef", "24292e", "586069", "0366d6"),
    "Base16 Ocean" to listOf("2b303b", "232833", "4f5b66", "c0c5ce", "a7adba", "8fa1b3", "ffffff", "f6f8fa", "e5e9ef", "24292e", "586069", "0366d6"),
    "Catppuccin Frapp\u00e9" to listOf("303446", "292c3c", "414559", "c6d0f5", "a5adce", "ca9ee6", "ffffff", "f6f8fa", "e5e9ef", "24292e", "586069", "0366d6"),
    "Catppuccin Macchiato" to listOf("24273a", "1e2030", "363a4f", "cad3f5", "a5adcb", "c6a0f6", "ffffff", "f6f8fa", "e5e9ef", "24292e", "586069", "0366d6"),
    "Coldark" to listOf("111b27", "0b141f", "213043", "e3eaf2", "8da1b9", "66b3ff", "ffffff", "f6f8fa", "e5e9ef", "24292e", "586069", "0366d6"),
    "Dark Neon" to listOf("000000", "111111", "282828", "e0e0e0", "a0a0a0", "ff66cc", "ffffff", "f6f8fa", "e5e9ef", "24292e", "586069", "0366d6"),
    "GitHub" to listOf("24292e", "1b1f23", "444d56", "e1e4e8", "959da5", "79b8ff", "ffffff", "f6f8fa", "e5e9ef", "24292e", "586069", "0366d6"),
    "Inspired GitHub" to listOf("24292e", "1b1f23", "444d56", "e1e4e8", "959da5", "85e89d", "ffffff", "f6f8fa", "e5e9ef", "24292e", "586069", "0366d6"),
    "Monokai Extended" to listOf("272822", "1e1f1c", "49483e", "f8f8f2", "a6a69c", "a6e22e", "ffffff", "f6f8fa", "e5e9ef", "24292e", "586069", "0366d6"),
    "Monokai Extended Bright" to listOf("272822", "1e1f1c", "49483e", "f8f8f2", "a6a69c", "66d9ef", "ffffff", "f6f8fa", "e5e9ef", "24292e", "586069", "0366d6"),
    "Monokai Extended Origin" to listOf("272822", "1e1f1c", "49483e", "f8f8f2", "a6a69c", "f92672", "ffffff", "f6f8fa", "e5e9ef", "24292e", "586069", "0366d6"),
    "One Half" to listOf("282c34", "21252b", "3e4451", "dcdfe4", "a0a7b4", "61afef", "ffffff", "f6f8fa", "e5e9ef", "24292e", "586069", "0366d6"),
    "Sublime Snazzy" to listOf("282a36", "222430", "44475a", "eff0eb", "a5a6ac", "ff6ac1", "ffffff", "f6f8fa", "e5e9ef", "24292e", "586069", "0366d6"),
    "Two Dark" to listOf("282c34", "21252b", "3e4451", "abb2bf", "9da5b4", "c678dd", "ffffff", "f6f8fa", "e5e9ef", "24292e", "586069", "0366d6"),
    "Zenburn" to listOf("3f3f3f", "333333", "505050", "dcdccc", "b0b09b", "f0dfaf", "ffffff", "f6f8fa", "e5e9ef", "24292e", "586069", "0366d6"),
)

// map old duplicate palette names back here so nobody loses their picked theme
private val paletteAliases =
    mapOf(
        "Catppuccin Latte" to "Catppuccin",
        "Catppuccin Mocha" to "Catppuccin",
        "Base16 Ocean Light" to "Base16 Ocean",
        "Coldark Cold" to "Coldark",
        "Coldark Dark" to "Coldark",
        "Gruvbox Dark" to "Gruvbox",
        "Gruvbox Light" to "Gruvbox",
        "Monokai Extended Light" to "Monokai Extended",
        "One Half Dark" to "One Half",
        "One Half Light" to "One Half",
        "Solarized Dark" to "Solarized",
        "Solarized Light" to "Solarized",
    )

fun canonicalPalette(name: String): String {
    val mapped = paletteAliases[name] ?: name
    return if (mapped in Palettes) mapped else "Catppuccin"
}

@Composable
fun SillyTheme(preferences: Preferences, content: @Composable () -> Unit) {
    val scheme = remember(preferences.palette, preferences.light) {
        val p = Palettes[canonicalPalette(preferences.palette)] ?: Palettes.getValue("Catppuccin")
        val light = preferences.light
        val o = if (light) 6 else 0
        fun color(i: Int): Color = Color(("ff" + p[o + i]).toLong(16))
        val bg = color(0)
        val base = color(1)
        val hover = color(2)
        val text = color(3)
        val muted = color(4)
        val accent = color(5)
        val errorColor = if (light) Color(0xffb12b48) else Color(0xfff38ba8)
        val errorContainerColor = lerp(bg, errorColor, if (light) 0.14f else 0.22f)
        val onErrorContainerColor = if (light) Color(0xff5c1022) else errorColor
        // fill in every M3 surface container role from the active palette so default purple never leaks into modals
        val surfaceLowest = if (light) bg else lerp(base, Color.Black, 0.2f)
        val surfaceLow = if (light) lerp(bg, base, 0.65f) else lerp(bg, base, 0.45f)
        val surfaceMid = base
        val surfaceHigh = if (light) lerp(base, hover, 0.55f) else lerp(bg, hover, 0.55f)
        val surfaceHighest = hover
        (if (light) lightColorScheme() else darkColorScheme()).copy(
            primary = accent,
            onPrimary = base,
            primaryContainer = lerp(bg, accent, if (light) 0.16f else 0.24f),
            onPrimaryContainer = text,
            secondary = accent,
            onSecondary = base,
            secondaryContainer = surfaceHigh,
            onSecondaryContainer = text,
            tertiary = accent,
            onTertiary = base,
            background = bg,
            onBackground = text,
            surface = bg,
            onSurface = text,
            surfaceDim = surfaceLowest,
            surfaceBright = surfaceHighest,
            surfaceVariant = hover,
            onSurfaceVariant = muted,
            outline = muted.copy(alpha = .4f),
            outlineVariant = muted.copy(alpha = .18f),
            surfaceContainerLowest = surfaceLowest,
            surfaceContainerLow = surfaceLow,
            surfaceContainer = surfaceMid,
            surfaceContainerHigh = surfaceHigh,
            surfaceContainerHighest = surfaceHighest,
            error = errorColor,
            onError = base,
            errorContainer = errorContainerColor,
            onErrorContainer = onErrorContainerColor,
        )
    }
    MaterialTheme(
        colorScheme = scheme,
        shapes = remember {
            Shapes(
                extraSmall = RoundedCornerShape(5.dp),
                small = RoundedCornerShape(7.dp),
                medium = RoundedCornerShape(10.dp),
                large = RoundedCornerShape(12.dp),
                extraLarge = RoundedCornerShape(14.dp),
            )
        },
        content = content,
    )
}
