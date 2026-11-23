package dev.sillyplayer.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PermissionNotice(pickers: Pickers, compact: Boolean = false) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    fun enabled() = NotificationManagerCompat.from(context).areNotificationsEnabled()
    var notifications by remember { mutableStateOf(enabled()) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val request =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            attempted = true
            notifications = enabled()
        }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) notifications = enabled()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = if (compact) 12.dp else 0.dp)) {
        Hint(
            "Allow folder access to save songs. Allow notifications for playback and download controls. Camera access is optional, only for scanning QR codes; you can paste a pairing link instead."
        )
        FlowRow {
            if (!compact) Action("Choose audio folder", "folder") { pickers.folder("destination") }
            Action(
                if (notifications) "Notification settings" else "Allow notifications",
                "settings",
            ) {
                if (
                    Build.VERSION.SDK_INT >= 33 &&
                        !attempted &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                )
                    request.launch(Manifest.permission.POST_NOTIFICATIONS)
                else
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    )
            }
        }
        if (!compact)
            Hint(if (notifications) "Notifications enabled" else "Notifications not allowed")
    }
}
