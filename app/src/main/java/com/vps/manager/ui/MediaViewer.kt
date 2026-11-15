package com.vps.manager.ui

import android.graphics.BitmapFactory
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
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.vps.manager.ssh.FileKind
import com.vps.manager.ssh.RemoteFile
import com.vps.manager.ssh.humanSize
import java.io.File

/** מצב ההורדה של קובץ לצפייה. */
sealed interface LoadState {
    data class Downloading(val done: Long, val total: Long) : LoadState
    data class Ready(val file: File) : LoadState
    data class Failed(val message: String) : LoadState
}

@Composable
fun MediaViewerDialog(
    remote: RemoteFile,
    kind: FileKind,
    state: LoadState,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.96f))
        ) {
            when (state) {
                is LoadState.Downloading -> DownloadProgress(remote, state, onCancel)
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
                is LoadState.Ready -> when (kind) {
                    FileKind.Images -> ZoomableImage(state.file)
                    FileKind.Videos, FileKind.Audio -> Player(state.file)
                    else -> Column(
                        Modifier.align(Alignment.Center).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Rounded.InsertDriveFile, null, tint = Color.White, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("הקובץ הורד למכשיר", color = Color.White)
                    }
                }
            }

            // סרגל עליון מעל התוכן
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Rounded.Close, "סגירה", tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            remote.name,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1
                        )
                    }
                    Text(
                        remote.size.humanSize(),
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Rounded.Delete, "מחיקה", tint = Ios.red)
                }
            }
        }
    }
}

@Composable
private fun BoxScope.DownloadProgress(remote: RemoteFile, state: LoadState.Downloading, onCancel: () -> Unit) {
    Column(
        Modifier.align(Alignment.Center).padding(32.dp).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("מוריד מהשרת…", color = Color.White, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        val fraction = if (state.total > 0) (state.done.toFloat() / state.total).coerceIn(0f, 1f) else 0f
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = Ios.blue
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "${state.done.humanSize()} מתוך ${state.total.humanSize()}  ·  ${(fraction * 100).toInt()}%",
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(18.dp))
        TextButton(onClick = onCancel) { Text("ביטול", color = Ios.blue) }
    }
}

@Composable
private fun ZoomableImage(file: File) {
    val bitmap = remember(file.path) {
        runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, opts)
            val max = maxOf(opts.outWidth, opts.outHeight)
            val sample = generateSequence(1) { it * 2 }.first { max / it <= 2400 }
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
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
                        offsetX += pan.x
                        offsetY += pan.y
                    } else {
                        offsetX = 0f; offsetY = 0f
                    }
                }
            }
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY)
    )
}

@Composable
private fun Player(file: File) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }
    AndroidView(
        factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true } },
        modifier = Modifier.fillMaxSize()
    )
}
