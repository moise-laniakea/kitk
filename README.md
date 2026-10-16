# Moïse Laniākea

Offline Android music player. Kotlin, Jetpack Compose (Material 3 / Material You), Media3 ExoPlayer, Room, TagLib.
Package `com.moise.laniakea`, minSdk 26, targetSdk 35.

**Status: compiles, not yet tested on a device.** GitHub Actions builds a debug APK from this repo on every push
(Actions tab → latest run → Artifacts → `MoiseLaniakea-debug-apk`). Nobody has run it on a phone yet, so expect runtime bugs.
Build settings that were needed: Kotlin 2.2.21 + KSP 2.2.21-2.0.5 + Room 2.7.2 and `compileSdk 36`, because TagLib 1.0.5 requires them.
If a build fails, the workflow writes the errors to `ERRORS.txt` on the `build-logs` branch.

## Build

1. Open this folder in Android Studio (Ladybug or newer). It creates the Gradle wrapper and syncs.
2. Run on a device or emulator. Grant audio and notification permission on first launch.
3. Release build: `./gradlew assembleRelease` (R8 and resource shrinking are on, ABIs limited to arm64-v8a and armeabi-v7a).

## Decoders for ALAC, APE, TAK, DSD, MKA

Android's built-in decoders cover MP3, WAV, FLAC, AAC, Ogg Vorbis, Opus, M4A and (on many devices) ALAC.
For ALAC everywhere, plus APE, TAK, DSD (DSF/DFF) and MKA, build the Media3 FFmpeg extension and drop the AAR into `app/libs/`:

1. Follow https://github.com/androidx/media/tree/release/libraries/decoder_ffmpeg (needs the NDK).
2. Enable at least these decoders: `alac ape tak dsd_lsbf dsd_msbf dsd_lsbf_planar dsd_msbf_planar flac vorbis opus aac mp3`.
3. Copy the resulting `decoder_ffmpeg-release.aar` to `app/libs/`. `PlaybackService` already prefers extension renderers.

This is the largest contributor to APK size, so keep the decoder list short.

## Where each requested feature lives

| Feature | Code |
|---|---|
| No built-in EQ, system/third-party EQ | `PlayerController.openSystemEqualizer`, equalizer button on Now Playing |
| Gapless | ExoPlayer native (works for MP3 with LAME/Xing header, M4A, FLAC, Opus, Vorbis) |
| Speed and pitch | `PlayerController.setSpeedPitch`, `SpeedPitchSheet` (can link speed and pitch) |
| Fade in/out on play and pause | `playback/FadingPlayer.kt`, sliders in settings and speed sheet |
| Dynamic queue | `QueueScreen`, `PlayerController` queue functions |
| Sleep timer by time or song count | `playback/SleepTimer.kt`, `SleepTimerSheet` |
| Notification controls, auto-pause | `PlaybackService` (MediaSessionService), idle auto-stop in settings |
| Playlists m3u/m3u8/pls/wpl/cue | `data/playlist/PlaylistFormats.kt` (import in Playlists tab; export m3u8 from a playlist) |
| Smart and nested playlists | `data/library/SmartPlaylists.kt`, `LibraryRepository.nestPlaylist/resolvePlaylist` |
| Lyrics LRC, enhanced LRC, TTML, SRT, SBV, SSA/ASS | `lyrics/LyricParsers.kt` |
| Karaoke from ID3 SYLT | `lyrics/Id3SyltParser.kt` |
| Lyrics display, tap to seek, karaoke highlight | `ui/LyricsView.kt` |
| Tag editor, batch, multi-value artists, custom delimiters | `ui/TagEditorScreen.kt`, `data/tags/TagIO.kt`, `data/library/Text.kt` |
| Album art editor, zoom view, folder art | `ArtworkLoader`, `TagEditorScreen`, `ZoomableArtDialog` |
| Library tabs, list/grid, natural and custom sort | `ui/LibraryScreen.kt`, `LibraryTabs.kt` |
| Linear and hierarchical folder browsing, whitelist, exclusion | `LibraryTabs.kt` (FolderTab), `LibraryRepository` |
| MediaStore + TagLib indexing with dedup | `LibraryRepository.indexNow` |
| Move, copy, rename, delete | `data/files/FileOps.kt` (uses Android 11+ approval prompts) |
| Four-section artist hub | `LibraryRepository.artistHub`, `ArtistScreen` |
| Audio cutter (WAV, M4A/AAC, OGG/Opus) and ringtone setter | `tools/AudioCutter.kt`, `tools/RingtoneSetter.kt`, `CutterScreen` |
| Material You, Monet, art-derived colors, themed icon | `ui/theme/Theme.kt`, monochrome adaptive icon |
| Now Playing themes, landscape side rail | `NowPlayingScreen.kt` (4 themes), `LibraryScreen.kt` (NavigationRail) |
| Strictly offline | Manifest declares no INTERNET permission |

## Known gaps and things to verify

- **TagLib**: `data/tags/TagIO.kt` uses `io.github.kyant0:taglib:1.0.5`. It compiles, but tag reading and writing are untested on real files.
- **Formats MediaStore ignores**: on Android 13+ MediaStore may not list .ape, .tak, .mka, .dsf/.dff at all. The indexer
  asks for them by extension, but a direct file-system scanner (with the "all files" permission) is not written.
- **Cue sheets**: parsed into entries with start offsets, but playback of a single-file CUE as separate tracks (clipping) is not wired up yet.
- **Gapless for fades**: fades only apply to explicit play/pause and the sleep timer, never to track changes.
- **Tag writing on Android 11+**: the editor requests write access, then you tap Save again.
- **Custom sort** currently sorts by library id; drag-to-reorder for the main list is not implemented (playlists and the queue have up/down buttons).
- Several wishlist items are basic versions: ringtone cutter has a range slider but no waveform, Now Playing themes are four simple layouts.
- No tests were written or run. The debug APK is large; a signed release build (R8 on) would be much smaller.
