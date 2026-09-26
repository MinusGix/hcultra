package chat.hc.ultra.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.hc.core.render.FontScale
import chat.hc.core.render.ImageHosts
import chat.hc.core.render.NickLayout
import chat.hc.core.render.Scheme
import chat.hc.core.render.Schemes
import chat.hc.core.session.Servers
import chat.hc.core.translate.GoogleTranslate
import chat.hc.ultra.data.REPO_URL
import chat.hc.ultra.data.Translator
import chat.hc.ultra.data.UpdateStatus
import chat.hc.ultra.data.checkForUpdate
import chat.hc.ultra.data.installedVersion
import chat.hc.ultra.service.ChatNotifications
import kotlinx.coroutines.launch
import java.util.Locale

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
    /** Transcript text size, as a multiplier; always a rung of [FontScale.STEPS]. */
    fontScale: Float,
    onFontScaleChanged: (Float) -> Unit,
    allowImages: Boolean,
    onAllowImagesChanged: (Boolean) -> Unit,
    /** The user's own image sources, as canonical URL prefixes. */
    extraImageSources: List<String>,
    onExtraImageSourcesChanged: (List<String>) -> Unit,
    joinLeave: Boolean,
    onJoinLeaveChanged: (Boolean) -> Unit,
    translate: Boolean,
    onTranslateChanged: (Boolean) -> Unit,
    /** One of [GoogleTranslate.LANGUAGES], or null for the phone's language. */
    translateTarget: String?,
    onTranslateTargetChanged: (String?) -> Unit,
    notifyMentions: Boolean,
    onNotifyMentionsChanged: (Boolean) -> Unit,
    notifyWhispers: Boolean,
    onNotifyWhispersChanged: (Boolean) -> Unit,
    notifyInvites: Boolean,
    onNotifyInvitesChanged: (Boolean) -> Unit,
    notifyOtherChannels: Boolean,
    onNotifyOtherChannelsChanged: (Boolean) -> Unit,
    /** Opens the system's own settings for one notification channel id. */
    onOpenSystemNotifications: (String) -> Unit,
    /** Hands a URL to the browser, through the same http/https check links use. */
    onOpenLink: (String) -> Unit,
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

            FontSizeSetting(fontScale, onFontScaleChanged)

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
                "Transcript",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 16.dp),
            )
            ToggleRow(
                title = "Show joins and leaves",
                // Why it is worth more than the site's version of the same
                // switch: there, hiding them only tidies the view. Here they
                // occupy the buffer that holds the only copy of the
                // conversation, so a busy channel spends it on arrivals.
                subtitle = "The site's \"Join/left notify\". Off, a busy channel keeps more " +
                    "of what was actually said — nothing here is re-fetchable. The user " +
                    "list still tracks who is present.",
                checked = joinLeave,
                onChanged = onJoinLeaveChanged,
            )
            ToggleRow(
                title = "Show images in the transcript",
                // The honest cost, since that is what the choice is about: an
                // embedded image is a request to somebody else's server, made
                // because a stranger in the channel wrote the URL.
                subtitle = "From the same few hosts the site allows — imgur, Discord, " +
                    "gyazo, postimg, ibb, ytimg, catbox, irys. Loading one tells that host you are here; " +
                    "anything else stays a link either way.",
                checked = allowImages,
                onChanged = onAllowImagesChanged,
            )
            if (allowImages) {
                ExtraImageSources(extraImageSources, onExtraImageSourcesChanged)
            }
            ToggleRow(
                title = "Translate messages",
                // Where the text goes, since that is what switching it on
                // agrees to; there is no second question on the first tap.
                subtitle = "Adds Translate to a tapped message. " +
                    "The message's text is sent to Google Translate — not who wrote it " +
                    "or which channel it is from.",
                checked = translate,
                onChanged = onTranslateChanged,
            )
            if (translate) {
                TranslateTarget(translateTarget, onTranslateTargetChanged)
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
                title = "Invites",
                subtitle = "Someone asking you into another channel.",
                checked = notifyInvites,
                onChanged = onNotifyInvitesChanged,
                onTune = { onOpenSystemNotifications(ChatNotifications.CHANNEL_INVITES) },
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

            About(onOpenLink = onOpenLink)
        }
    }
}

