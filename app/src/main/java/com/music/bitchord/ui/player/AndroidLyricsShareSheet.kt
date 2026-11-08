package com.music.bitchord.ui.player

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import com.music.bitchord.ui.replay.ShareAction
import com.music.bitchord.ui.replay.cacheForSharing
import com.music.bitchord.ui.replay.saveToGallery
import com.music.bitchord.ui.replay.sendIntent
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/**
 * The picked lines, drawn, with the two things you can do to the picture.
 *
 * Built on [PlayerDrawer] rather than on a `ModalBottomSheet` for the same reason
 * as every other sheet in this package: it is inside the player, which is itself
 * already a modal sheet, and nesting the two leaves the inner one with nothing
 * to be modal over. It also means this drawer gets the player's own frosted
 * material, the drag-to-dismiss gesture and the scrim fade for free.
 *
 * The render happens on a background dispatcher and the preview is a plain
 * `Image` of the result — no second layout pass, no offscreen Compose, and the
 * identical pixels that the Save and Share buttons then write to disk.
 */
@Composable
internal fun AndroidLyricsShareSheet(
    hazeState: HazeState,
    request: LyricsShareRequest,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()

    var image by remember(request) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(request) { mutableStateOf(false) }
    var saved by remember(request) { mutableStateOf(false) }
    // Same picture, cut to the 9:16 frame a story wants instead of to its lines.
    var story by remember(request) { mutableStateOf(false) }
    var customize by remember(request) { mutableStateOf(false) }
    var style by remember(request) { mutableStateOf<LyricsCardStyle?>(null) }
    val effectiveStyle = style ?: LyricsCardStyle.DEFAULT

    LaunchedEffect(request, story, style) {
        image = null
        failed = false
        saved = false
        image = try {
            renderLyricsShareCard(context, request, story, style)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            failed = true
            null
        }
    }

    PlayerDrawer(
        hazeState = hazeState,
        title = stringResource(R.string.lyrics_share_title),
        onDismiss = onDismiss,
        modifier = modifier,
        titleGap = 2.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.lyrics_share_description),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
            )

            val bitmap = image
            // The card is only as tall as its lines, so the frame follows it:
            // a strip comes out wide, a full verse narrow, both about the same
            // height on screen rather than a fixed box with bands to spare.
            val ratio = bitmap?.let { it.width.toFloat() / it.height } ?: (9f / 16f)
            val preview = Modifier
                .fillMaxWidth((0.72f * ratio).coerceIn(0.36f, 0.92f))
                .aspectRatio(ratio)
                .clip(RoundedCornerShape(18.dp))
            if (bitmap == null) {
                Box(
                    modifier = preview
                        .background(Color.White.copy(alpha = 0.06f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(26.dp),
                        strokeWidth = 2.5.dp,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }
            } else {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.your_lyrics_card),
                    modifier = preview,
                )
            }

            ShareAction(
                label = stringResource(R.string.lyrics_share_customize),
                icon = Icons.Rounded.Tune,
                accent = customize,
                enabled = true,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    haptics.play(Haptic.Tap)
                    customize = !customize
                },
            )

            if (customize) {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.lyrics_share_style), style = MaterialTheme.typography.labelLarge, color = Color.White)
                    LyricsCardTheme.entries.chunked(2).forEach { group ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            group.forEach { theme ->
                                FilterChip(
                                    selected = effectiveStyle.theme == theme,
                                    colors = editorChipColors(),
                                    onClick = { style = effectiveStyle.withTheme(theme) },
                                    label = { Text(stringResource(theme.labelRes()), color = Color.White) },
                                )
                            }
                        }
                    }

                    Text(stringResource(R.string.lyrics_share_background), style = MaterialTheme.typography.labelLarge, color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LyricsCardBackground.entries.forEach { option ->
                            FilterChip(
                                selected = effectiveStyle.background == option,
                                colors = editorChipColors(),
                                onClick = { style = effectiveStyle.copy(background = option) },
                                label = { Text(stringResource(when (option) {
                                    LyricsCardBackground.ARTWORK -> R.string.lyrics_share_artwork
                                    LyricsCardBackground.GRADIENT -> R.string.lyrics_share_gradient
                                    LyricsCardBackground.SOLID -> R.string.lyrics_share_solid
                                }), color = Color.White) },
                            )
                        }
                    }
                    if (effectiveStyle.background != LyricsCardBackground.ARTWORK) {
                        Text(stringResource(R.string.lyrics_share_background_color), style = MaterialTheme.typography.labelLarge, color = Color.White)
                        ColorChoices(effectiveStyle.backgroundStart) { color -> style = effectiveStyle.copy(backgroundStart = color) }
                    }
                    if (effectiveStyle.background == LyricsCardBackground.GRADIENT) {
                        Text(stringResource(R.string.lyrics_share_gradient_color), style = MaterialTheme.typography.labelLarge, color = Color.White)
                        ColorChoices(effectiveStyle.backgroundEnd) { color -> style = effectiveStyle.copy(backgroundEnd = color) }
                    }
                    Text(stringResource(R.string.lyrics_share_text_color), style = MaterialTheme.typography.labelLarge, color = Color.White)
                    ColorChoices(effectiveStyle.textColor) { color -> style = effectiveStyle.copy(textColor = color) }

                    Text(stringResource(R.string.lyrics_share_font), style = MaterialTheme.typography.labelLarge, color = Color.White)
                    LyricsCardFont.entries.chunked(3).forEach { group ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            group.forEach { font ->
                                FilterChip(
                                    selected = effectiveStyle.font == font,
                                    colors = editorChipColors(),
                                    onClick = { style = effectiveStyle.copy(font = font) },
                                    label = { Text(stringResource(when (font) {
                                        LyricsCardFont.BITFUSE -> R.string.lyrics_share_font_default
                                        LyricsCardFont.SANS -> R.string.lyrics_share_font_sans
                                        LyricsCardFont.SERIF -> R.string.lyrics_share_font_serif
                                        LyricsCardFont.MONOSPACE -> R.string.lyrics_share_font_mono
                                        LyricsCardFont.ROUNDED -> R.string.lyrics_share_font_rounded
                                    }), color = Color.White) },
                                )
                            }
                        }
                    }

                    Text(stringResource(R.string.lyrics_share_art_shape), style = MaterialTheme.typography.labelLarge, color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LyricsCardArtworkShape.entries.forEach { shape ->
                            FilterChip(
                                selected = effectiveStyle.artworkShape == shape,
                                colors = editorChipColors(),
                                onClick = { style = effectiveStyle.copy(artworkShape = shape) },
                                label = { Text(stringResource(when (shape) {
                                    LyricsCardArtworkShape.SQUARE -> R.string.lyrics_share_shape_square
                                    LyricsCardArtworkShape.ROUNDED -> R.string.lyrics_share_shape_rounded
                                    LyricsCardArtworkShape.CIRCLE -> R.string.lyrics_share_shape_circle
                                }), color = Color.White) },
                            )
                        }
                    }
                    Text(stringResource(R.string.lyrics_share_card_corners), style = MaterialTheme.typography.labelLarge, color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0f, 48f, 96f).forEach { radius ->
                            FilterChip(
                                selected = effectiveStyle.cornerRadius == radius,
                                colors = editorChipColors(),
                                onClick = { style = effectiveStyle.copy(cornerRadius = radius) },
                                label = { Text(stringResource(when (radius) {
                                    0f -> R.string.lyrics_share_shape_square
                                    48f -> R.string.lyrics_share_corner_rounded
                                    else -> R.string.lyrics_share_corner_round
                                }), color = Color.White) },
                            )
                        }
                    }

                    Text(stringResource(R.string.lyrics_share_alignment), style = MaterialTheme.typography.labelLarge, color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LyricsCardAlignment.entries.forEach { alignment ->
                            FilterChip(
                                selected = effectiveStyle.alignment == alignment,
                                colors = editorChipColors(),
                                onClick = { style = effectiveStyle.copy(alignment = alignment) },
                                label = { Text(stringResource(when (alignment) {
                                    LyricsCardAlignment.LEFT -> R.string.lyrics_share_align_left
                                    LyricsCardAlignment.CENTER -> R.string.lyrics_share_align_center
                                    LyricsCardAlignment.RIGHT -> R.string.lyrics_share_align_right
                                }), color = Color.White) },
                            )
                        }
                    }

                    Text(stringResource(R.string.lyrics_share_font_size), style = MaterialTheme.typography.labelLarge, color = Color.White)
                    Slider(value = effectiveStyle.fontScale, onValueChange = { style = effectiveStyle.copy(fontScale = it) }, valueRange = 0.75f..1.25f)
                    Text(stringResource(R.string.lyrics_share_line_spacing), style = MaterialTheme.typography.labelLarge, color = Color.White)
                    Slider(value = effectiveStyle.lineSpacing, onValueChange = { style = effectiveStyle.copy(lineSpacing = it) }, valueRange = 0.8f..1.4f)
                    SettingSwitch(stringResource(R.string.lyrics_share_brand), effectiveStyle.showBrand) {
                        style = effectiveStyle.copy(showBrand = it)
                    }
                    SettingSwitch(stringResource(R.string.lyrics_share_track_info), effectiveStyle.showTrackInfo) {
                        style = effectiveStyle.copy(showTrackInfo = it)
                    }
                    ShareAction(
                        label = stringResource(R.string.lyrics_share_reset),
                        icon = Icons.Rounded.Undo,
                        accent = false,
                        enabled = style != null,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { style = null },
                    )
                }
            }

            ShareAction(
                label = stringResource(if (story) R.string.lyrics_share_story_revert else R.string.lyrics_share_story),
                icon = if (story) Icons.Rounded.Undo else Icons.Rounded.PhoneIphone,
                accent = false,
                enabled = image != null,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    haptics.play(Haptic.Tap)
                    story = !story
                },
            )

            if (failed) {
                Text(
                    text = stringResource(R.string.couldnt_draw_picture),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ShareAction(
                    label = stringResource(R.string.save),
                    icon = Icons.Rounded.Download,
                    accent = false,
                    enabled = image != null,
                    modifier = Modifier.weight(1f),
                    // Once the picture is in the gallery the button says so
                    // itself — a check and "Saved!" — so the line under the
                    // card does not have to.
                    saved = saved,
                    savedLabel = stringResource(R.string.saved_bang),
                    onClick = {
                        val drawn = image
                        if (drawn != null) {
                            haptics.play(Haptic.Tap)
                            scope.launch {
                                saved = saveToGallery(
                                    context,
                                    drawn,
                                    request.song.title,
                                    LYRICS_FILE_PREFIX,
                                )
                            }
                        }
                    },
                )
                ShareAction(
                    label = stringResource(R.string.share),
                    icon = Icons.Rounded.IosShare,
                    accent = true,
                    enabled = image != null,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val drawn = image
                        if (drawn != null) {
                            haptics.play(Haptic.Tap)
                            scope.launch {
                                val uri = cacheForSharing(context, drawn, LYRICS_FILE_NAME)
                                if (uri == null) {
                                    failed = true
                                    saved = false
                                } else {
                                    context.startActivity(
                                        Intent.createChooser(
                                            sendIntent(uri),
                                            context.getString(R.string.share_your_lyrics),
                                        ),
                                    )
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ColorChoices(selected: Int, onSelect: (Int) -> Unit) {
    val colors = listOf(
        Color(0xFF111111), Color(0xFF292044), Color(0xFF824C72), Color(0xFF244B5A),
        Color(0xFF285943), Color(0xFF8A453C), Color(0xFFB78131), Color(0xFFEEEEEE), Color.White,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        colors.forEach { color ->
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(color)
                    .clickable { onSelect(color.toArgb()) }
                    .then(if (color.toArgb() == selected) Modifier.background(Color.Transparent, CircleShape) else Modifier),
            )
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Color.White)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun editorChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = Color.White.copy(alpha = 0.08f),
    labelColor = Color.White,
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = Color.White,
)

private fun LyricsCardTheme.labelRes(): Int = when (this) {
    LyricsCardTheme.BITFUSE -> R.string.lyrics_theme_bitfuse
    LyricsCardTheme.SPOTIFY -> R.string.lyrics_theme_spotify
    LyricsCardTheme.RUSHED -> R.string.lyrics_theme_rushed
    LyricsCardTheme.VERTICAL -> R.string.lyrics_theme_vertical
    LyricsCardTheme.COUPLET -> R.string.lyrics_theme_couplet
    LyricsCardTheme.ALBUM_ART -> R.string.lyrics_theme_album_art
    LyricsCardTheme.HYPNOTIC -> R.string.lyrics_theme_hypnotic
    LyricsCardTheme.QUOTE -> R.string.lyrics_theme_quote
    LyricsCardTheme.MESSY -> R.string.lyrics_theme_messy
    LyricsCardTheme.BRAT -> R.string.lyrics_theme_brat
}

private fun LyricsCardStyle.withTheme(theme: LyricsCardTheme): LyricsCardStyle = when (theme) {
    LyricsCardTheme.BITFUSE -> LyricsCardStyle.DEFAULT
    LyricsCardTheme.SPOTIFY -> copy(theme = theme, background = LyricsCardBackground.SOLID, backgroundStart = 0xFF101010.toInt(), textColor = Color.White.toArgb(), alignment = LyricsCardAlignment.LEFT, font = LyricsCardFont.SANS, showBrand = false, showTrackInfo = false)
    LyricsCardTheme.RUSHED -> copy(theme = theme, background = LyricsCardBackground.ARTWORK, textColor = Color.White.toArgb(), alignment = LyricsCardAlignment.LEFT, showBrand = false, showTrackInfo = true)
    LyricsCardTheme.VERTICAL -> copy(theme = theme, background = LyricsCardBackground.GRADIENT, backgroundStart = 0xFF14232B.toInt(), backgroundEnd = 0xFF38545D.toInt(), textColor = Color.White.toArgb(), alignment = LyricsCardAlignment.RIGHT, showBrand = false, showTrackInfo = true)
    LyricsCardTheme.COUPLET -> copy(theme = theme, background = LyricsCardBackground.GRADIENT, backgroundStart = 0xFF402456.toInt(), backgroundEnd = 0xFF171322.toInt(), textColor = Color.White.toArgb(), alignment = LyricsCardAlignment.LEFT, showBrand = false, showTrackInfo = true)
    LyricsCardTheme.ALBUM_ART -> copy(theme = theme, background = LyricsCardBackground.ARTWORK, textColor = Color.White.toArgb(), alignment = LyricsCardAlignment.CENTER, showBrand = false, showTrackInfo = false)
    LyricsCardTheme.HYPNOTIC -> copy(theme = theme, background = LyricsCardBackground.GRADIENT, backgroundStart = 0xFF1F1235.toInt(), backgroundEnd = 0xFF70458A.toInt(), textColor = Color.White.toArgb(), alignment = LyricsCardAlignment.CENTER, showBrand = false, showTrackInfo = true)
    LyricsCardTheme.QUOTE -> copy(theme = theme, background = LyricsCardBackground.GRADIENT, backgroundStart = 0xFF252536.toInt(), backgroundEnd = 0xFF5C657A.toInt(), textColor = Color.White.toArgb(), alignment = LyricsCardAlignment.CENTER, showBrand = false, showTrackInfo = false)
    LyricsCardTheme.MESSY -> copy(theme = theme, background = LyricsCardBackground.ARTWORK, textColor = Color.White.toArgb(), alignment = LyricsCardAlignment.LEFT, showBrand = false, showTrackInfo = true)
    LyricsCardTheme.BRAT -> copy(theme = theme, background = LyricsCardBackground.SOLID, backgroundStart = 0xFFB7FF00.toInt(), textColor = 0xFF111111.toInt(), font = LyricsCardFont.MONOSPACE, alignment = LyricsCardAlignment.LEFT, showBrand = false, showTrackInfo = false)
}

/**
 * Two names, deliberately not the Replay's.
 *
 * The cache name so a replay share and a lyrics share cannot overwrite each
 * other while the receiving app is still reading the first, and the gallery
 * prefix so a saved card in the pictures folder says which feature produced it.
 */
private const val LYRICS_FILE_NAME = "lyrics.png"
private const val LYRICS_FILE_PREFIX = "bitchord-lyrics"
