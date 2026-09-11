package org.bidon.rendering

/** Which renderer a creative needs. */
enum class CreativeType {
    HTML,
    MRAID,
    VAST;

    companion object {
        /**
         * The type to render [adm] as.
         *
         * VAST markup is always video, since no other renderer can show it. Otherwise the type the
         * DSP declared wins: the markup alone cannot tell an MRAID creative from HTML that merely
         * mentions mraid.js. With nothing declared, the markup is the only evidence left.
         *
         * A declared VAST that is not VAST markup still goes to the VAST renderer. It fails to
         * parse there and is reported as an error, rather than being shown as a web page.
         */
        fun of(adm: String, rendering: RenderingConfig): CreativeType {
            val declared = rendering.declaredCreativeType
            return when {
                adm.contains("<VAST", ignoreCase = true) -> VAST
                declared != null -> declared
                adm.contains("mraid.js", ignoreCase = true) -> MRAID
                else -> HTML
            }
        }
    }
}
