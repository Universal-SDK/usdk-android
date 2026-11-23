#!/usr/bin/env bash
# Runs the demo app on an emulator against a local Bidon stack (bidon-backend's `just compose`).
# Run it inside the Nix dev shell (`direnv allow` or `nix develop`), which provides the SDK.
#
#   scripts/demo.sh emulator   create the AVD on first use, then boot it
#   scripts/demo.sh install    forward the stack's ports and install the debug app
set -euo pipefail

AVD_NAME="${AVD_NAME:-bidon-demo}"
SYSTEM_IMAGE="system-images;android-37.0;google_apis;x86_64"
SDKAPI_PORT="${SDKAPI_PORT:-1324}"
DSPSIM_PORT="${DSPSIM_PORT:-1325}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -z "${ANDROID_HOME:-}" ]]; then
  echo "ANDROID_HOME is not set: run this inside the Nix dev shell (direnv allow, or nix develop)." >&2
  exit 1
fi

emulator_cmd() {
  if ! avdmanager list avd -c | grep -qx "$AVD_NAME"; then
    echo "Creating AVD $AVD_NAME ($SYSTEM_IMAGE)"
    echo no | avdmanager create avd --name "$AVD_NAME" --package "$SYSTEM_IMAGE" --device pixel_8
  fi
  # A Nix-built emulator cannot always load the host's GPU drivers; software rendering is
  # enough for ads. Override with EMULATOR_GPU=host if your drivers cooperate.
  exec emulator -avd "$AVD_NAME" -gpu "${EMULATOR_GPU:-swiftshader_indirect}" -no-snapshot-save "$@"
}

install_cmd() {
  adb wait-for-device
  adb reverse "tcp:$SDKAPI_PORT" "tcp:$SDKAPI_PORT"
  adb reverse "tcp:$DSPSIM_PORT" "tcp:$DSPSIM_PORT"
  (cd "$ROOT/bidon_rendering_android" && ./gradlew :app:installDebug)
  adb shell am start -n com.demo.tetris/org.bidon.app.MainActivity
  cat <<EOF

Installed. On first run, open the app's Settings tab and set
  Base URL: http://localhost:$SDKAPI_PORT
then tap "Restart app". adb reverse does not survive an emulator restart: run install again.
EOF
}

case "${1:-}" in
  emulator) shift; emulator_cmd "$@" ;;
  install) install_cmd ;;
  *) echo "usage: $0 emulator|install" >&2; exit 2 ;;
esac
