# Auto Lyrics — Development Reference

## Terminology

| Term | Meaning |
|---|---|
| **Full lyrics view** (AA) | The scrollable browse-tree list of lyric lines on Android Auto, powered by `onLoadChildren()`. Shows a windowed subset of lines. |
| **Fast-updating screen** / **Now-playing card** (AA) | The Android Auto now-playing card. Displays title, artist, album art, and a subtitle (current lyric) via `MediaMetadataCompat`. Updated every 300ms. |
| **Small fast-updating screen** | The now-playing card when AA is in the *minimized* container (e.g. maps in the larger panel). |
| **Large fast-updating screen** | The now-playing card when it fills the larger AA container. |
| **Performance Mode** (Phone) | A separate full-screen immersive activity with large karaoke text and word pop-out. |
| **Karaoke** | Word-level timestamps (ELRC format). On the phone, the current word is highlighted; on AA, shown via bracket notation `【word】`. |
| **Synced lyrics** | Line-level timestamps (standard LRC). `LyricsStatus.FOUND`. |
| **Plain lyrics** / **Unsynced** | No timestamps. `LyricsStatus.PLAIN_ONLY`. Scrolled proportionally based on song duration. |
| **SyncLRC** | Primary lyrics API: `synclrc.tharuk.pro`. Provides karaoke, synced, and plain lyrics. |
| **LRCLIB** | Fallback lyrics API: `lrclib.net`. Provides synced and plain lyrics. |
| **AA offset** | A separate lyrics delay setting specific to Android Auto, stored as `aa_offset_ms` in SharedPreferences. |
| **Phone sync** | The global lyrics offset applied on the phone, managed by `MediaTracker.offsetMs`. |

## Architecture

```
AutoLyricsApp (Application)
  └─ MediaTracker (singleton) — playback tracking, lyrics fetching, state emission
       ├─ MediaListenerService (NotificationListenerService) — intercepts media notifications
       ├─ SyncLrcClient / LrcLibClient — lyrics API clients
       ├─ LrcParser — LRC/ELRC parsing
       ├─ MetadataCleaner — cleans track metadata for API queries
       ├─ LyricsCache — in-memory + background refresh + queue pre-fetch
       └─ AlbumColorExtractor — Palette API color extraction

  Phone UI
  ├─ MainActivity — main lyrics view, settings, sync tools
  └─ PerformanceActivity — full-screen immersive karaoke view

  Android Auto
  ├─ LyricsBrowserService (MediaBrowserServiceCompat)
  │   ├─ Browse tree (onLoadChildren) — windowed lyrics list
  │   ├─ MediaSession — metadata (title, art, subtitle), playback state
  │   ├─ Karaoke bracket builder — buildKaraokeText()
  │   └─ Transport controls proxy — forwards play/pause/seek to source player
  └─ BootReceiver — starts service on boot/package replace
```

## File Map

### Kotlin Sources (`app/src/main/java/com/autolyrics/`)

| File | Purpose |
|---|---|
| `AutoLyricsApp.kt` | Application class. Initializes `MediaTracker`, starts `LyricsBrowserService`. |
| `MainActivity.kt` | Phone UI: lyrics display, font/delay settings, tap-to-sync, audio sync, scroll handling, theme colors. |
| `PerformanceActivity.kt` | Full-screen immersive karaoke mode with large text and word pop-out effect. |
| `model/Models.kt` | Data classes: `TrackInfo`, `LyricWord`, `LyricLine`, `AlbumColors`, `LyricsStatus`, `LyricsState`. |
| `media/MediaTracker.kt` | Singleton. Tracks active media session, fetches lyrics, manages offset, emits `StateFlow<LyricsState>`. |
| `media/MediaListenerService.kt` | `NotificationListenerService` — detects media sessions. |
| `lyrics/SyncLrcClient.kt` | HTTP client for SyncLRC API (primary source). |
| `lyrics/LrcLibClient.kt` | HTTP client for LRCLIB API (fallback source). |
| `lyrics/LrcParser.kt` | Parses LRC and ELRC format strings into `LyricLine` lists. |
| `lyrics/MetadataCleaner.kt` | Normalizes track/artist strings for API queries. |
| `lyrics/LyricsCache.kt` | In-memory lyrics cache with background refresh and queue pre-fetching. |
| `util/AlbumColorExtractor.kt` | Extracts `AlbumColors` from album art bitmaps using AndroidX Palette. |
| `util/AudioSyncHelper.kt` | Audio-based auto-sync using `SpeechRecognizer`. Calculates offset from recognized speech. |
| `auto/LyricsBrowserService.kt` | Android Auto integration. Browse tree, MediaSession metadata/playback, karaoke brackets, transport proxy. |
| `auto/BootReceiver.kt` | Starts `LyricsBrowserService` on `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`. |

