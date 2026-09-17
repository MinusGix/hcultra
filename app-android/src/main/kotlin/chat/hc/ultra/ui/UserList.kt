package chat.hc.ultra.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.hc.core.protocol.Levels
import chat.hc.core.protocol.User
import chat.hc.core.session.ModAction

/**
 * Channel roster.
 *
 * Tapping a user inserts an `@nick` mention — the one action common enough to
 * deserve the whole row and no confirmation. Everything else is behind a
 * long-press: whisper, invite, and whichever moderation actions this user may
 * actually take against that one.
 *
 * Long-press used to *be* whisper, on the grounds that two actions were not
 * worth a menu. Three are. Inviting is available against everyone, so as a row
 * label it would have repeated down every line in the channel saying nothing —
 * unlike the moderation labels, which are gated and therefore rare. Folding
 * those into the same menu is what keeps the rows readable, and it matches the
 * site, whose own userlist opens a menu per person.
 */
@Composable
fun UserList(
    users: List<User>,
    onMention: (String) -> Unit,
    onWhisper: (String) -> Unit,
    modifier: Modifier = Modifier,
    onInvite: (User) -> Unit = {},
    /** Actions this user may take against a target; empty for ordinary users. */
    modActionsFor: (User) -> List<ModAction> = { emptyList() },
    onModerate: (ModAction, User) -> Unit = { _, _ -> },
) {
    var confirming by remember { mutableStateOf<Pair<ModAction, User>?>(null) }

    confirming?.let { (action, target) ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("${action.label} ${target.nick}?") },
            text = { Text(describe(action, target.nick)) },
            confirmButton = {
                TextButton(onClick = {
                    onModerate(action, target)
                    confirming = null
                }) { Text(action.label) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text("Cancel") }
            },
        )
    }

    // Server order, untouched: whoever `onlineSet` listed, then arrivals as they
    // arrived — the same order the site's list is in, and the same order the
    // "Users online" line in the transcript gives. Sorting by level and name
    // read as tidier and was worse: it put people somewhere other than where
    // both of the other two places had just shown them, for a rank that the
    // colours already say. Position here means "has been here longest", which is
    // at least something the list is in a position to know.

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp),
    ) {
        Text(
            text = "${users.size} online",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        LazyColumn(
            modifier = Modifier.heightIn(max = 220.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(users, key = { it.userid }) { user ->
                UserRow(
                    user = user,
                    onClick = { onMention(user.nick) },
                    onWhisper = { onWhisper(user.nick) },
                    onInvite = { onInvite(user) },
                    modActions = modActionsFor(user),
                    onModAction = { action ->
                        // Destructive actions confirm; Unmuzzle is trivially
                        // reversible and does not need a dialog in the way.
                        if (action.destructive) confirming = action to user
                        else onModerate(action, user)
                    },
                )
            }
        }
    }
}

private fun describe(action: ModAction, nick: String): String = when (action) {
    ModAction.Kick -> "$nick is removed from the channel and can rejoin immediately."
    ModAction.Ban -> "Blocks $nick's connection. This outlasts them leaving the channel."
    ModAction.Muzzle -> "$nick's messages are silently dropped. They are not told."
    ModAction.Unmuzzle -> "$nick can be heard again."
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserRow(
    user: User,
    onClick: () -> Unit,
    onWhisper: () -> Unit,
    onInvite: () -> Unit,
    modActions: List<ModAction>,
    onModAction: (ModAction) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    // The server sends an explicit per-user colour; fall back to the theme.
    val nickColor = user.color
        ?.let { runCatching { Color(android.graphics.Color.parseColor("#$it")) }.getOrNull() }
        ?: colors.onSurface

    var menuOpen by remember { mutableStateOf(false) }

    // Nothing worth offering against yourself. Whispering and inviting yourself
    // are both legal server-side and both pointless, and `Moderation.available`
    // refuses a self-target outright — so the menu would open with nothing in
    // it. A long-press that does nothing beats a menu that says nothing.
    val hasMenu = !user.isme

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                // Tap mentions. It is by far the most common thing to want from
                // a roster, so it keeps the whole row and costs one tap; the
                // rarer actions are a long-press away in the menu below.
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { if (hasMenu) menuOpen = true },
                )
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // The server's own decoration, shown verbatim — `forceflair` allows any
            // string of up to two characters, so this is not a fixed icon set. It is
            // also the fastest read on who can actually moderate.
            user.flair?.takeIf { it.isNotBlank() }?.let {
                Text(text = it, style = MaterialTheme.typography.labelMedium)
            }
            badgeFor(user)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = user.nick,
                style = MaterialTheme.typography.bodyMedium,
                color = nickColor,
                fontWeight = if (user.isme) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )
            user.trip?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            if (user.isme) {
                Text(
                    text = "you",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Whisper") },
                onClick = {
                    menuOpen = false
                    onWhisper()
                },
            )
            // Says where, because "Invite" alone does not: the server invents a
            // fresh channel for the two of you rather than pointing at this one,
            // and somebody expecting the latter would be surprised by the line
            // that comes back naming somewhere they have never been.
            DropdownMenuItem(
                text = { Text("Invite to a new channel") },
                onClick = {
                    menuOpen = false
                    onInvite()
                },
            )
            // Gated, so usually none of these. Destructive ones still route
            // through the confirm dialog, which is where the target is named —
            // the menu items themselves stay terse.
            modActions.forEach { action ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = action.label,
                            color = if (action.destructive) colors.error else colors.primary,
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onModAction(action)
                    },
                )
            }
        }
    }
}

private fun badgeFor(user: User): String? = Levels.badge(user.level)
    ?: "bot".takeIf { user.isBot }
