package chat.hc.ultra.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/*
 * The app's few icons, as path data from Material's icon set.
 *
 * Drawn here rather than pulling in the icons artifact for a handful of glyphs.
 * Filled black; `Icon` tints them, so the fill colour never shows.
 */
private fun icon(name: String, path: String) = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black)).build()

/** Material `arrow_back`. */
internal val BackArrow = icon(
    "BackArrow",
    "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z",
)

/** Material `open_in_new`. */
internal val OpenInNew = icon(
    "OpenInNew",
    "M19,19H5V5h7V3H5c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2v-7h-2v7z" +
        "M14,3v2h3.59l-9.83,9.83 1.41,1.41L19,6.41V10h2V3h-7z",
)

/** Material `arrow_downward`. */
internal val ArrowDown = icon(
    "ArrowDown",
    "M20,12l-1.41,-1.41L13,16.17V4h-2v12.17l-5.58,-5.59L4,12l8,8 8,-8z",
)
