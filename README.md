# Bidon uSDK renderer for Android

An open-source ad renderer that lets a DSP's ads show inside a publisher's app without the DSP shipping an SDK of its own. It renders HTML, MRAID and VAST creatives, takes its display settings from the bid response, fires the creative's tracking, and reports telemetry about each ad.

The renderer plugs into the [Bidon SDK](https://github.com/bidon-io/bidon-sdk-android) as a network adapter, so a publisher who already runs Bidon gets it through the usual auction.

## Modules

The Android project lives in `bidon_rendering_android/`.

| Module | Package | What it is |
|---|---|---|
| `bidon-rendering` | `org.bidon.rendering` | The renderer: HTML, MRAID and VAST, banner and fullscreen, tracking and telemetry. Its public API is pinned in [`api/bidon-rendering.api`](bidon_rendering_android/bidon-rendering/api/bidon-rendering.api). |
| `adapter/bidon` | `org.bidon.bidon` | The Bidon SDK adapter: banner, interstitial and rewarded, built against `org.bidon:bidon-sdk:0.16.0`. |
| `app` | `org.bidon.app` (application id `com.demo.tetris`) | A demo app that loads and shows all three formats through the Bidon SDK, plus a screen that renders creatives without a server. |

## Requirements

- Android Studio, or JDK 21 for command-line builds (Gradle downloads it if it's missing)
- `minSdk` 24, `compileSdk` 37
- Access to `https://artifactory.bidon.org/bidon`, which serves `org.bidon:bidon-sdk`

## Build and test

Run from `bidon_rendering_android/`:

```sh
./gradlew assembleDebug                # build all modules
./gradlew testDebugUnitTest            # unit tests
./gradlew connectedDebugAndroidTest    # device tests, needs an emulator or a device
./gradlew apiCheck                     # fails if the public API changed without apiDump
./gradlew lint
./gradlew :app:installDebug            # install the demo app
```

The demo app needs a Bidon server: set its URL and your app key on the app's Settings tab. A telemetry URL set there is used until the server's config carries a `telemetry` block.

## Using it with the Bidon SDK

The renderer isn't published to a Maven repository yet. Add `bidon-rendering` and `adapter/bidon` to your build as modules, then register the adapter before you initialize the SDK:

```kotlin
BidonSdk
    .registerAdapters(BidonAdapter())
    .initialize(context = activity, appKey = "YOUR_APP_KEY")
```

The adapter starts the renderer when the SDK initializes it. When a bid wins, the adapter picks the renderer for the creative type and applies the `ext.rendering` object from the bid response: the close button's delay, countdown, style and position, the VAST skip offset, and when an impression counts as viewable. The fields of that object are described in the [rendering configuration schema](https://github.com/bidon-io/bidon-backend/blob/new-main/docs/rendered-configuration-schema.md).

## Telemetry

The renderer reports what happened to each ad: load failures, show requests and show failures, impressions, and crashes of the WebView's render process or of the renderer itself. Events are kept on disk and sent in batches to the `https` URL in the `telemetry` block of the adapter's server config. Without that block nothing is sent; inside it, `enabled` switches telemetry off and `sample_rate` thins it out.

- [`events.proto`](bidon_rendering_android/bidon-rendering/src/test/resources/telemetry/catalogue/events.proto) is a copy of the server's event catalogue; `events.proto.source` next to it names the commit it was taken from, and the unit tests check every event against it.
- [`error-codes.md`](bidon_rendering_android/bidon-rendering/src/test/resources/telemetry/error-codes.md) lists every error code, including the ones reserved and not raised yet, with what it means and who owns the failure. A unit test fails when the list and the code differ.

## Releasing

There is no release process yet: nothing is published, and the version is set in code (`adapterVersion` and `sdkVersion` in `BidonAdapter`). Publishing to a Maven repository is planned.

## Contributing

Open a pull request against `main` from a branch named after its area (`renderer/<topic>`, `app/<topic>`, `build/<topic>`). Before you open it, run the unit tests, `apiCheck` and `lint`; if you change the public API on purpose, run `apiDump` and commit the updated `.api` file. Add tests with the code, and include the test output in the pull request.

## License

Apache License 2.0, see [LICENSE](LICENSE).
