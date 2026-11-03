package org.bidon.rendering.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** A host screen that holds back the screen it is asked to start, so a test can do something first and start it. */
class HoldingActivity : Activity() {

    /** The last screen this one was asked to start. */
    var held: Intent? = null
        private set

    override fun startActivity(intent: Intent?) {
        held = intent
    }

    override fun startActivity(intent: Intent?, options: Bundle?) {
        held = intent
    }
}
