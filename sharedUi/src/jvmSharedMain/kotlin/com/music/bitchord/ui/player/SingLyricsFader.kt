package com.music.bitchord.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.ic_slider_sing
import org.jetbrains.compose.resources.painterResource

/** Shared fader content; platform host contributes only the glass modifier. */
@Composable
internal fun LyricsSingFader(modifier: Modifier = Modifier) {
    val host = PlayerPlatform.host
    if (!host.singSupported) return
    val enabled by host.singEnabled.collectAsStateWithLifecycle()
    val level by host.singLevel.collectAsStateWithLifecycle()
    val fillLevel by animateFloatAsState(
        targetValue = if (enabled) level.coerceIn(0f, 1f) else 0.06f,
        animationSpec = tween(durationMillis = 240),
        label = "singFaderLevel",
    )
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(50)

    Box(
        modifier = modifier
            .width(68.dp)
            .height(164.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.015f), shape)
            .then(host.singFaderGlass)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.035f),
                        Color.White.copy(alpha = 0.005f),
                        Color.Black.copy(alpha = 0.015f),
                    ),
                ),
                shape,
            )
            .border(0.5.dp, Color.White.copy(alpha = 0.06f), shape)
            .pointerInput(host) {
                fun update(y: Float, start: Boolean) {
                    host.setSingLevel(1f - (y / size.height.toFloat()).coerceIn(0f, 1f))
                    if (start && !host.singEnabled.value) host.toggleSing()
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    update(down.position.y, true)
                    var lastY = down.position.y
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (change.position.y != lastY) {
                            update(change.position.y, false)
                            lastY = change.position.y
                        }
                        change.consume()
                    } while (change.pressed)
                }
            }
            .semantics {
                contentDescription = "Sing vocal level"
                stateDescription = if (enabled) "${(level * 100).toInt()} percent" else "Off"
                progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(level, 0f..1f)
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(fillLevel)
                .align(Alignment.BottomCenter)
                .background(Color.White.copy(alpha = if (enabled) 0.94f else 0.58f)),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp)
                .size(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(Res.drawable.ic_slider_sing),
                contentDescription = null,
                colorFilter = ColorFilter.tint(Color(0xFF30271F)),
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

@Composable
internal fun LyricsSingButton(
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val host = PlayerPlatform.host
    val singEnabled by host.singEnabled.collectAsStateWithLifecycle()
    val discAlpha by animateFloatAsState(
        targetValue = if (expanded) 0.34f else 0.18f,
        label = "singFaderDisc",
    )
    val tint = LocalPlayerTint.current.let {
        if (singEnabled) it else it.copy(alpha = 0.78f)
    }
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(LocalPlayerTint.current.copy(alpha = discAlpha))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .semantics {
                contentDescription = if (expanded) "Close Sing vocal level" else "Open Sing vocal level"
                stateDescription = if (singEnabled) "Sing on" else "Sing off"
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(Res.drawable.ic_slider_sing),
            contentDescription = null,
            colorFilter = ColorFilter.tint(tint),
            modifier = Modifier.size(19.dp),
        )
    }
}
