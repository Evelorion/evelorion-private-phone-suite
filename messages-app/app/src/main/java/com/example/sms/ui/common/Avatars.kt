package com.example.sms.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun AvatarView(
    avatar: Avatar,
    size: Dp = 56.dp,
    shape: Shape = CircleShape,
) {
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .background(
                when (avatar) {
                    is Avatar.Initial -> avatar.color
                    is Avatar.Symbol -> avatar.bg
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        when (avatar) {
            is Avatar.Initial -> Text(
                avatar.text,
                color = Color.White,
                style = if (size >= 48.dp) MaterialTheme.typography.headlineSmall
                        else MaterialTheme.typography.labelLarge,
            )
            is Avatar.Symbol -> Icon(
                avatar.icon, null, tint = avatar.fg,
                modifier = Modifier.size(size * 0.46f),
            )
        }
    }
}
