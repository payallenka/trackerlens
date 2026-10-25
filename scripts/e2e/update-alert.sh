#!/usr/bin/env bash
# End-to-end test of the update-alert path on a device or emulator:
#   install test app v1 -> background check -> update to v2 (adds a tracker and a permission)
#   -> background check -> assert the notification -> clean up.
# Needs: adb, an Android SDK (build-tools, platform 36), JDK 17+, and the debug build installed
#        (./gradlew :app:installDebug).
# Run it on an emulator or a test device: it installs and removes a test app, clears the device log,
# grants the debug app notification permission and leaves a test alert in the debug app's history.
# ANDROID_SERIAL must name the device, so it can never run on a phone by accident.
set -euo pipefail
[ -n "${ANDROID_SERIAL:-}" ] || { echo "set ANDROID_SERIAL to the emulator/test device to use (see: adb devices)"; exit 2; }

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$SDK" ] || { echo "set ANDROID_HOME"; exit 2; }
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
AJ=$(ls "$SDK"/platforms/android-*/android.jar | sort -V | tail -1)
ADB="$SDK/platform-tools/adb"
APP=app.trackerlens.debug
FIXTURE=app.trackerlens.fixture
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"; "$ADB" uninstall "$FIXTURE" >/dev/null 2>&1 || true' EXIT

"$ADB" shell pm path "$APP" >/dev/null || { echo "install the debug build first: ./gradlew :app:installDebug"; exit 2; }

keytool -genkeypair -keystore "$WORK/fx.jks" -storepass android -keypass android -alias fx \
  -keyalg RSA -validity 1 -dname CN=fixture -noprompt >/dev/null 2>&1

# Builds a tiny APK whose code defines the given classes (tracker SDK class names) and permissions.
build() {
  local v=$1 perms=$2; shift 2
  local d="$WORK/v$v"; mkdir -p "$d/src" "$d/cls" "$d/dex"
  for c in app.trackerlens.fixture.Main "$@"; do
    local f="$d/src/${c//.//}.java"; mkdir -p "$(dirname "$f")"
    echo "package ${c%.*}; public class ${c##*.} {}" > "$f"
  done
  javac --release 11 -d "$d/cls" $(find "$d/src" -name '*.java')
  "$BT/d8" --min-api 28 --output "$d/dex" $(find "$d/cls" -name '*.class')
  cat > "$d/AndroidManifest.xml" <<XML
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$FIXTURE"
    android:versionCode="$v" android:versionName="$v.0">
  <uses-sdk android:minSdkVersion="28" android:targetSdkVersion="36"/>
  <uses-permission android:name="android.permission.CAMERA"/>
  $perms
  <application android:label="TrackerLens Test App"/>
</manifest>
XML
  "$BT/aapt2" link -o "$d/u.apk" --manifest "$d/AndroidManifest.xml" -I "$AJ"
  (cd "$d/dex" && zip -q ../u.apk classes.dex)
  "$BT/zipalign" -f 4 "$d/u.apk" "$d/a.apk"
  "$BT/apksigner" sign --ks "$WORK/fx.jks" --ks-pass pass:android --out "$WORK/test-v$v.apk" "$d/a.apk"
}

# Counts completed scans in the log. A check that was skipped logs no "scan:" line, so it can't pass.
scans() { grep -c "TrackerLens: scan:" < <("$ADB" logcat -d -s TrackerLens:I) || true; }

run_check() {
  local before; before=$(scans)
  # consent=true: a fresh install hasn't been through the journey, which normally grants scan consent.
  "$ADB" shell am broadcast -n "$APP/app.trackerlens.work.DebugCheckReceiver" --ez consent true >/dev/null
  for _ in $(seq 1 90); do
    [ "$(scans)" -gt "$before" ] && return 0
    sleep 1
  done
  echo "FAIL: background check did not run a scan"; exit 1
}

echo "building test app v1 (Facebook Ads) and v2 (+ Firebase Analytics, + location)"
build 1 "" com.facebook.ads.AdView
build 2 '<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>' \
  com.facebook.ads.AdView com.google.firebase.analytics.FirebaseAnalytics

"$ADB" uninstall "$FIXTURE" >/dev/null 2>&1 || true
"$ADB" shell pm grant "$APP" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
# Opening the app clears any earlier TrackerLens notification, so a stale one can't satisfy the check;
# then leave it, because alerts only count as seen while the app is visible.
"$ADB" shell am start -W -n "$APP/app.trackerlens.ui.MainActivity" >/dev/null
sleep 2
"$ADB" shell input keyevent KEYCODE_HOME

echo "step 1: install v1, baseline check"
"$ADB" install "$WORK/test-v1.apk" >/dev/null
run_check

echo "step 2: update to v2, check again"
"$ADB" install -r "$WORK/test-v2.apk" >/dev/null
run_check

notification=$("$ADB" shell dumpsys notification --noredact | grep -A80 "pkg=$APP" || true)
expect() { grep -qF -- "$1" <<<"$notification" || { echo "FAIL: notification lacks: $1"; exit 1; }; }
expect "An app update reduced your privacy"
expect "TrackerLens Test App: 1 new tracker and 2 new permissions"
expect "+ Tracker: Google Firebase Analytics"
expect "vis=PRIVATE"
expect "LOCAL_ONLY"
echo "PASS: update alert posted with the expected text, private on the lock screen, kept off watches"
