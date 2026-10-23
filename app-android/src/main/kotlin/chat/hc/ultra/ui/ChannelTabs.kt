package chat.hc.ultra.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import chat.hc.core.session.ChannelKey
import chat.hc.core.session.ChannelUi
import chat.hc.core.session.Servers
import chat.hc.core.session.SessionState
import kotlinx.coroutines.flow.first

/** How many tabs the strip tries to show at once before it gives up and scrolls. */
private const val VisibleTabs = 3
private val TabGap = 6.dp
/** Below this a tab is a dot and two letters, which names nothing. Scroll instead. */
private val MinTab = 56.dp

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
    active: ChannelKey?,
    onSelect: (ChannelKey) -> Unit,
    onClose: (ChannelKey) -> Unit,
    onAdd: () -> Unit,
    /** Tapping the channel you are on — or its online count — opens its roster. */
    onShowRoster: (ChannelKey) -> Unit,
    /** The channel whose roster is open, so its count can show as pressed. */
    rosterOpenFor: ChannelKey?,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Only once there is a second server to tell apart: until then the label
    // would say "hack.chat" on every tab, which is width spent saying nothing.
    val showServer = channels.mapTo(HashSet()) { it.server }.size > 1
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabStrip(
            channels = channels,
            active = active,
            showServer = showServer,
            onSelect = onSelect,
            onClose = onClose,
            onShowRoster = onShowRoster,
            rosterOpenFor = rosterOpenFor,
            modifier = Modifier.weight(1f),
        )

        // Join and settings share one control at the end of the strip. A "+"
        // tab used to sit after the channels, inside the scrolling row, so the
        // third channel pushed it out of sight; pinned beside the menu it would
        // instead cost the width a third tab needs. Joining is occasional, and
        // with no channels open the join form shows by itself anyway.
        Box {
            Text(
                text = "⋮",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier
                    .padding(start = 4.dp)
                    .clip(CircleShape)
                    .clickable(onClick = { menuOpen = true })
                    .semantics { contentDescription = "Join a channel, or settings" }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Join a channel") },
                    onClick = { menuOpen = false; onAdd() },
                )
                DropdownMenuItem(
                    text = { Text("Settings") },
                    onClick = { menuOpen = false; onOpenSettings() },
                )
            }
        }
    }
}

/**
 * The tabs themselves: natural width while they fit, shrunk to fit three when
 * they do not, and scrolling past that.
 *
 * A [LazyRow] cannot do the middle part — it measures each item alone, and a
 * width that depends on its siblings needs all of them at once. Nobody keeps
 * enough channels open for laziness to matter.
 */
@Composable
private fun TabStrip(
    channels: List<ChannelUi>,
    active: ChannelKey?,
    showServer: Boolean,
    onSelect: (ChannelKey) -> Unit,
    onClose: (ChannelKey) -> Unit,
    onShowRoster: (ChannelKey) -> Unit,
    rosterOpenFor: ChannelKey?,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    // Where each tab sits along the strip, in px, as the last layout placed it.
    // Written from measurement, which is the only place that knows; an equal
    // list does not invalidate, so this settles after one pass.
    var spans by remember { mutableStateOf(emptyList<IntRange>()) }

    BoxWithConstraints(modifier) {
        val viewport = constraints.maxWidth
        val density = LocalDensity.current
        val gap = with(density) { TabGap.roundToPx() }
        val floor = with(density) { MinTab.roundToPx() }

        Layout(
            content = {
                channels.forEach { ui ->
                    key(ui.key) {
                        ChannelTab(
                            ui = ui,
                            showServer = showServer,
                            selected = ui.key == active,
                            onSelect = { onSelect(ui.key) },
                            onClose = { onClose(ui.key) },
                            onShowRoster = { onShowRoster(ui.key) },
                            rosterOpen = ui.key == rosterOpenFor,
                        )
                    }
                }
            },
            modifier = Modifier.horizontalScroll(scroll),
        ) { measurables, constraints ->
            val natural = measurables.map { it.maxIntrinsicWidth(constraints.maxHeight) }
            val widths = fitTabWidths(natural, viewport, gap, floor)
            val placeables = measurables.mapIndexed { i, m ->
                m.measure(Constraints(maxWidth = widths[i], maxHeight = constraints.maxHeight))
            }
            var x = 0
            val placed = placeables.map { p -> (x until x + p.width).also { x += p.width + gap } }
            spans = placed
            val height = placeables.maxOfOrNull { it.height } ?: 0
            layout((x - gap).coerceAtLeast(0), height) {
                placeables.forEachIndexed { i, p -> p.placeRelative(placed[i].first, (height - p.height) / 2) }
            }
        }

        // The tab you are on stays in view. Only when the selection or the set
        // of channels changes, though: following every relayout would snap the
        // strip back each time an unread badge appeared while you were
        // scrolling through it.
        LaunchedEffect(active, channels.size) {
            val index = channels.indexOfFirst { it.key == active }
            if (index < 0) return@LaunchedEffect
            // A tab that has just been joined is not measured yet.
            val span = snapshotFlow { spans }.first { it.size == channels.size }[index]
            val target = when {
                span.first < scroll.value -> span.first
                span.last + 1 > scroll.value + viewport -> span.last + 1 - viewport
                else -> return@LaunchedEffect
            }
            scroll.animateScrollTo(target)
        }

        val edges by remember(channels) {
            derivedStateOf { Edges.of(spans, channels, scroll.value, viewport) }
        }
        val ground = MaterialTheme.colorScheme.background
        Box(Modifier.matchParentSize()) {
            if (edges.moreBefore) EdgeHint(ground, unread = edges.unreadBefore, start = true, Modifier.align(Alignment.CenterStart))
            if (edges.moreAfter) EdgeHint(ground, unread = edges.unreadAfter, start = false, Modifier.align(Alignment.CenterEnd))
        }
    }
}

