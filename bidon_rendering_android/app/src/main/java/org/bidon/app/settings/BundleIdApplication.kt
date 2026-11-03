package org.bidon.app.settings

import android.app.Application
import android.content.ComponentCallbacks
import android.content.Context

/**
 * Hands the BidOn SDK a different bundle id than the one the app is actually installed under.
 *
 * The SDK reads the bundle id straight off the context it is initialized with
 * (AppDataSourceImpl.getBundleId() is `context.packageName`) and there is no API to set it, so the
 * only way to change it is to give the SDK a context that answers `getPackageName()` differently.
 *
 * It has to be an Application rather than a plain ContextWrapper: the SDK casts the context to
 * Application in three places (its lifecycle observer, its session tracker, and its DI graph), and
 * a ContextWrapper would blow up on the first cast.
 *
 * Only the SDK ever sees this. The rest of the app keeps using the real Application, so shared
 * preferences and resources are untouched.
 */
class BundleIdApplication(
    private val real: Application,
    private val bundleId: String,
) : Application() {

    init {
        attachBaseContext(real)
    }

    override fun getPackageName(): String = bundleId

    /** So the SDK stores this wrapper rather than unwrapping straight back to the real app. */
    override fun getApplicationContext(): Context = this

    // A fresh Application instance has its own empty callback lists, and the framework only ever
    // dispatches to the real one. Without these the SDK would register listeners that never fire,
    // which quietly kills banner pause/resume and its low-memory handling.

    override fun registerActivityLifecycleCallbacks(callback: ActivityLifecycleCallbacks?) {
        real.registerActivityLifecycleCallbacks(callback)
    }

    override fun unregisterActivityLifecycleCallbacks(callback: ActivityLifecycleCallbacks?) {
        real.unregisterActivityLifecycleCallbacks(callback)
    }

    override fun registerComponentCallbacks(callback: ComponentCallbacks?) {
        real.registerComponentCallbacks(callback)
    }

    override fun unregisterComponentCallbacks(callback: ComponentCallbacks?) {
        real.unregisterComponentCallbacks(callback)
    }
}
