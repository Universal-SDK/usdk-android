{
  description = "Bidon uSDK renderer for Android: build tools and an emulator";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachSystem [ "x86_64-linux" ] (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            # The Android SDK is unfree and ships under Google's licence.
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };

        # compileSdk 37 for the build, build-tools 35.0.0 for AGP 8.10, and one x86_64 image
        # of the same API level for the emulator.
        android = pkgs.androidenv.composeAndroidPackages {
          platformVersions = [ "37.0" ];
          buildToolsVersions = [ "35.0.0" ];
          includeEmulator = true;
          includeSystemImages = true;
          systemImageTypes = [ "google_apis" ];
          abiVersions = [ "x86_64" ];
          includeCmake = false;
          includeNDK = false;
          includeSources = false;
        };

        sdk = "${android.androidsdk}/libexec/android-sdk";
      in
      {
        devShells.default = pkgs.mkShellNoCC {
          packages = [
            android.androidsdk
            pkgs.jdk21
          ];

          ANDROID_HOME = sdk;
          ANDROID_SDK_ROOT = sdk;
          JAVA_HOME = pkgs.jdk21.home;
        };
      }
    );
}
