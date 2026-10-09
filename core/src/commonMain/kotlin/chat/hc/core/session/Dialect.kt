package chat.hc.core.session

/**
 * Which protocol to speak to a server.
 *
 * The first frame on a socket picks the dialect for its whole life (see
 * probe/FINDINGS.md §1), so this decides how every connection opens:
 *
 *  - [V2] sends `session` first, as hack.chat wants.
 *  - [V1] sends `join` first, for servers that predate `session`. Today's
 *    hack.chat understands it too, but as the 2015-era protocol it keeps for
 *    old clients — so it is a setting for old servers, not a fallback.
 *  - [Auto] sends `session` and listens. A server that predates it drops every
 *    command but `join` and `chat` until you have joined (2.1.91's
 *    `MainServer#handleData`), so silence is the answer; `join` then follows on
 *    the same socket. That is safe whichever server it turns out to be,
 *    because frames are handled in order: a server that knows `session` has
 *    already made the socket v2 by the time it reads the `join`.
 */
enum class Dialect { Auto, V2, V1 }
