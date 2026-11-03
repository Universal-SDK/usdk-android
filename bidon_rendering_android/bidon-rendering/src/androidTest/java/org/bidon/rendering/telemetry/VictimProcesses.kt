package org.bidon.rendering.telemetry

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/*
 * Talking to a service that runs in a process of its own, so a test can let that process die and start a new one.
 */

/** Binds to service [S], starting its process if needed, and unbinds once [block] is done. */
internal inline fun <reified S : Service, T> withService(context: Context, noinline block: Messenger.() -> T): T =
    withService(context, Intent(context, S::class.java), block)

internal fun <T> withService(context: Context, intent: Intent, block: Messenger.() -> T): T {
    val connected = CountDownLatch(1)
    var service: Messenger? = null
    val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = Messenger(binder)
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) = Unit
    }
    assertTrue(context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
    try {
        assertTrue("the victim did not start", connected.await(10, TimeUnit.SECONDS))
        return service!!.block()
    } finally {
        context.unbindService(connection)
    }
}

/** Sends command [what], with [data] when given, and waits for the reply. */
internal fun Messenger.request(what: Int, arg1: Int = 0, data: Bundle? = null): Message {
    val replies = LinkedBlockingQueue<Message>()
    val thread = HandlerThread("victim-replies").apply { start() }
    try {
        val replyTo = Messenger(Handler(thread.looper) { replies.add(Message.obtain(it)); true })
        send(Message.obtain(null, what, arg1, 0).apply {
            this.replyTo = replyTo
            if (data != null) this.data = data
        })
        return replies.poll(30, TimeUnit.SECONDS) ?: throw AssertionError("no reply to command $what")
    } finally {
        thread.quitSafely()
    }
}

internal fun awaitDeath(pid: Int) {
    val deadline = System.currentTimeMillis() + 10_000
    while (File("/proc/$pid").exists()) {
        if (System.currentTimeMillis() > deadline) fail("the victim ($pid) is still alive")
        Thread.sleep(20)
    }
}
