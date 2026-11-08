package com.music.bitchord.ui.player

/**
 * What the Spotify light beside the like button is allowed to say.
 *
 * Four states rather than a boolean because "off" and "broken" look the same
 * on screen (grey) but mean different things to whoever is reading the light,
 * and because a host with no Spotify account at all (the desktop app) should
 * not grow a grey icon it can never make white.
 *
 * The light reports; it does not act. A tap that did nothing would be worse
 * than no tap, so the reason behind a grey light is left where it already is:
 * the track log, which carries the HTTP code or the failed match.
 */
enum class SpotifySyncState {
    /** No light: this host has no Spotify account to report on. */
    Hidden,

    /** Grey: the account is not connected, or reporting is switched off. */
    Off,

    /** White: the last track the player started was accepted by Spotify. */
    Connected,

    /**
     * Grey: connected, but the last attempt failed - no match in the
     * catalogue, a refused state, or an expired token. The track log says
     * which.
     */
    Error,
}
