package com.vps.manager.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import com.trilead.ssh2.Connection
import com.vps.manager.ssh.FileKind
import com.vps.manager.ssh.ParallelSftpSource
import com.vps.manager.ssh.RemoteFile
import com.vps.manager.ssh.RemoteFiles
import com.vps.manager.ssh.humanSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** מצב הצפייה בקובץ. שום דבר לא נשמר בטלפון — הכול מהשרת ישירות. */
sealed interface LoadState {
    /** סרטון או שמע — מנוגן ישירות מהשרת. */
    data object Streaming : LoadState
    /** תמונה בטעינה לזיכרון. */
    data class Loading(val done: Long, val total: Long) : LoadState
    /** תמונה מוכנה — בזיכרון בלבד. */
    data class ImageReady(val bytes: ByteArray) : LoadState
    data class Failed(val message: String) : LoadState
}

@Composable
fun MediaViewerDialog(
    remote: RemoteFile,
    kind: FileKind,
    state: LoadState,
    session: com.vps.manager.ssh.SshSession?,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var landscape by remember { mutableStateOf(false) }
    var showPlayerMenu by remember { mutableStateOf(false) }
    var playerRef by remember { mutableStateOf<ExoPlayer?>(null) }

    // מסך מלא אמיתי: מסתירים את סרגלי המערכת כדי ששום כפתור לא ייחתך
    DisposableEffect(Unit) {
        val window = activity?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    LaunchedEffect(landscape) {
        activity?.requestedOrientation =
            if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        (LocalContext.current as? Activity)?.window?.let { WindowCompat.setDecorFitsSystemWindows(it, false) }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            when (state) {
                is LoadState.Streaming ->
                    if (session != null) {
                        StreamPlayer(
                            remote = remote,
                            session = session,
                            onPlayerReady = { playerRef = it }
                        )
                    } else {
                        CenterMessage("אין חיבור פעיל לשרת")
                    }

                is LoadState.Loading -> Column(
                    Modifier.align(Alignment.Center).padding(32.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val fraction = if (state.total > 0)
                        (state.done.toFloat() / state.total).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(0.7f).height(4.dp),
                        color = Ios.blue
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("טוען מהשרת…", color = Color.White.copy(alpha = 0.85f))
                }

                is LoadState.ImageReady -> ZoomableImage(state.bytes)

                is LoadState.Failed -> Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = Ios.red, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(state.message, color = Color.White, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onRetry) { Text("ניסיון נוסף") }
                }
            }

            // סרגל עליון צף
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCancel, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Rounded.Close, "סגירה", tint = Color.White)
                }
                Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            remote.name,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        remote.size.humanSize(),
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                if (state is LoadState.Streaming) {
                    IconButton(onClick = { landscape = !landscape }, modifier = Modifier.size(40.dp)) {
                        Icon(
                            if (landscape) Icons.Rounded.ScreenRotation else Icons.Rounded.Fullscreen,
                            "סיבוב מסך", tint = Color.White
                        )
                    }
                    IconButton(onClick = { showPlayerMenu = true }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Rounded.Tune, "ערוצי שמע וכתוביות", tint = Color.White)
                    }
                    DropdownMenu(expanded = showPlayerMenu, onDismissRequest = { showPlayerMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("ערוץ שמע") },
                            leadingIcon = { Icon(Icons.Rounded.Audiotrack, null) },
                            onClick = {
                                showPlayerMenu = false
                                playerRef?.let { p ->
                                    showTrackDialog(context, p, C.TRACK_TYPE_AUDIO, "בחירת ערוץ שמע")
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("כתוביות") },
                            leadingIcon = { Icon(Icons.Rounded.ClosedCaption, null) },
                            onClick = {
                                showPlayerMenu = false
                                playerRef?.let { p ->
                                    showTrackDialog(context, p, C.TRACK_TYPE_TEXT, "בחירת כתוביות")
                                }
                            }
                        )
                    }
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Rounded.Delete, "מחיקה", tint = Ios.red)
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
private fun showTrackDialog(context: android.content.Context, player: ExoPlayer, type: Int, title: String) {
    runCatching {
        TrackSelectionDialogBuilder(context, title, player, type)
            .setShowDisableOption(true)
            .setAllowAdaptiveSelections(false)
            .build()
            .show()
    }
}

