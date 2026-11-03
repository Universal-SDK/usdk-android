package org.bidon.rendering.telemetry

/**
 * Every error code the renderer can send, as `error_code`, with who owns the failure and the load or show stage it
 * failed in.
 * A code is never renumbered or reused; one that stops being raised is marked [retired] and stays. The checked-in
 * table `error-codes.md` lists the same codes.
 *
 * @param stage the `failed_stage` of a load failure; `null` for a code that is not a load failure
 * @param showStage the `failed_stage` of a show failure; `null` for a code that is not a show failure, and for
 * [CLOSED_BEFORE_IMPRESSION], whose stage is the point the ad screen had reached
 * @param externalSource the system whose own code may come with this one as `external_error_code`
 */
internal enum class ErrorCode(
    val number: Int,
    val domain: ErrorDomain,
    val stage: FailedStage?,
    val externalSource: ExternalErrorSource?,
    val retired: Boolean = false,
    val showStage: ShowStage? = null,
) {
    /** The mediation SDK's init threw. */
    SDK_INIT_THREW(1001, ErrorDomain.BIDON_SDK, null, null),

    /** The mediation SDK's init did not finish in time. */
    SDK_INIT_TIMED_OUT(1002, ErrorDomain.BIDON_SDK, null, null),

    /** A network adapter's init threw, in the mediation SDK's adapter init. */
    ADAPTER_INIT_THREW(1101, ErrorDomain.NETWORK_ADAPTER, null, null),

    /** A network adapter was still initialising when its group timed out, in the mediation SDK's adapter init. */
    ADAPTER_INIT_TIMED_OUT(1102, ErrorDomain.NETWORK_ADAPTER, null, null),

    /** The renderer's section of the server config cannot be used, at the renderer adapter's init. */
    CONFIG_SECTION_UNUSABLE(1901, ErrorDomain.BIDON_SERVER, null, null),

    /** The renderer's init threw, at the renderer adapter's init. */
    RENDERER_INIT_THREW(1902, ErrorDomain.BIDON_SDK, null, null),

    /** Building the signal token threw, when the adapter collects its signal. */
    SIGNAL_TOKEN_THREW(2001, ErrorDomain.BIDON_SDK, null, null),

    /** The signal was not ready in time, when the adapter collects its signal. */
    SIGNAL_TIMED_OUT(2002, ErrorDomain.BIDON_SDK, null, null),

    /** The ad payload is missing, not a string, or blank, when the adapter reads it. */
    PAYLOAD_MISSING(5001, ErrorDomain.BIDON_SERVER, FailedStage.PARSE, null),

    /** The payload's rendering settings are not an object, so the defaults apply, when the adapter reads it. */
    RENDERING_NOT_OBJECT(5002, ErrorDomain.BIDON_SERVER, null, null),

    /** A rendering setting in the payload could not be applied as given, when the adapter reads it. */
    RENDERING_VALUE_ADJUSTED(5003, ErrorDomain.DSP, null, null),

    /** The markup is blank in a renderer made without a payload, when the load starts. */
    MARKUP_BLANK_NO_PAYLOAD(5004, ErrorDomain.PUBLISHER, FailedStage.PARSE, null),

    /** The payload the mediation forwarded is not a JSON object, when the adapter reads it. */
    M3_PAYLOAD_NOT_OBJECT(5005, ErrorDomain.BIDON_SERVER, FailedStage.PARSE, null),

    /**
     * The main frame failed before the page loaded with a WebView error that is not a network one, in the HTML and
     * MRAID renderers.
     */
    WEBVIEW_MAIN_FRAME_ERROR(6001, ErrorDomain.CREATIVE, FailedStage.WEBVIEW_LOAD, ExternalErrorSource.WEBVIEW),

    /** The WebView threw while taking the markup, in the HTML and MRAID renderers. */
    MARKUP_REJECTED(6002, ErrorDomain.DEVICE, FailedStage.WEBVIEW_INIT, null),

    /** The page loaded with nothing on it that can be seen, in the HTML and MRAID renderers. */
    BLANK_CREATIVE(6003, ErrorDomain.CREATIVE, FailedStage.WEBVIEW_LOAD, null),

    /** The VAST document is invalid, in the VAST renderer's load. */
    VAST_INVALID(6004, ErrorDomain.CREATIVE, FailedStage.PARSE, ExternalErrorSource.VAST),

    /** The VAST wrapper chain is deeper than the parser follows, in the VAST renderer's load. */
    VAST_WRAPPER_TOO_DEEP(6005, ErrorDomain.CREATIVE, FailedStage.PARSE, ExternalErrorSource.VAST),

    /** A VAST wrapper has no usable http or https address, in the VAST renderer's load. */
    VAST_WRAPPER_NO_URI(6006, ErrorDomain.CREATIVE, FailedStage.PARSE, ExternalErrorSource.VAST),

    /** The renderer's own MRAID script could not be read, in the MRAID renderer's load. */
    MRAID_JS_UNREADABLE(6007, ErrorDomain.BIDON_SDK, FailedStage.WEBVIEW_INIT, null),

    /** The load timed out while the page was loading or being checked for content. */
    LOAD_TIMEOUT_WEBVIEW(6008, ErrorDomain.CREATIVE, FailedStage.TIMEOUT, null),

    /** The load timed out while a VAST wrapper was being fetched. */
    LOAD_TIMEOUT_WRAPPER_FETCH(6009, ErrorDomain.NETWORK, FailedStage.TIMEOUT, null),

    /** The load timed out anywhere else. */
    LOAD_TIMEOUT_OTHER(6010, ErrorDomain.BIDON_SDK, FailedStage.TIMEOUT, null),

    /** The WebView's render process went away during the load, in the HTML and MRAID renderers. */
    RENDER_PROCESS_GONE_DURING_LOAD(6011, ErrorDomain.DEVICE, FailedStage.WEBVIEW_LOAD, null),

    /** A resource of the page failed with a WebView error that is not a network one. */
    WEBVIEW_RESOURCE_ERROR(6012, ErrorDomain.CREATIVE, null, ExternalErrorSource.WEBVIEW),

    /** A resource or the main frame of the page answered with an HTTP status of 400 or above. */
    WEBVIEW_HTTP_ERROR(6013, ErrorDomain.CREATIVE, null, ExternalErrorSource.HTTP),

    /** The response a VAST wrapper led to has no ad that may play, in the VAST renderer's load. */
    VAST_WRAPPED_NO_AD(6014, ErrorDomain.CREATIVE, FailedStage.PARSE, ExternalErrorSource.VAST),

    /** The app's cleartext policy refused a VAST wrapper's http address, in the VAST renderer's load. */
    VAST_WRAPPER_CLEARTEXT_BLOCKED(6015, ErrorDomain.CREATIVE, FailedStage.ASSET_FETCH, ExternalErrorSource.VAST),

    /** The VAST ad has no media file the device can play, found when it is shown. */
    NO_PLAYABLE_MEDIA(7001, ErrorDomain.CREATIVE, null, ExternalErrorSource.VAST, showStage = ShowStage.MEDIA_SELECT),

    /** The player failed before the first frame of the video ad. */
    PLAYER_ERROR(7002, ErrorDomain.CREATIVE, null, ExternalErrorSource.MEDIA3, showStage = ShowStage.PLAYBACK_START),

    /** The ad loaded, but its WebView was gone when it was shown. */
    WEBVIEW_GONE_AT_SHOW(7003, ErrorDomain.DEVICE, null, null, showStage = ShowStage.READY_CHECK),

    /** The ad screen could not be started, when the ad is shown. */
    AD_SCREEN_NOT_STARTED(7004, ErrorDomain.BIDON_SDK, null, null, showStage = ShowStage.ACTIVITY_LAUNCH),

    /** The ad screen started and found no view to show. */
    AD_SCREEN_NO_VIEW(7005, ErrorDomain.DEVICE, null, null, showStage = ShowStage.VIEW_ATTACH),

    /** The ad screen closed before the impression, with no other failure. */
    CLOSED_BEFORE_IMPRESSION(7006, ErrorDomain.DEVICE, null, null),

    /** The player could not reach the media, while the video ad was shown. */
    PLAYER_NETWORK(7101, ErrorDomain.NETWORK, null, ExternalErrorSource.MEDIA3, showStage = ShowStage.PLAYBACK_START),

    /** The device could not decode or output the media, while the video ad was shown. */
    PLAYER_DECODE(7102, ErrorDomain.DEVICE, null, ExternalErrorSource.MEDIA3, showStage = ShowStage.PLAYBACK_START),

    /** The video ad's end card image failed to load. */
    END_CARD_IMAGE_FAILED(7103, ErrorDomain.CREATIVE, null, null),

    /** A VAST wrapper's fetch got no answer, in the VAST renderer's load. */
    VAST_WRAPPER_NO_ANSWER(8001, ErrorDomain.NETWORK, FailedStage.ASSET_FETCH, ExternalErrorSource.VAST),

    /** A VAST wrapper's server answered with a status outside 200 to 299, in the VAST renderer's load. */
    VAST_WRAPPER_HTTP_STATUS(8002, ErrorDomain.DSP, FailedStage.ASSET_FETCH, ExternalErrorSource.HTTP),

    /** The main frame failed before the page loaded with a network error, in the HTML and MRAID renderers. */
    WEBVIEW_MAIN_FRAME_NETWORK(8003, ErrorDomain.NETWORK, FailedStage.WEBVIEW_LOAD, ExternalErrorSource.WEBVIEW),

    /** A resource of the page failed with a network error. */
    WEBVIEW_RESOURCE_NETWORK(8004, ErrorDomain.NETWORK, null, ExternalErrorSource.WEBVIEW),

    /** An ad was asked to show before it loaded, after a failed load or after a destroy. */
    SHOW_NOT_READY(11001, ErrorDomain.PUBLISHER, null, null, showStage = ShowStage.READY_CHECK),

    /** An ad was asked to show a second time. */
    SHOW_TWICE(11002, ErrorDomain.PUBLISHER, null, null, showStage = ShowStage.READY_CHECK),

    /** An ad was asked to show from a process other than the one that hosts the ad screen. */
    SHOWN_FROM_OTHER_PROCESS(11003, ErrorDomain.PUBLISHER, null, null, showStage = ShowStage.READY_CHECK),
    ;

    companion object
}
