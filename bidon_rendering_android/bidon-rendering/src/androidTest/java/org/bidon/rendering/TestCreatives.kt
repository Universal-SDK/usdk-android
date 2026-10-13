package org.bidon.rendering

/** Links and markup the click tests share. */
object TestCreatives {
    /** Opens an exported screen of the test app that accepts links, as an app that takes links from the web does. */
    const val APP_LINK = "bidontest-open://x"
    const val OTHER_APP_LINK = "bidontest-open://y"

    /** The host's own deep link screen, which is not exported; a web page; an exported screen that takes links. */
    val LINKS = listOf("bidontest://screen", "https://advertiser.example/landing", APP_LINK)

    /** The [LINKS] a creative may open. */
    val OPENED = LINKS.drop(1)

    /** Stretches the creative's element with the id `ad` over the whole ad, so a tap in the middle lands on it. */
    const val FILL = "<style>html, body { margin: 0; height: 100% } #ad { display: block; height: 100% }</style>"

    /** How long a creative is given to act, once it has loaded and again after a tap. */
    const val SETTLE_MILLIS = 1_000L
}
