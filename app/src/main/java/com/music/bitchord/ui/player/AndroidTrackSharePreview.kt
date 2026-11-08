package com.music.bitchord.ui.player

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.ColorUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.replay.Fonts
import com.music.bitchord.ui.replay.cacheForSharing
import com.music.bitchord.ui.replay.drawArtwork
import com.music.bitchord.ui.replay.loadBitmap
import com.music.bitchord.ui.replay.paletteOf
import com.music.bitchord.ui.replay.sendIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AndroidTrackSharePreview(
    song: Song,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var image by remember(song.videoId) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(song.videoId, song.thumbnailUrl) {
        image = renderTrackShareCard(context, song)
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).widthIn(max = 400.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.track_share_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.track_share_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.close), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                val bitmap = image
                val frameShape = RoundedCornerShape(18.dp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(9f / 16f)
                        .clip(frameShape)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, frameShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bitmap == null) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    } else {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.track_share_preview_description),
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                Button(
                    enabled = bitmap != null,
                    onClick = {
                        val card = bitmap ?: return@Button
                        scope.launch {
                            cacheForSharing(context, card, "bitchord-track-share.png")?.let {
                                context.startActivity(android.content.Intent.createChooser(sendIntent(it), song.title))
                            }
                            onDismiss()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.IosShare, contentDescription = null)
                    Text(text = stringResource(R.string.track_share_action), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

private suspend fun renderTrackShareCard(context: Context, song: Song): Bitmap =
    withContext(Dispatchers.Default) {
        val width = 1080f
        val height = 1920f
        val cover = song.thumbnailUrl?.let { loadBitmap(context, it) }
        Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            val palette = paletteOf(cover)
            val deep = palette.map { ColorUtils.blendARGB(it, android.graphics.Color.BLACK, 0.38f) }
            val backdrop = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, width, height,
                    intArrayOf(deep[0], palette[0], palette[1], deep[2]),
                    floatArrayOf(0f, 0.40f, 0.74f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, width, height, backdrop)
            backdrop.shader = RadialGradient(
                width * 0.5f, height * 0.43f, width * 0.78f,
                intArrayOf(ColorUtils.setAlphaComponent(palette[2], 155), android.graphics.Color.TRANSPARENT),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, width, height, backdrop)
            backdrop.shader = LinearGradient(
                0f, height * 0.66f, 0f, height,
                intArrayOf(android.graphics.Color.TRANSPARENT, 0x52000000),
                null,
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, width, height, backdrop)
            backdrop.shader = null

            val artSize = 700f
            val artX = (width - artSize) / 2f
            val artY = 405f
            val frame = RectF(artX - 12f, artY - 12f, artX + artSize + 12f, artY + artSize + 12f)
            val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x99FFFFFF.toInt()
                style = Paint.Style.STROKE
                strokeWidth = 5f
                setShadowLayer(28f, 0f, 0f, 0xCCFFFFFF.toInt())
            }
            canvas.drawRoundRect(frame, 54f, 54f, halo)
            halo.clearShadowLayer()
            halo.color = 0xB3FFFFFF.toInt()
            halo.strokeWidth = 2f
            canvas.drawRoundRect(frame, 54f, 54f, halo)
            drawArtwork(canvas, cover, song.title, artX, artY, artSize, false)

            val fonts = Fonts(context)
            val title = fonts.heading(76f, android.graphics.Color.WHITE).apply { isFakeBoldText = true }
            val titleLines = wrapShareTitle(song.title, title, width - 120f, 2)
            var baseline = 1245f
            titleLines.forEach { line ->
                canvas.drawText(line, (width - title.measureText(line)) / 2f, baseline, title)
                baseline += 92f
            }
            val artist = fonts.body(48f, 0xE8FFFFFF.toInt())
            val artistText = ellipsizeShareText(song.artist, artist, width - 140f)
            canvas.drawText(artistText, (width - artist.measureText(artistText)) / 2f, baseline + 24f, artist)
            val brand = fonts.label(30f, 0xBFFFFFFF.toInt())
            val brandText = "BITFUSE  •  NOW PLAYING"
            canvas.drawText(brandText, (width - brand.measureText(brandText)) / 2f, 1790f, brand)
        }
    }

private fun wrapShareTitle(text: String, paint: Paint, maxWidth: Float, maxLines: Int): List<String> {
    val words = text.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    if (words.isEmpty()) return listOf("Unknown track")
    val lines = mutableListOf<String>()
    var line = ""
    for (word in words) {
        val candidate = if (line.isEmpty()) word else "$line $word"
        if (line.isNotEmpty() && paint.measureText(candidate) > maxWidth) {
            lines += line
            line = word
            if (lines.size == maxLines) break
        } else {
            line = candidate
        }
    }
    if (lines.size < maxLines && line.isNotEmpty()) lines += line
    if (lines.size == maxLines && words.joinToString(" ").length > lines.joinToString(" ").length) {
        lines[lines.lastIndex] = ellipsizeShareText(lines.last(), paint, maxWidth - paint.measureText("…")) + "…"
    }
    return lines
}

private fun ellipsizeShareText(text: String, paint: Paint, maxWidth: Float): String {
    var result = text.trim()
    while (result.isNotEmpty() && paint.measureText("$result…") > maxWidth) {
        result = result.dropLast(1).trimEnd()
    }
    return if (result == text.trim()) result else "$result…"
}
