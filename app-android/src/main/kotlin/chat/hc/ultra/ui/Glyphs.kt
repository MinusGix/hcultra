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

/** Material `content_copy`. */
internal val CopyIcon = icon(
    "Copy",
    "M16,1H4C2.9,1 2,1.9 2,3v14h2V3h12V1zM19,5H8C6.9,5 6,5.9 6,7v14c0,1.1 0.9,2 2,2h11" +
        "c1.1,0 2,-0.9 2,-2V7C21,5.9 20.1,5 19,5zM19,21H8V7h11V21z",
)

/** Material `file_download`. */
internal val DownloadIcon = icon(
    "Download",
    "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z",
)

/** Material `share`. */
internal val ShareIcon = icon(
    "Share",
    "M18,16.08c-0.76,0 -1.44,0.3 -1.96,0.77L8.91,12.7c0.05,-0.23 0.09,-0.46 0.09,-0.7" +
        "s-0.04,-0.47 -0.09,-0.7l7.05,-4.11c0.54,0.5 1.25,0.81 2.04,0.81 1.66,0 3,-1.34 3,-3" +
        "s-1.34,-3 -3,-3 -3,1.34 -3,3c0,0.24 0.04,0.47 0.09,0.7L8.04,9.81C7.5,9.31 6.79,9 6,9" +
        "c-1.66,0 -3,1.34 -3,3s1.34,3 3,3c0.79,0 1.5,-0.31 2.04,-0.81l7.12,4.16" +
        "c-0.05,0.21 -0.08,0.43 -0.08,0.65 0,1.61 1.31,2.92 2.92,2.92 1.61,0 2.92,-1.31 2.92,-2.92" +
        "s-1.31,-2.92 -2.92,-2.92z",
)
