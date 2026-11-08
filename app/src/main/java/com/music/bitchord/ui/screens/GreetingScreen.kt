package com.music.bitchord.ui.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun GreetingScreen(
    onComplete: () -> Unit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
) {
    Text(text = "Hello")
}
