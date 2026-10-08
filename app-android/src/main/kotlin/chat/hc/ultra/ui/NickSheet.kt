package chat.hc.ultra.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import chat.hc.core.protocol.User
import chat.hc.core.session.ModAction
import chat.hc.core.store.MessageKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * What holding a nick in the transcript opens: the roster's long-press menu,
 * for whoever the nick names, headed by when that message was sent.
 *
 * The time lives here and nowhere else on purpose. A timestamp on every line
 * is noise for the common case of reading along live, and the question "when
 * was this said" is asked of one message at a time.
 *
 * A sheet rather than a dropdown: the nick is inside the WebView, so there is
 * no Compose node to anchor a dropdown to.
 *
 * [online] is the roster entry the nick resolves to, if they are still here.
 * Whisper and invite need someone to receive them, and moderation must target
 * the very connection that sent the message, so all three go when it is null.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NickSheet(
    hold: NickHold,
    /** The held nick is your own; nothing to offer but the time and a copy. */
    isSelf: Boolean,
    online: User?,
    modActions: List<ModAction>,
    ignored: Boolean,
    onMention: () -> Unit,
    onWhisper: () -> Unit,
    onInvite: () -> Unit,
    onCopy: (String) -> Unit,
    onToggleIgnore: () -> Unit,
    onModerate: (ModAction) -> Unit,
    onDismiss: () -> Unit,
) {
    // Every item closes the sheet first; the action then acts on a screen
    // that is not underneath a sheet.
    fun then(action: () -> Unit): () -> Unit = {
        onDismiss()
        action()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp)) {
                Text(
                    text = listOfNotNull(hold.trip, hold.nick).joinToString(" "),
                    style = MaterialTheme.typography.titleMedium,
                )
                sentLine(hold)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()

            if (!isSelf) Item("Mention", then(onMention))
            if (!isSelf && online != null) {
                Item("Whisper", then(onWhisper))
                // Says where, for the reason the roster's item does.
                Item("Invite to a new channel", then(onInvite))
            }
            Item("Copy nick", then { onCopy(hold.nick) })
            hold.trip?.let { trip -> Item("Copy trip", then { onCopy(trip) }) }
            if (!isSelf) Item(if (ignored) "Stop ignoring" else "Ignore", then(onToggleIgnore))
            modActions.forEach { action ->
                Item(
                    label = action.label,
                    color = if (action.destructive) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    onClick = then { onModerate(action) },
                )
            }
        }
    }
}

@Composable
private fun Item(label: String, onClick: () -> Unit, color: Color = Color.Unspecified) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
    )
}

/**
 * "Sent today at 14:32:05", or with the date when it was not today. Seconds,
 * because the usual reason to ask is to put two nearby messages in order.
 * Join and leave say what happened rather than "sent".
 */
private fun sentLine(hold: NickHold): String? {
    if (hold.at <= 0L) return null
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(hold.at).atZone(zone)
    val time = at.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM))
    val day = at.toLocalDate()
    val today = LocalDate.now(zone)
    val whenText = when (day) {
        today -> "today at $time"
        today.minusDays(1) -> "yesterday at $time"
        else -> "${at.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))} at $time"
    }
    val verb = when (hold.kind) {
        MessageKind.Join -> "Joined"
        MessageKind.Leave -> "Left"
        else -> "Sent"
    }
    return "$verb $whenText"
}