/**
 * Which version this is, where it came from, and whether there is a newer one.
 *
 * At the bottom because it is the section you go looking for rather than the
 * one you pass through. The update check runs when it is pressed and at no
 * other time — see [checkForUpdate].
 */
@Composable
private fun About(onOpenLink: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version = remember(context) { installedVersion(context) }
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<UpdateStatus?>(null) }

    Text(
        "About",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 20.dp),
    )
    Text(
        "hcultra $version",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 4.dp),
    )
    Text(
        "An unofficial client. hack.chat is by Andrew Belt and contributors.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Row(
        Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            enabled = !checking,
            onClick = {
                checking = true
                status = null
                scope.launch {
                    status = checkForUpdate(context)
                    checking = false
                }
            },
        ) { Text(if (checking) "Checking…" else "Check for updates") }

        TextButton(onClick = { onOpenLink(REPO_URL) }) { Text("Source on GitHub") }
    }

    // Nothing at all until the button has been pressed: a blank line here says
    // "not checked", which is the truth and is also the resting state.
    when (val result = status) {
        null -> Unit

        UpdateStatus.UpToDate -> Text(
            "Up to date.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        is UpdateStatus.Available -> Column {
            Text(
                "${result.release.tag} is available.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            // No in-app download: an APK this app fetched and handed to the
            // installer would be an update path with no signature check of its
            // own in front of it. The release page is where the attested build
            // is, and Android does the verifying from there.
            TextButton(
                onClick = { onOpenLink(result.release.url) },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
            ) { Text("Open the release page") }
        }

        is UpdateStatus.Failed -> Text(
            result.reason + ".",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    Text(
        "Checking asks GitHub for the latest release, and sends nothing else. " +
            "It happens only when you press the button.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
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

/**
 * Which language Translate goes into: the phone's, unless the reader picks
 * another.
 *
 * Each language is listed under its own name first, with the phone's name for
 * it beneath: the reader choosing is, by the premise of the setting, someone
 * more at home in a language other than the phone's, and should be able to
 * find it by the name they know it by. The search matches either.
 */
@Composable
private fun TranslateTarget(target: String?, onChanged: (String?) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val phone = remember { Translator.phoneTarget() }
    val reader = remember { Locale.getDefault() }
    val current = target?.let { Translator.languageName(it) }
        ?: "Phone language (${Translator.languageName(phone, reader)})"

    Row(
        Modifier
            .fillMaxWidth()
            .clickable { picking = true }
            .padding(start = 12.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Translate into", style = MaterialTheme.typography.bodyMedium)
            Text(
                current,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    if (!picking) return
    // Named once per opening rather than per keystroke: 130 locale lookups.
    val languages = remember {
        GoogleTranslate.LANGUAGES
            .map { Triple(it, Translator.languageName(it), Translator.languageName(it, reader)) }
            .sortedBy { it.third.lowercase(reader) }
    }
    var query by remember { mutableStateOf("") }
    val shown = remember(query) {
        val q = query.trim()
        if (q.isEmpty()) languages
        else languages.filter { (code, own, local) ->
            own.contains(q, ignoreCase = true) || local.contains(q, ignoreCase = true) ||
                code.equals(q, ignoreCase = true)
        }
    }
    val choose = { code: String? ->
        onChanged(code)
        picking = false
    }
    AlertDialog(
        onDismissRequest = { picking = false },
        title = { Text("Translate into") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Search") },
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 8.dp)) {
                    if (query.isBlank()) {
                        item {
                            LanguageRow(
                                title = "Phone language",
                                subtitle = Translator.languageName(phone, reader),
                                selected = target == null,
                                onClick = { choose(null) },
                            )
                        }
                    }
                    items(shown, key = { it.first }) { (code, own, local) ->
                        LanguageRow(
                            title = own,
                            // Nothing to add when the two names are the same,
                            // as they are for the phone's own language.
                            subtitle = local.takeIf { it != own },
                            selected = code == target,
                            onClick = { choose(code) },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
    )
}

@Composable
private fun LanguageRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else null,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * How big the transcript's text is: two buttons, the size they are moving, and
 * a line of chat at that size.
 *
 * A step pair rather than a slider. The values worth having are a short ladder
 * ([FontScale.STEPS]) and a slider over eight rungs is a worse way to pick one
 * of eight things — harder to hit, harder to hit again, and impossible to
 * operate by voice or switch access. Two buttons are a target each.
 *
 * The sample matters more here than it looks. The sheet covers the transcript
 * it is resizing, so without a preview the only way to see the effect of a tap
 * is to dismiss the sheet, look, and open it again for every rung.
 */
@Composable
private fun FontSizeSetting(scale: Float, onChanged: (Float) -> Unit) {
    Text(
        "Text size",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 16.dp),
    )
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton(
            glyph = "A-",
            label = "Smaller text",
            enabled = FontScale.canShrink(scale),
            onClick = { onChanged(FontScale.smaller(scale)) },
        )
        StepButton(
            glyph = "A+",
            label = "Larger text",
            enabled = FontScale.canGrow(scale),
            onClick = { onChanged(FontScale.larger(scale)) },
        )
        Text(
            text = FontScale.percentLabel(scale),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp)
                // The buttons change this number and nothing else the screen
                // reader would announce, so without a live region a blind user
                // pressing "Larger text" is told nothing at all.
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = "Text size ${FontScale.percentLabel(scale)}"
                },
        )
        TextButton(
            onClick = { onChanged(FontScale.DEFAULT) },
            enabled = scale != FontScale.DEFAULT,
        ) { Text("Reset") }
    }
    FontSizeSample(scale)
}

/**
 * One button of the pair.
 *
 * Sized rather than left to wrap its glyph: a two-character button is a small
 * target, and 48dp is the floor below which a control stops being reliably
 * hittable. `sizeIn` and not `size`, so the button still grows if the system
 * font scale makes the glyph itself larger than the minimum.
 */
@Composable
private fun StepButton(
    glyph: String,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint =
        if (enabled) MaterialTheme.colorScheme.onSurface
        // Disabled, not absent: the pair keeps its shape at both ends of the
        // ladder, so the button that still works does not move under the finger
        // that was about to press it.
        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)

    Box(
        Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            // Role, so a screen reader says "button" rather than reading a
            // stray "A-" and leaving the user to guess what it is.
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

/**
 * A line of chat at the chosen size.
 *
 * Deliberately shaped like a message — a nick in the scheme's accent, then
 * text — because the question being answered is "can I read the transcript",
 * not "how big is 130%". The 15sp base is the renderer stylesheet's own
 * `--hc-base`, so the sample and the transcript step together.
 */
@Composable
private fun FontSizeSample(scale: Float) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "hcultra",
            fontSize = (BASE_TEXT_SP * scale).sp,
            lineHeight = (BASE_TEXT_SP * scale * 1.45f).sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "this is how the transcript will read",
            fontSize = (BASE_TEXT_SP * scale).sp,
            lineHeight = (BASE_TEXT_SP * scale * 1.45f).sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * The image sources the user trusts beyond the site's list: one row per URL
 * prefix with a way to remove it, and a field to add another.
 *
 * Prefixes rather than hosts, so a server that hosts one folder of pictures
 * can be allowed without allowing everything else it serves.
 */
@Composable
private fun ExtraImageSources(sources: List<String>, onChanged: (List<String>) -> Unit) {
    var input by remember { mutableStateOf("") }
    val normalized = remember(input) { ImageHosts.normalizePrefix(input) }
    val duplicate = normalized != null && normalized in sources

    Column(Modifier.padding(start = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Also allow images from", style = MaterialTheme.typography.bodyMedium)
        if (sources.isEmpty()) {
            Text(
                "Nothing beyond the site's hosts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        sources.forEach { source ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    source,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onChanged(sources - source) }) { Text("Remove") }
            }
        }
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            singleLine = true,
            placeholder = { Text("https://example.com/images/") },
            isError = input.isNotBlank() && normalized == null,
            supportingText = {
                when {
                    input.isBlank() ->
                        Text("A URL prefix: images whose address starts with it will embed.")
                    normalized == null -> Text("Needs to be an https address, like example.com/images/")
                    duplicate -> Text("Already allowed")
                    else -> Text(normalized)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = {
                    normalized?.let { onChanged(sources + it) }
                    input = ""
                },
                enabled = normalized != null && !duplicate,
            ) { Text("Add") }
            TextButton(
                onClick = { onChanged(ImageHosts.defaultExtra) },
                enabled = sources != ImageHosts.defaultExtra,
            ) { Text("Reset") }
        }
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
