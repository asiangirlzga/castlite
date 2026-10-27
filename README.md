# CastLite

Minimal screen + audio mirror from an Android phone to an Android TV. One tiny APK, no libraries, no browser. Install the same APK on both devices.

- Video: screen is encoded to H.264 (hardware) and decoded on the TV.
- Audio: sound played by apps on the phone is captured and played on the TV (Android 10+ on the phone).
- Works on the same Wi-Fi, or with the TV connected to the phone's hotspot.

## Build
Open this folder in Android Studio, let Gradle sync, then Build > Build APK(s).
Or with the Android SDK installed: `gradle assembleRelease`
APK: `app/build/outputs/apk/release/app-release.apk` (signed with the debug key, installs directly).

## Install
- Phone: copy the APK over and install.
- Android TV / Google TV: install with a "send files to TV" app or USB drive, or with adb:
  `adb connect TV_IP` then `adb install app-release.apk`

## Use
1. Phone and TV on the same Wi-Fi, or TV joined to the phone's hotspot.
2. Open CastLite on the TV. It goes straight to the waiting screen. (On a non-TV device use "USE THIS DEVICE AS THE TV SCREEN".)
3. On the phone tap FIND TV, then START CASTING, and allow screen capture and audio.
4. Tap STOP CASTING on the phone to end.

## Tuning
- `SendService.java`: `MAX_SIDE` (resolution) and `BITRATE`.
- `ReceiverActivity.java`: `VIDEO_DELAY_NS` if sound and picture are out of step.

## Limits
- Android TV / Google TV only (not Samsung Tizen or LG webOS, which cannot install APKs).
- Apps that block capture (Netflix, banking apps, some games) show black video and stay silent.
- No password or encryption: use it on your own network or hotspot.
- Audio is sent as raw PCM (about 1.4 Mbit/s) to keep the code tiny.
