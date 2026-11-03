package org.bidon.rendering.telemetry

import org.json.JSONObject

// keySet() is only in the org.json library. Since Kotlin 2.3 the unit-test compile sees android.jar's JSONObject,
// which has keys() but not keySet().
internal fun JSONObject.keyNames(): Set<String> = keys().asSequence().toSet()
