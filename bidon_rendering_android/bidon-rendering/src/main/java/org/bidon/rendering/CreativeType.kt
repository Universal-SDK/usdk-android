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
         * VAST markup is always video, since no other renderer can show it. Otherwise a declared
         * HTML or MRAID type wins: the markup alone cannot tell an MRAID creative from HTML that
         * merely mentions mraid.js. With nothing declared, the markup is the only evidence left.
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