@Composable
private fun BoxScope.CenterMessage(text: String) {
    Text(text, Modifier.align(Alignment.Center), color = Color.White)
}

/** נגן שמזרים מהשרת בכמה ערוצים וחיבורים במקביל — התחלה מיידית ודילוג מהיר. */
@OptIn(UnstableApi::class)
@Composable
private fun StreamPlayer(
    remote: RemoteFile,
    session: com.vps.manager.ssh.SshSession,
    onPlayerReady: (ExoPlayer) -> Unit
) {
    val context = LocalContext.current
    var error by remember(remote.path) { mutableStateOf<String?>(null) }
    var player by remember(remote.path) { mutableStateOf<ExoPlayer?>(null) }
    var preparing by remember(remote.path) { mutableStateOf(true) }

    LaunchedEffect(remote.path) {
        // פתיחת חיבורי ההעברה וחיפוש כתוביות נעשים מחוץ למסך הראשי
        val conns = withContext(Dispatchers.IO) {
            runCatching { session.transferConnections() }.getOrDefault(emptyList())
        }
        if (conns.isEmpty()) {
            error = "אין חיבור פעיל לשרת"
            preparing = false
            return@LaunchedEffect
        }
        val subs = withContext(Dispatchers.IO) {
            runCatching { RemoteFiles.siblingSubtitles(conns[0], remote) }.getOrDefault(emptyList())
        }

        val built = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ParallelSftpSource.factory(conns)))
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(10_000, 120_000, 600, 1_500)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            )
            .build()

        built.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                error = "לא ניתן לנגן את הקובץ: ${e.errorCodeName}"
            }
        })

        val item = MediaItem.Builder()
            .setUri(ParallelSftpSource.uriFor(remote.path))
            .setSubtitleConfigurations(
                subs.map { sub ->
                    MediaItem.SubtitleConfiguration.Builder(ParallelSftpSource.uriFor(sub))
                        .setMimeType(
                            when (sub.substringAfterLast('.').lowercase()) {
                                "vtt" -> MimeTypes.TEXT_VTT
                                "ass", "ssa" -> MimeTypes.TEXT_SSA
                                else -> MimeTypes.APPLICATION_SUBRIP
                            }
                        )
                        .setLabel(sub.substringAfterLast('/'))
                        .build()
                }
            )
            .build()

        built.setMediaItem(item)
        built.prepare()
        built.playWhenReady = true
        player = built
        preparing = false
        onPlayerReady(built)
    }

    DisposableEffect(remote.path) {
        onDispose { player?.release() }
    }

    Box(Modifier.fillMaxSize()) {
        val p = player
        if (p != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = true
                        controllerShowTimeoutMs = 3500
                        setShowSubtitleButton(true)
                        setKeepContentOnPlayerReset(true)
                        keepScreenOn = true
                    }
                },
                update = { view -> view.player = p },
                modifier = Modifier.fillMaxSize()
            )
        } else if (preparing) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = Ios.blue)
                Spacer(Modifier.height(10.dp))
                Text("מתחבר לשרת…", color = Color.White.copy(alpha = 0.8f))
            }
        }
        error?.let {
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Rounded.ErrorOutline, null, tint = Ios.red, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(8.dp))
                Text(it, color = Color.White, textAlign = TextAlign.Center)
            }
        }
    }
}

/** תמונה מהזיכרון — לא נשמרת בטלפון. */
@Composable
private fun ZoomableImage(bytes: ByteArray) {
    val bitmap = remember(bytes) {
        runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            val max = maxOf(opts.outWidth, opts.outHeight).coerceAtLeast(1)
            val sample = generateSequence(1) { it * 2 }.first { max / it <= 2400 }
            BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        }.getOrNull()
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    if (bitmap == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("לא ניתן להציג את התמונה", color = Color.White)
        }
        return
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    if (scale > 1f) {
                        offsetX += pan.x; offsetY += pan.y
                    } else {
                        offsetX = 0f; offsetY = 0f
                    }
                }
            }
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY)
    )
}
