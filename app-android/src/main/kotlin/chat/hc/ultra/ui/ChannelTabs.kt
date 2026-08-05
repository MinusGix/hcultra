package chat.hc.ultra.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.hc.core.session.ChannelUi
import chat.hc.core.session.SessionState

/**
 * Tab strip over the joined channels.
 *
 * Each channel is a separate socket — the server refuses a second `join` on one
 * — so a tab really is a distinct connection, and its state is worth showing:
 * a channel can be reconnecting while the one you are reading is fine.
 */
@Composable
fun ChannelTabs(
    channels: List<ChannelUi>,
    active: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onAdd: () -> Unit,
    /** Tapping a tab's online count opens the roster for that channel. */
    onShowRoster: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(channels, key = { it.channel }) { ui ->
                ChannelTab(
                    ui = ui,
                    selected = ui.channel == active,
                    onSelect = { onSelect(ui.channel) },
                    onClose = { onClose(ui.channel) },
                    onShowRoster = { onShowRoster(ui.channel) },
                )
            }
            item {
                Text(
                    text = "+",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(onClick = onAdd)
                        .padding(horizontal = 14.dp, vertical = 5.dp),
                )
            }
        }

        // Settings lives on this strip rather than a row of its own: scheme and
        // server are not per-session choices, and vertical space is the scarce
        // thing on a phone.
        Text(
            text = "⚙",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier
                .padding(start = 6.dp)
                .clip(CircleShape)
                .clickable(onClick = onOpenSettings)
                .semantics { contentDescription = "Settings" }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ChannelTab(
    ui: ChannelUi,
    selected: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onShowRoster: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) colors.primary else colors.surfaceVariant)
            .clickable(onClick = onSelect)
            .padding(start = 12.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // A dot rather than words: the tab strip is too tight for status text,
        // but "this channel is reconnecting" still has to be visible.
        StatusDot(ui.state, selected)

        Text(
            text = "?${ui.channel}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) colors.onPrimary else colors.onSurface,
        )

        if (ui.unread > 0 && !selected) {
            Text(
                text = if (ui.unread > 99) "99+" else ui.unread.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onPrimary,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(colors.primary)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }

        // How many people are here, per tab — and the way into the roster, now
        // that there is no status row to hang it off. Zero means the handshake
        // has not landed yet, so there is nothing to open.
        if (ui.roster.isNotEmpty()) {
            Text(
                text = ui.roster.size.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = (if (selected) colors.onPrimary else colors.onSurface).copy(alpha = 0.75f),
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable {
                        onSelect()
                        onShowRoster()
                    }
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }

        Text(
            text = "×",
            style = MaterialTheme.typography.bodyMedium,
            color = (if (selected) colors.onPrimary else colors.onSurface).copy(alpha = 0.6f),
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onClose)
                .padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun StatusDot(state: SessionState, selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    val color = when (state) {
        is SessionState.Live -> if (selected) colors.onPrimary else colors.primary
        is SessionState.Failed -> colors.error
        is SessionState.Reconnecting -> colors.error.copy(alpha = 0.7f)
        else -> colors.onSurfaceVariant.copy(alpha = 0.5f)
    }
    Box(Modifier.size(7.dp).clip(CircleShape).background(color))
}
