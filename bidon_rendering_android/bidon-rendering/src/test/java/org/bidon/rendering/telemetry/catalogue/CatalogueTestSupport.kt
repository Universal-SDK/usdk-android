package org.bidon.rendering.telemetry.catalogue

/** Files the catalogue tests read from the unit-test resources. */
internal object CatalogueTestSupport {

    private const val VENDORED_PROTO = "telemetry/catalogue/events.proto"

    /** The text of the classpath resource [path], or null when there is none. */
    fun resourceText(path: String): String? = resourceBytes(path)?.toString(Charsets.UTF_8)

    fun vendoredProtoBytes(): ByteArray = resourceBytes(VENDORED_PROTO) ?: error("missing $VENDORED_PROTO")

    private fun resourceBytes(path: String): ByteArray? =
        javaClass.classLoader!!.getResourceAsStream(path)?.use { it.readBytes() }

    /** The backend's catalogue, as vendored next to the tests. */
    fun vendoredCatalogue(): ProtoCatalogue = ProtoCatalogue.parse(vendoredProtoBytes().toString(Charsets.UTF_8))
}
