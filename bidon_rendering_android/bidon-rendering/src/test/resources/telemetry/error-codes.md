# Error codes

Every `error_code` the renderer can send, with what it means and who owns the failure. A code is a string of digits, never renumbered or reused: a code that stops being raised stays in the list as `retired`. The bands are 1000 initialisation and configuration, 2000 token collection, 5000 payload parsing, 6000 creative load, 7000 display, 8000 transport and 11000 publisher integration. Failures reported by a system we do not own (the WebView, HTTP, the VAST spec, the media player) travel as `external_error_code` with `external_error_source`, never as a code of ours.

`Load stage` is the `failed_stage` sent with a load failure and `Show stage` is the `failed_stage` sent with a show failure, the point of the show where it happened; `–` means none, and `by creative` means the stage the ad screen had reached: `view_attach` for HTML and MRAID, `playback_start` for VAST. `Status` is `active` when the renderer raises the code today, `reserved` when the number is taken and the code is not raised yet, `retired` when it is no longer raised. `Raised by` names the BID ticket that emits the code; `–` means no ticket assigned yet. Failures at show time are sent as `ad_show_failed`, never as `ad_load_failed`.

The unit test `ErrorCodeTest` reads this file and fails when it and the `ErrorCode` enum differ.

| Code | Name | Domain | Load stage | Show stage | External source | Event | Status | Raised by |
|---|---|---|---|---|---|---|---|---|
| 1001 | SDK_INIT_THREW | bidon_sdk | – | – | – | sdk_init_failed | reserved | – |
| 1002 | SDK_INIT_TIMED_OUT | bidon_sdk | – | – | – | sdk_init_failed | reserved | – |
| 1101 | ADAPTER_INIT_THREW | network_adapter | – | – | – | adapter_init_result | reserved | – |
| 1102 | ADAPTER_INIT_TIMED_OUT | network_adapter | – | – | – | adapter_init_result | reserved | – |
| 1901 | CONFIG_SECTION_UNUSABLE | bidon_server | – | – | – | adapter_init_result | reserved | BID-124 |
| 1902 | RENDERER_INIT_THREW | bidon_sdk | – | – | – | adapter_init_result | reserved | BID-124 |
| 2001 | SIGNAL_TOKEN_THREW | bidon_sdk | – | – | – | signal_failed | reserved | BID-129 |
| 2002 | SIGNAL_TIMED_OUT | bidon_sdk | – | – | – | signal_failed | reserved | BID-129 |
| 5001 | PAYLOAD_MISSING | bidon_server | parse | – | – | adm_parse_result, ad_load_failed | reserved | BID-142 |
| 5002 | RENDERING_NOT_OBJECT | bidon_server | – | – | – | adm_parse_result | reserved | BID-142 |
| 5003 | RENDERING_VALUE_ADJUSTED | dsp | – | – | – | adm_parse_result | reserved | BID-142 |
| 5004 | MARKUP_BLANK_NO_PAYLOAD | publisher | parse | – | – | ad_load_failed | reserved | BID-142 |
| 5005 | M3_PAYLOAD_NOT_OBJECT | bidon_server | parse | – | – | adm_parse_result, ad_load_failed | reserved | BID-154 |
| 6001 | WEBVIEW_MAIN_FRAME_ERROR | creative | webview_load | – | webview | ad_load_failed | active | BID-136 |
| 6002 | MARKUP_REJECTED | device | webview_init | – | – | ad_load_failed | active | BID-136 |
| 6003 | BLANK_CREATIVE | creative | webview_load | – | – | ad_load_failed | active | BID-136 |
| 6004 | VAST_INVALID | creative | parse | – | vast | ad_load_failed | active | BID-136 |
| 6005 | VAST_WRAPPER_TOO_DEEP | creative | parse | – | vast | ad_load_failed | active | BID-136 |
| 6006 | VAST_WRAPPER_NO_URI | creative | parse | – | vast | ad_load_failed | active | BID-136 |
| 6007 | MRAID_JS_UNREADABLE | bidon_sdk | webview_init | – | – | ad_load_failed | reserved | BID-153 |
| 6008 | LOAD_TIMEOUT_WEBVIEW | creative | timeout | – | – | ad_load_failed | reserved | BID-153 |
| 6009 | LOAD_TIMEOUT_WRAPPER_FETCH | network | timeout | – | – | ad_load_failed | reserved | BID-153 |
| 6010 | LOAD_TIMEOUT_OTHER | bidon_sdk | timeout | – | – | ad_load_failed | reserved | BID-153 |
| 6011 | RENDER_PROCESS_GONE_DURING_LOAD | device | webview_load | – | – | ad_load_failed, render_crashed | reserved | BID-131 |
| 6012 | WEBVIEW_RESOURCE_ERROR | creative | – | – | webview | webview_error | reserved | BID-134 |
| 6013 | WEBVIEW_HTTP_ERROR | creative | – | – | http | webview_error | reserved | BID-134 |
| 6014 | VAST_WRAPPED_NO_AD | creative | parse | – | vast | ad_load_failed | active | BID-136 |
| 6015 | VAST_WRAPPER_CLEARTEXT_BLOCKED | creative | asset_fetch | – | vast | ad_load_failed | active | BID-136 |
| 7001 | NO_PLAYABLE_MEDIA | creative | – | media_select | vast | ad_show_failed | active | BID-140 |
| 7002 | PLAYER_ERROR | creative | – | playback_start | media3 | ad_show_failed | active | BID-140 |
| 7003 | WEBVIEW_GONE_AT_SHOW | device | – | ready_check | – | ad_show_failed | active | BID-140 |
| 7004 | AD_SCREEN_NOT_STARTED | bidon_sdk | – | activity_launch | – | ad_show_failed | active | BID-140 |
| 7005 | AD_SCREEN_NO_VIEW | device | – | view_attach | – | ad_show_failed | active | BID-140 |
| 7006 | CLOSED_BEFORE_IMPRESSION | device | – | by creative | – | ad_show_failed | active | BID-140 |
| 7101 | PLAYER_NETWORK | network | – | playback_start | media3 | video_error, ad_show_failed | reserved | BID-135 |
| 7102 | PLAYER_DECODE | device | – | playback_start | media3 | video_error, ad_show_failed | reserved | BID-135 |
| 7103 | END_CARD_IMAGE_FAILED | creative | – | – | – | video_error | reserved | BID-135 |
| 8001 | VAST_WRAPPER_NO_ANSWER | network | asset_fetch | – | vast | ad_load_failed | active | BID-136 |
| 8002 | VAST_WRAPPER_HTTP_STATUS | dsp | asset_fetch | – | http | ad_load_failed | active | BID-136 |
| 8003 | WEBVIEW_MAIN_FRAME_NETWORK | network | webview_load | – | webview | ad_load_failed | active | BID-136 |
| 8004 | WEBVIEW_RESOURCE_NETWORK | network | – | – | webview | webview_error | reserved | BID-134 |
| 11001 | SHOW_NOT_READY | publisher | – | ready_check | – | ad_show_failed | active | BID-140 |
| 11002 | SHOW_TWICE | publisher | – | ready_check | – | ad_show_failed | active | BID-140 |
| 11003 | SHOWN_FROM_OTHER_PROCESS | publisher | – | ready_check | – | ad_show_failed | reserved | BID-140 |
