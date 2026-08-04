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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.hc.core.render.Scheme
import chat.hc.core.render.Schemes

/**
 * Theme picker over hack.chat's own schemes and highlight.js themes.
 *
 * Each row previews with the scheme's real colours rather than a name alone —
 * with 44 schemes, names like "atelier-heath" carry no information otherwise.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSheet(
    schemes: List<Scheme>,
    currentScheme: String,
    /** null means "follow the scheme's paired default". */
    highlightOverride: String?,
    autoHighlight: String,
    onSchemeSelected: (String) -> Unit,
    onHighlightSelected: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Syntax highlighting", style = MaterialTheme.typography.titleSmall)
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