### Resources (`app/src/main/res/`)

| File | Purpose |
|---|---|
| `layout/activity_main.xml` | Phone main UI layout. |
| `layout/activity_performance.xml` | Full-screen performance mode layout. |
| `xml/automotive_app_desc.xml` | Android Auto app descriptor. |
| `values/themes.xml` | App theme (Material dark, no action bar). |
| `values/colors.xml` | Color resources. |
| `values/strings.xml` | String resources. |
| `drawable/` | Icons, backgrounds, shapes. |

### Key Configuration

| File | Purpose |
|---|---|
| `app/build.gradle.kts` | Dependencies, SDK versions, signing config, version management. |
| `AndroidManifest.xml` | Components, permissions, AA metadata. |
| `.github/workflows/build.yml` | CI: build APK, create GitHub release with versioned filename. |
| `app/signing.p12` | Shared signing keystore (avoids version conflicts across CI runs). |

## Key Constants (`LyricsBrowserService`)

| Constant | Value | Purpose |
|---|---|---|
| `WINDOW_SIZE` | 3 | Number of synced lyric lines in the AA browse tree window. |
| `PLAIN_WINDOW_SIZE` | 4 | Number of unsynced lyric lines in the AA browse tree window. |
| `PAD_WIDTH` | 60 | Character padding for browse tree items (reduces choppiness). |
| `NOTIFY_THROTTLE_MS` | 800ms | Minimum interval between browse tree refreshes. |
| `BROWSE_KARAOKE_WINDOW_MS` | 1000ms | Karaoke bracket time window for browse tree items. |
| `SUBTITLE_KARAOKE_WINDOW_MS` | 400ms | Karaoke bracket time window for now-playing subtitle. |
| `SESSION_REFRESH_MS` | 2500ms | Periodic MediaSession playback state refresh interval. |
| `PLAIN_LOOP_DELAY_MS` | 3000ms | Plain lyrics browse tree periodic advance interval. |

## SharedPreferences Keys (`auto_lyrics_prefs`)

| Key | Type | Default | Purpose |
|---|---|---|---|
| `lyrics_font_size` | Int | 16 | Phone lyrics text size (sp). |
| `lyrics_font_family` | String | `sans-serif` | Phone lyrics font family. |
| `aa_karaoke_enabled` | Boolean | true | Toggle karaoke bracket display on AA. |
| `aa_offset_ms` | Long | 0 | Android Auto-specific lyrics delay (ms). |

## Karaoke Bracket Logic (AA)

The bracket display uses **monotonic advancement** to avoid visual jitter:
- Opening bracket `【` is placed at the current word index.
- Closing bracket `】` is placed at the last word within the karaoke window.
- Both `lastKaraokeWordIdx` and `endIdx` only advance forward within the same line.
- `resetKaraokeState()` is called on track change and line change to allow the brackets to start fresh.

## Lyrics Fetch Priority

1. **SyncLRC** (`synclrc.tharuk.pro`) — returns karaoke (ELRC), synced (LRC), or plain text.
2. **LRCLIB** (`lrclib.net`) — fallback, returns synced (LRC) or plain text.
3. Within a response, preference: karaoke > synced > plain.
