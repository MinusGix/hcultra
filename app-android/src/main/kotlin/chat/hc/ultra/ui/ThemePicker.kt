package chat.hc.ultra.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import chat.hc.core.render.NickLayout
import chat.hc.core.render.Scheme
import chat.hc.core.render.Schemes
import chat.hc.core.session.Servers
import chat.hc.ultra.service.ChatNotifications

/**
 * Settings: server endpoint, then theming.
 *
 * Server sits at the top because it is the setting that changes what you are
 * looking at rather than how it looks.
 *
 * Each row previews with the scheme's real colours rather than a name alone —
 * with 44 schemes, names like "atelier-heath" carry no information otherwise.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSheet(
    schemes: List<Scheme>,
    currentServer: String,
    onServerChanged: (String) -> Unit,
    currentScheme: String,
    /** null means "follow the scheme's paired default". */
    highlightOverride: String?,
    autoHighlight: String,
    onSchemeSelected: (String) -> Unit,
    onHighlightSelected: (String?) -> Unit,
    nickLayout: NickLayout,
    onNickLayoutSelected: (NickLayout) -> Unit,
    notifyMentions: Boolean,
    onNotifyMentionsChanged: (Boolean) -> Unit,
    notifyWhispers: Boolean,
    onNotifyWhispersChanged: (Boolean) -> Unit,
    notifyOtherChannels: Boolean,
    onNotifyOtherChannelsChanged: (Boolean) -> Unit,
    /** Opens the system's own settings for one notification channel id. */
    onOpenSystemNotifications: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            ServerSetting(currentServer, onServerChanged)

            Text(
                "Message layout",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 16.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NickLayout.entries.forEach { option ->
                    val selected = option == nickLayout
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable { onNickLayoutSelected(option) }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }

            Text(
                "Notifications",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 16.dp),
            )
            ToggleRow(
                title = "Mentions",
                subtitle = "When someone writes @yournick in a channel you have joined.",
                checked = notifyMentions,
                onChanged = onNotifyMentionsChanged,
                // Straight to the system switches for this exact channel. Sound
                // and vibration are the platform's to own since Android 8, and
                // an in-app copy of them would simply not work.
                onTune = { onOpenSystemNotifications(ChatNotifications.CHANNEL_MENTIONS) },
            )
            ToggleRow(
                title = "Whispers",
                subtitle = "A whisper is addressed to you by definition.",
                checked = notifyWhispers,
                onChanged = onNotifyWhispersChanged,
                onTune = { onOpenSystemNotifications(ChatNotifications.CHANNEL_WHISPERS) },
            )
            ToggleRow(
                title = "Other channels while the app is open",
                subtitle = "The channel you are reading never alerts either way.",
                checked = notifyOtherChannels,
                onChanged = onNotifyOtherChannelsChanged,
            )
            Text(
                "Nothing else alerts — ordinary channel messages never do.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                "Syntax highlighting",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 16.dp),
            )
            LazyRow(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    val selected = highlightOverride == null
                    Text(
                        text = "Auto ($autoHighlight)",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable { onHighlightSelected(null) }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
                items(Schemes.highlightThemes) { theme ->
                    val selected = theme == highlightOverride
                    Text(
                        text = theme,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable { onHighlightSelected(theme) }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }

            Text(
                "Colour scheme",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(schemes, key = { it.name }) { scheme ->
                    SchemeRow(
                        scheme = scheme,
                        selected = scheme.name == currentScheme,
                        onClick = { onSchemeSelected(scheme.name) },
                    )
                }
            }
        }
    }
}

/**
 * A switch with a reason under it, and a way through to the system's own
 * controls for the same thing.
 *
 * The "Sound" link is not decoration: the complaint this whole section answers
 * was that mentions did not buzz, and whether they buzz is a per-channel system
 * setting the app is not allowed to change once the channel exists. A switch
 * here that claimed to control vibration would be a lie; a link that lands on
 * the real one is not.
 */
@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChanged: (Boolean) -> Unit,
    /** Null when the row has no notification channel of its own to tune. */
    onTune: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChanged(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (checked && onTune != null) {
                Text(
                    "Sound & vibration",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onTune)
                        .padding(vertical = 4.dp),
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChanged)
    }
}

@Composable
private fun ServerSetting(current: String, onChanged: (String) -> Unit) {
    var input by remember(current) { mutableStateOf(current) }
    val result = remember(input) { Servers.normalize(input) }
    val valid = result as? Servers.Result.Valid
    val changed = valid != null && valid.url != current

    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Server", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            singleLine = true,
            isError = result is Servers.Result.Invalid,
            supportingText = {
                when {
                    result is Servers.Result.Invalid -> Text(result.reason)
                    valid != null && Servers.isPlaintext(valid.url) ->
                        Text("Unencrypted — fine for a local server, not over the internet")
                    changed -> Text("Reconnects: joined channels will be left")
                    else -> Text(valid?.url ?: "")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = { valid?.let { onChanged(it.url) } },
                enabled = changed,
            ) { Text("Connect") }
            TextButton(
                onClick = { input = Servers.DEFAULT_URL },
                enabled = current != Servers.DEFAULT_URL || input != Servers.DEFAULT_URL,
            ) { Text("Reset to hack.chat") }
        }
    }
}

@Composable
private fun SchemeRow(scheme: Scheme, selected: Boolean, onClick: () -> Unit) {
    val bg = parse(scheme.background, Color(0xFF151515))
    val fg = parse(scheme.foreground, Color.White)
    val nick = parse(scheme.nick, fg)

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(nick))
        // Rendered in the scheme's own colours, so the row is the preview.
        Text(
            text = scheme.label,
            color = fg,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (scheme.dark) "dark" else "light",
            color = fg.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

private fun parse(hex: String, fallback: Color): Color = runCatching {
    Color(android.graphics.Color.parseColor(hex))
}.getOrDefault(fallback)
