package chat.hc.ultra.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
 * Tapping a user inserts an `@nick` mention; long-pressing starts a `/w`.
 * Those are the two reasons to reach for a roster on a phone, and neither is
 * worth a menu.
 */
@Composable
fun UserList(
    users: List<User>,
    onMention: (String) -> Unit,
    onWhisper: (String) -> Unit,
    modifier: Modifier = Modifier,
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

    // Moderators and admins first, then alphabetically — the same ordering the
    // level colours imply, so the list does not reshuffle as people talk.
    val sorted = users.sortedWith(
        compareByDescending<User> { it.level }.thenBy { it.nick.lowercase() }
    )

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
            items(sorted, key = { it.userid }) { user ->
                UserRow(
                    user = user,
                    onClick = { onMention(user.nick) },
                    onLongClick = { onWhisper(user.nick) },
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
    onLongClick: () -> Unit,
    modActions: List<ModAction>,
    onModAction: (ModAction) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    // The server sends an explicit per-user colour; fall back to the theme.
    val nickColor = user.color
        ?.let { runCatching { Color(android.graphics.Color.parseColor("#$it")) }.getOrNull() }
        ?: colors.onSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            // Tap mentions, long-press starts a whisper — the two things you
            // actually want a roster for on a phone.
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
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

        modActions.forEach { action ->
            Text(
                text = action.label,
                style = MaterialTheme.typography.labelSmall,
                color = if (action.destructive) colors.error else colors.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onModAction(action) }
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}

private fun badgeFor(user: User): String? = Levels.badge(user.level)
    ?: "bot".takeIf { user.isBot }
