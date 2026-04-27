package org.bidon.rendering
data class RendererConfig(
    val logLevel: LogLevel = LogLevel.INFO,
    val networkTimeoutMs: Long = 3000,
    val enableDebugging: Boolean = false
)

enum class LogLevel {
    DEBUG, INFO, WARN, ERROR, NONE
}