package chat.hc.ultra.data

import chat.hc.core.render.ImageHosts

/**
 * The image check every native gate uses: the site's [ImageHosts] plus the
 * URL prefixes the user added in settings.
 *
 * Process-wide rather than threaded through, because its readers are not
 * composables — the WebView's request interceptor runs on a WebView thread, and
 * the viewer's fetch on an IO one — and they must all agree. [ThemePrefs] keeps
 * it in step: it is loaded from there at startup and written through on change,
 * before the renderer is told, so the gate is never behind the page.
 */
object ImageSources {

    @Volatile
    var extra: List<String> = ImageHosts.defaultExtra

    fun allows(url: String): Boolean = ImageHosts.allows(url, extra)
}
