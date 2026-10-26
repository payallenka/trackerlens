#!/usr/bin/env bash
# Builds, signs, tags and publishes a TrackerLens release. See RELEASING.md.
# Usage: scripts/release.sh <versionName> <versionCode>      e.g. scripts/release.sh 0.2.0 2
set -euo pipefail
cd "$(dirname "$0")/.."

name="${1:?versionName, e.g. 0.2.0}"; code="${2:?versionCode, e.g. 2}"
[[ "$name" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "versionName must look like 1.2.3"; exit 2; }
[[ "$code" =~ ^[0-9]+$ ]] || { echo "versionCode must be a number"; exit 2; }
[ -f keystore.properties ] || { echo "keystore.properties is missing: release builds must be signed with the release key"; exit 2; }
[ -z "$(git status --porcelain)" ] || { echo "working tree not clean; commit or stash first"; exit 2; }
[ "$(git rev-parse --abbrev-ref HEAD)" = main ] || { echo "release from main"; exit 2; }
notes="fastlane/metadata/android/en-US/changelogs/$code.txt"
[ -f "$notes" ] || { echo "write $notes first (user-facing release notes)"; exit 2; }
current=$(grep -oE 'versionCode = [0-9]+' app/build.gradle.kts | grep -oE '[0-9]+')
[ "$code" -gt "$current" ] || { echo "versionCode must increase (current: $current)"; exit 2; }

sed -i -E "s/versionCode = [0-9]+/versionCode = $code/; s/versionName = \"[^\"]+\"/versionName = \"$name\"/" app/build.gradle.kts

./gradlew --no-daemon -q clean :core:test :app:assembleRelease
apk=app/build/outputs/apk/release/app-release.apk
[ -f "$apk" ] || { echo "signed release APK not found"; exit 1; }

aapt2=$(ls "${ANDROID_HOME:?set ANDROID_HOME}"/build-tools/*/aapt2 | sort -V | tail -1)
perms=$("$aapt2" dump permissions "$apk")
if grep -q "android.permission.INTERNET" <<<"$perms"; then echo "INTERNET permission found; aborting"; exit 1; fi

mkdir -p dist && cp "$apk" "dist/trackerlens-$name.apk"
sha=$(sha256sum "dist/trackerlens-$name.apk" | cut -d' ' -f1)

git add app/build.gradle.kts
git commit -q -m "chore: release v$name"
git tag -a "v$name" -m "TrackerLens $name"
git push -q origin main "v$name"

body=$(printf '%s\n\n### Verify the download\n- APK SHA-256: `%s`\n- Signing certificate SHA-256: `5c:f6:e5:df:f9:1b:78:c6:70:b7:49:b2:39:d4:91:b5:5b:7e:cd:f6:02:b4:9a:79:fc:22:a0:39:0b:f1:1f:9a`\n\nFull changelog: CHANGELOG.md' "$(cat "$notes")" "$sha")
gh release create "v$name" "dist/trackerlens-$name.apk" --title "TrackerLens $name" --notes "$body" --verify-tag
echo "Released v$name ($sha)"
