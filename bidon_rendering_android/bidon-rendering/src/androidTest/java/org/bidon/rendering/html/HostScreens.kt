package org.bidon.rendering.html

import android.app.Activity

/** A screen of the host app that opens from its own deep links, but only from inside the app: it is not exported. */
class HostDeepLinkActivity : Activity()

/** A screen any app may start, which views links but never asked to be opened from the web. */
class HostViewActivity : Activity()

/** A screen any app may start, which opens from web links even when they carry no address. */
class HostBrowsableActivity : Activity()

/** A screen of the host app that routes the app's own web links inside it, and is not exported. */
class HostWebActivity : Activity()

/** A screen any app may open from a link, as the screen of an app that accepts links from the web. */
class HostLinkActivity : Activity()
