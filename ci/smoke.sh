#!/usr/bin/env bash
# Runs inside the emulator. Installs the APK, indexes a test song, opens the app, plays the song, records everything.
APK=apk/app-debug.apk
PKG=com.moise.laniakea
mkdir -p out
adb install -r "$APK" | tee out/install.txt
adb shell pm grant $PKG android.permission.READ_MEDIA_AUDIO
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS

# Put a test song on the phone and make sure MediaStore has indexed it.
adb push test_tone.wav /sdcard/Music/test_tone.wav | tee out/push.txt
adb shell content call --uri content://media/external/file --method scan_volume --arg external_primary >> out/scan.txt 2>&1
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Music/test_tone.wav >> out/scan.txt 2>&1
for i in $(seq 1 20); do
  adb shell content query --uri content://media/external/audio/media --projection _display_name:_data > out/mediastore.txt 2>&1
  grep -q test_tone out/mediastore.txt && { echo "indexed after ${i}s" >> out/scan.txt; break; }
  sleep 2
done

SIZE=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1); W=${SIZE%x*}; H=${SIZE#*x}
echo "screen $W x $H" > out/screen.txt

adb shell am force-stop $PKG
adb logcat -c
adb shell am start -W -n $PKG/.MainActivity | tee out/start.txt
sleep 12
adb exec-out screencap -p > out/shot1_after_launch.png
adb shell dumpsys activity activities | grep -E "topResumedActivity" > out/top_activity.txt

# Tap the first song in the list.
adb shell input tap $((W/2)) $((H*27/100))
sleep 6
adb exec-out screencap -p > out/shot2_after_tap.png
adb shell dumpsys media_session | grep -E "package=$PKG|state=PlaybackState|description=" | head -12 > out/media_session.txt

# Open Now Playing by tapping the mini player.
adb shell input tap $((W/2)) $((H*93/100))
sleep 4
adb exec-out screencap -p > out/shot3_now_playing.png

adb logcat -d -s MoiseScan:V > out/logcat_scan.txt 2>&1
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml > out/ui_dump.xml 2>&1
PID=$(adb shell pidof $PKG | tr -d '\r')
adb logcat -d --pid=$PID > out/logcat_app.txt 2>&1
adb logcat -d -t 3000 > out/logcat_full.txt
{ echo "=== FATAL / crash ==="; grep -n -A30 -E "FATAL EXCEPTION|ANR in" out/logcat_full.txt | head -150
  echo; echo "=== app errors/warnings (own process) ==="; grep -E " [EWF] " out/logcat_app.txt | head -80
} > out/summary.txt