/**
 * Widths for tabs whose natural widths are [natural], in a strip [viewport]
 * wide.
 *
 * All of them at natural width if that fits. Otherwise the widest are trimmed
 * to a common cap — names ellipsize, and the start of a channel name is usually
 * enough to tell it apart — chosen so that every tab fits when there are no
 * more than [VisibleTabs], and so that about [VisibleTabs] are on screen at a
 * time when there are more. Never below [floor].
 */
internal fun fitTabWidths(natural: List<Int>, viewport: Int, gap: Int, floor: Int): List<Int> {
    val n = natural.size
    if (n == 0 || natural.sum() + gap * (n - 1) <= viewport) return natural
    val shown = minOf(n, VisibleTabs)
    val shareOfShown = (viewport - gap * (shown - 1)) / shown
    val cap = maxOf(waterLevel(natural, viewport - gap * (n - 1)), shareOfShown, floor)
    return natural.map { minOf(it, cap) }
}

/** The largest cap c such that the sum of min(width, c) is within [budget]. */
private fun waterLevel(widths: List<Int>, budget: Int): Int {
    var left = budget
    val sorted = widths.sorted()
    sorted.forEachIndexed { i, w ->
        val remaining = sorted.size - i
        if (w.toLong() * remaining >= left) return left / remaining
        left -= w
    }
    return sorted.last()
}

/** Whether the strip runs past either edge, and whether what is past it has unread. */
private data class Edges(
    val moreBefore: Boolean,
    val moreAfter: Boolean,
    val unreadBefore: Boolean,
    val unreadAfter: Boolean,
) {
    companion object {
        fun of(spans: List<IntRange>, channels: List<ChannelUi>, scrolled: Int, viewport: Int): Edges {
            if (spans.size != channels.size) return Edges(false, false, false, false)
            val end = scrolled + viewport
            // Half out of view counts as unseen: a sliver at the edge shows
            // a badge's colour at best, not that it is a badge.
            fun before(s: IntRange) = (s.first + s.last) / 2 < scrolled
            fun after(s: IntRange) = (s.first + s.last) / 2 > end
            return Edges(
                moreBefore = spans.any { it.first < scrolled },
                moreAfter = spans.any { it.last + 1 > end },
                unreadBefore = spans.indices.any { before(spans[it]) && channels[it].unread > 0 },
                unreadAfter = spans.indices.any { after(spans[it]) && channels[it].unread > 0 },
            )
        }
    }
}

/**
 * A fade where the strip runs on past the edge, so a cut-off tab reads as
 * "there is more" rather than as clipping, and a dot on it when what is out of
 * sight has unread messages.
 */
@Composable
private fun EdgeHint(ground: Color, unread: Boolean, start: Boolean, modifier: Modifier) {
    val fade = if (start) listOf(ground, Color.Transparent) else listOf(Color.Transparent, ground)
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(20.dp)
            .background(Brush.horizontalGradient(fade)),
        contentAlignment = if (start) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        if (unread) {
            Box(
                Modifier
                    .padding(horizontal = 2.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .semantics { contentDescription = "Unread in channels out of view" },
            )
        }
    }
}

