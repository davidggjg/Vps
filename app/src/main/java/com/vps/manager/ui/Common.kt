package com.vps.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.ui.unit.dp
import com.vps.manager.ssh.ConnState

fun ConnState.label(): String = when (this) {
    ConnState.Connected -> "מחובר"
    ConnState.Connecting -> "מתחבר"
    ConnState.Reconnecting -> "מתחבר מחדש"
    ConnState.Failed -> "שגיאה"
    ConnState.Disconnected -> "לא מחובר"
}

fun ConnState.color(): Color = when (this) {
    ConnState.Connected -> Ios.green
    ConnState.Connecting, ConnState.Reconnecting -> Ios.orange
    ConnState.Failed -> Ios.red
    ConnState.Disconnected -> Ios.gray
}

@Composable
fun StatusPill(state: ConnState, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(state.color().copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(RoundedCornerShape(50))
                .background(state.color())
        )
        Text(state.label(), style = MaterialTheme.typography.labelMedium, color = state.color())
    }
}
