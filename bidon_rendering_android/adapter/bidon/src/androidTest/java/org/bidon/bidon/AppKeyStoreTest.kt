package org.bidon.bidon

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The adapter reads the app key from the preferences the mediation SDK wrote at `initialize()`, on a real device, and
 * a key that is missing, blank or stored as another type is no key.
 */
@RunWith(AndroidJUnit4::class)
class AppKeyStoreTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.getSharedPreferences(AppKeyStore.PREFERENCES, Context.MODE_PRIVATE)
    private val store = AppKeyStore.fromPreferences(context)

    @After
    fun clear() {
        preferences.edit().clear().commit()
    }

    @Test
    fun appKeyFromPreferences() {
        preferences.edit().putString(AppKeyStore.APP_KEY, "publisher-app-key").commit()

        assertEquals("publisher-app-key", store.appKey())
    }

    @Test
    fun noAppKeyAnywhere() {
        preferences.edit().clear().commit()

        assertNull(store.appKey())
    }

    @Test
    fun blankAppKeyIsNoKey() {
        preferences.edit().putString(AppKeyStore.APP_KEY, "  ").commit()

        assertNull(store.appKey())
    }

    @Test
    fun appKeyStoredAsAnotherTypeIsNoKey() {
        preferences.edit().putInt(AppKeyStore.APP_KEY, 42).commit()

        assertNull(store.appKey())
    }
}
