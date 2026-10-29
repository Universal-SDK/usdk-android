package org.bidon.rendering.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle

/** A host screen that refuses to start any other screen, the way a device with the screen disabled would. */
class RefusingActivity : Activity() {

    override fun startActivity(intent: Intent?) {
        throw ActivityNotFoundException("refused: $intent")
    }

    override fun startActivity(intent: Intent?, options: Bundle?) {
        throw ActivityNotFoundException("refused: $intent")
    }
}
