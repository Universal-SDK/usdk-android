package org.bidon.rendering.telemetry.catalogue

/** Files the catalogue tests read from the unit-test resources. */
internal object CatalogueTestSupport {

    private const val VENDORED_PROTO = "telemetry/catalogue/events.proto"

    /** The text of the classpath resource [path], or null when there is none. */
    fun resourceText(path: String): String? =
        javaClass.classLoader!!.getResourceAsStream(path)?.use { it.readBytes().toString(Charsets.UTF_8) }

    fun vendoredProtoBytes(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(VENDORED_PROTO)!!.use { it.readBytes() }

    /** The backend's catalogue, as vendored next to the tests. */
    fun vendoredCatalogue(): ProtoCatalogue = ProtoCatalogue.parse(vendoredProtoBytes().toString(Charsets.UTF_8))
}
