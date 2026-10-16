#!/usr/bin/env bash
# Runs inside the emulator. Installs the APK, opens the app, and records what happened.
APK=apk/app-debug.apk
PKG=com.moise.laniakea
mkdir -p out
adb push test_tone.wav /sdcard/Music/test_tone.wav
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Music/test_tone.wav >/dev/null
adb install -r "$APK" | tee out/install.txt
adb shell pm grant $PKG android.permission.READ_MEDIA_AUDIO
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
adb logcat -c
adb shell am start -W -n $PKG/.MainActivity | tee out/start.txt
sleep 15
adb exec-out screencap -p > out/shot1_after_launch.png
adb shell dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity" > out/top_activity.txt
# Open the Now Playing screen by tapping the first song, then screenshot again.
adb shell input tap 540 520
sleep 4
adb exec-out screencap -p > out/shot2_after_tap.png
adb shell input tap 540 2000
sleep 4
adb exec-out screencap -p > out/shot3_after_tap2.png
adb logcat -d -t 4000 > out/logcat_full.txt
{ echo "=== FATAL / crash ==="; grep -n -A30 -E "FATAL EXCEPTION|AndroidRuntime|ANR in|Process: $PKG.*died" out/logcat_full.txt | head -150
  echo; echo "=== app tagged lines ==="; grep -nE "$PKG|MediaProvider|moise|Moise" out/logcat_full.txt | grep -E " E | W | F " | head -80
} > out/summary.txt
