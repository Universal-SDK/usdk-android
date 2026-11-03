# Telemetry reports exception class names, which must still read as ours in a minified app.
-keepnames class org.bidon.rendering.** extends java.lang.Throwable
