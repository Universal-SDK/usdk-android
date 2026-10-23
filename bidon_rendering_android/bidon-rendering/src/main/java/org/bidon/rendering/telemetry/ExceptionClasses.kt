package org.bidon.rendering.telemetry

import java.util.Collections
import java.util.IdentityHashMap

/**
 * The class names a failure event carries for an exception, never its message: the exception's own class and the
 * class of its root cause, the latter only when it differs. A name that is not a Java class name is left out.
 */
internal class ExceptionClasses private constructor(val exceptionClass: String?, val rootCauseClass: String?) {

    companion object {

        private const val MAX_CAUSES = 32

        /** The class names of [throwable]. Never throws, whatever its causes do. */
        fun of(throwable: Throwable): ExceptionClasses {
            val exceptionClass = sendableClassName(throwable.javaClass.name)
            val rootCauseClass = sendableClassName(rootCauseOf(throwable).javaClass.name)
            return ExceptionClasses(exceptionClass, rootCauseClass.takeIf { it != exceptionClass })
        }

        /**
         * The last throwable of [throwable]'s cause chain, at most [MAX_CAUSES] causes down. The walk stops before a
         * throwable it has already met, and at one whose `getCause()` throws.
         */
        private fun rootCauseOf(throwable: Throwable): Throwable {
            val met = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            var current = throwable
            repeat(MAX_CAUSES) {
                met += current
                val next = runCatching { current.cause }.getOrNull()
                if (next == null || next in met) return current
                current = next
            }
            return current
        }
    }
}

private const val MAX_CLASS_NAME_LENGTH = 256
private val CLASS_NAME = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")

/** [name] when it is a Java class name of at most 256 characters, the only form a class name is sent in. */
internal fun sendableClassName(name: String): String? =
    name.takeIf { it.length <= MAX_CLASS_NAME_LENGTH && CLASS_NAME.matches(it) }
