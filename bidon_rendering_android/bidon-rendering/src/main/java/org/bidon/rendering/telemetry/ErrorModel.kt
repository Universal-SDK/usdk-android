package org.bidon.rendering.telemetry

/** Who owns a failure, as it is written in `error_domain`. */
internal enum class ErrorDomain(val wireName: String) {
    BIDON_SDK("bidon_sdk"),
    BIDON_SERVER("bidon_server"),
    MAX("max"),
    NETWORK_ADAPTER("network_adapter"),
    PUBLISHER("publisher"),
    DSP("dsp"),
    NETWORK("network"),
    CREATIVE("creative"),
    DEVICE("device"),
}

/** Where a load failed, as it is written in `failed_stage`. */
internal enum class FailedStage(val wireName: String) {
    PARSE("parse"),
    RENDERER_SELECT("renderer_select"),
    ASSET_FETCH("asset_fetch"),
    WEBVIEW_INIT("webview_init"),
    WEBVIEW_LOAD("webview_load"),
    TIMEOUT("timeout"),
}

/** A system outside the renderer whose own code can come with an [ErrorCode], as written in `external_error_source`. */
internal enum class ExternalErrorSource(val wireName: String) {
    WEBVIEW("webview"),
    HTTP("http"),
    VAST("vast"),
    MEDIA3("media3"),
    DSP("dsp"),
}