/**
 * One channel. The tab you are on carries the controls — roster count and
 * close — and the others carry only what tells you whether to switch: name,
 * unread, and a status dot if something is wrong. Three full tabs did not fit
 * on a phone; to close another channel, switch to it first.
 */
@Composable
private fun ChannelTab(
    ui: ChannelUi,
    showServer: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onShowRoster: () -> Unit,
    rosterOpen: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val onTab = if (selected) colors.onPrimary else colors.onSurface
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) colors.primary else colors.surfaceVariant)
            /*
             * The channel you are already on is the way into its roster.
             *
             * The count beside it did that and only that, and people did not
             * find it: a small number reads as a label, not a control, and there
             * is nothing else on screen suggesting the user list exists. The
             * channel name is the obvious thing to press when you want to know
             * about the channel.
             *
             * Selecting a *different* tab stays a selection and nothing more.
             * Switching channel should not also throw a roster over the
             * conversation you switched to in order to read.
             */
            .clickable(
                onClickLabel = if (selected) "Show who is here" else "Switch to this channel",
                onClick = { if (selected) onShowRoster() else onSelect() },
            )
            // Slimmer than the controls it holds: the roster count carries its
            // own padding now, and doubling up would only make the strip taller.
            .padding(start = 12.dp, end = if (selected) 8.dp else 12.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Only when something is wrong. A live channel is the normal case, and
        // the dot on every tab was width spent saying so; its absence now says it.
        if (ui.state !is SessionState.Live) StatusDot(ui.state)

        // Weighted so it is measured last: when the strip trims this tab, the
        // name gives up the width, not the badge or the close button after it.
        Column(Modifier.weight(1f, fill = false)) {
            Text(
                text = "?${ui.channel}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = onTab,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Under the name rather than beside it: a tab twice as wide shows
            // half as many channels.
            if (showServer) {
                Text(
                    text = Servers.label(ui.server),
                    style = MaterialTheme.typography.labelSmall,
                    color = onTab.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (!selected) {
            if (ui.unread > 0) {
                Text(
                    text = if (ui.unread > 99) "99+" else ui.unread.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onPrimary,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(colors.primary)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        } else {
            // How many people are here. Zero means the handshake has not landed
            // yet, so there is nothing to open.
            if (ui.roster.isNotEmpty()) {
                Text(
                    text = ui.roster.size.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = onTab.copy(alpha = if (rosterOpen) 1f else 0.75f),
                    fontWeight = if (rosterOpen) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(CircleShape)
                        // Held down while the roster is open: this is a toggle, and a
                        // tap that appears to do nothing is indistinguishable from a
                        // tap that missed.
                        .background(if (rosterOpen) onTab.copy(alpha = 0.25f) else Color.Transparent)
                        .clickable(onClick = onShowRoster)
                        // Generous for one or two glyphs. This sits inside the tab's
                        // own clickable, so a near miss falls through to it — which
                        // toggles the roster too, so the miss does what was intended.
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }

            // Held away from the roster count on purpose. Both targets are expanded
            // to the 48dp minimum, and where two expanded targets meet the boundary
            // falls between them — so with the count sitting flush against it, a tap
            // aimed at the count and landing a little right closed the channel.
            // Measured on device: the close boundary is ~16dp from the count's
            // centre without this gap, ~28dp with it.
            Spacer(Modifier.width(10.dp))

            Text(
                text = "×",
                style = MaterialTheme.typography.bodyMedium,
                color = onTab.copy(alpha = 0.6f),
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClickLabel = "Leave ?${ui.channel}", onClick = onClose)
                    .padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun StatusDot(state: SessionState) {
    val colors = MaterialTheme.colorScheme
    val color = when (state) {
        is SessionState.Live -> colors.primary
        is SessionState.Failed -> colors.error
        is SessionState.Reconnecting -> colors.error.copy(alpha = 0.7f)
        // Waiting on the user, not the network — so not an error colour.
        is SessionState.Challenged -> colors.tertiary
        else -> colors.onSurfaceVariant.copy(alpha = 0.5f)
    }
    Box(Modifier.size(7.dp).clip(CircleShape).background(color))
}
