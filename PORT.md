# Echo Music — Windows Desktop Port

Port of [EchoMusicApp/Echo-Music](https://github.com/EchoMusicApp/Echo-Music) v1.4.4 (Android)
to a native Windows PC application.

- **Strategy:** Compose Multiplatform for Desktop (JVM), reusing upstream Kotlin sources *verbatim*
  wherever they are platform-independent.
- **Upstream clone:** `../Echo-Music` (pinned shallow clone of `main`, v1.4.4)
- **Shipped-artifact analysis:** `../Echo-RE` (APK v1.4.4, jadx + apktool decompilation)

---

## 1. Why Compose Multiplatform

The upstream UI is **Jetpack Compose** (645 `@Composable`s, 66 nav routes, Material 3).
Compose Multiplatform is the same Compose runtime/compiler on the JVM, so UI code carries
over with import changes (`androidx.compose.*` → `org.jetbrains.compose.*` for the few
desktop-only pieces) instead of a rewrite.

Verified gates:

| Gate | Result |
|---|---|
| Compose Desktop artifacts on Maven Central | ✅ 200 |
| Compose Gradle plugin 1.12.2 stable | ✅ available |
| Kotlin 2.3.10 (upstream's exact version) | ✅ available |
| JitPack (NewPipeExtractor / PipePipeExtractor) | ✅ 200 |
| JDK 21 toolchain | ✅ auto-provisioned via foojay resolver |

---

## 2. Module disposition

### Phase 1 — reused verbatim as JVM modules — ✅ BUILD SUCCESSFUL

Compiled on desktop JVM with **zero source changes** (only `build.gradle.kts` shims):

| Upstream module | Contents | Port status |
|---|---|---|
| `:metadata` | text cleaners, `ApiText` | ✅ compiled |
| `:domain` | repository/use-case interfaces | ✅ compiled |
| `:innertube` | **entire YouTube Music API client** — 113 files, Ktor 3.6 + kotlinx.serialization, NewPipe/PipePipe extractors, 30+ endpoints | ✅ compiled |
| `:lrclib` | lrclib.net lyrics | ✅ compiled |
| `:kugou` | KuGou lyrics (hash/keyword signing) | ✅ compiled |
| `:betterlyrics` | lyrics-api.boidu.dev + TTML parser | ✅ compiled |
| `:simpmusic` | api-lyrics.simpmusic.org | ✅ compiled |
| `:youlyplus` | 6-mirror failover lyrics | ✅ compiled |
| `:unison` | unison.boidu.dev | ✅ compiled |

Desktop code so far: `Main.kt` (NavigationRail shell), `HomeScreen.kt` (YouTube home shelves),
`SearchScreen.kt` (debounced search with tab filters), `YouTubeRepository.kt` (façade wrapper),
`DesktopLyricsService.kt` (port of `LyricsHelper`'s race-all-providers algorithm).

### Phase 2 — small shims required

| Module | Blocker | Plan |
|---|---|---|
| `:paxsenixlyrics` | `Timber`, `Context.packageManager` | compat module with JVM logger + version source |
| `:lyrics` | `Context`, `android.util.LruCache`, `DateUtils`, Hilt qualifiers | compat module: `LruCache` → `LinkedHashMap` eviction, `DateUtils` constants |
| `:canvas` `:applecanvas` `:echomusiccanvas` | none (already JVM) | add directly |
| `:shazamkit` | none (pure HTTP) | add directly |

### Phase 3 — rewrite on desktop equivalents

| Upstream | Desktop replacement |
|---|---|
| `:core` Room v48 (`song.db`, 21 entities, 5 DAOs, 55 migrations) | Room **JDBC** (same schema/entities/DAOs, `room-runtime` has a KMP story) or SQLite via JDBC with identical SQL; DataStore → `multiplatform-settings` or plain prefs file |
| `MusicService` (4885 lines, `MediaLibraryService`) | In-process player controller + **Windows SMTC** (`GlobalSystemMediaTransportControlsSessionManager`) for taskbar/media-key integration |
| ExoPlayer + `ResolvingDataSource` | **VLCJ** or **JavaFX MediaPlayer**/`vlcj`+libVLC for stream playback; keep upstream's resolver chain (`YTPlayerUtils` logic) feeding it resolved URLs |
| Audio focus / `AudioFocusRequest` | SMTC timeline + simple duck/pause coordination |
| WebView PoToken/BotGuard/cipher | **JCEF** (embedded Chromium) *or* upstream's non-WebView `innertubex` EJS/QuickJS tier — QuickJS runs fine on JVM (`org.quickjs:quickjs`) and the APK ships `yt.solver.core.js` (yt-dlp ejs) |
| Cronet | drop → OkHttp engine (already the default) |
| FFmpegKit (`AudioExportService`) | ship `ffmpeg.exe` sidecar or JavaCV |
| `:usbaudio` JNI USB-DAC | **WASAPI exclusive mode** via javax.sound.sampled / JNA |
| Hilt | Koin or manual composition root (36 ViewModels, ~20 modules) |
| WorkManager | coroutine scheduler |
| Notifications / channels | system tray + toasts |
| 11 widgets, QS tile, Android Auto (1600 lines), Cast, Firebase, haptics, boot receiver, APK updater | **dropped** (no Windows equivalent) |

---

## 3. Playback resolution chain (from shipped APK, not source)

```
mediaId
  └─ MusicService resolver lambda (ResolvingDataSource.Factory)
       ├─ YTPlayerUtils.playerResponseForPlayback()   // client fallback tiers
       │    WEB_REMIX → IOS → IPADOS → TVHTML5 → ANDROID_VR → VISIONOS → WEB_CREATOR
       ├─ InnerTubeXResolver.extract()                // innertubex: cipher tiers, PoToken, EJS
       └─ extensionManager.resolveStream()            // third-party addon sources
            │
            ├─ signature decipher  ← assets/player_configs.json (sig/nClass/sts tables)
            ├─ PO token minting    ← assets/solver/yt.solver.core.js (yt-dlp ejs)
            │                        + meriyah.js + astring.js, run in QuickJS
            └─ BotGuard            ← assets/po_token.html (WebView tier)
```

Desktop plan: reuse `player_configs.json` + `yt.solver.core.js` in a **JVM QuickJS**
(same assets, no WebView), with `innertubex`'s non-WebView tier as fallback.

> **Superseded by measurement.** The full solver stack above is only needed for the `WEB_REMIX`
> tier, which cannot answer anonymously anyway. Probing the ladder from a plain JVM showed a
> tier that needs **no** signature decipher, **no** `n`-transform, **no** PO token and **no**
> WebView — see §6. The solver assets remain unported on purpose.

---

## 6. Implemented: playback + UI (phase 3)

### 6.1 Stream resolution — `playback/StreamResolver.kt`

The ladder that was **measured** to work from this JVM (2026-10 probe):

| Tier | Result |
|---|---|
| `VISIONOS` 0.1 | ✅ direct URL, **whole file** (Range → 206 to the last byte), 6 h expiry — no cipher, no `n`, no PO token |
| `ANDROID_VR` 1.65.10 / `IOS` | direct URL but googlevideo answers 403 past ~1 MiB from most networks → degraded fallback only |
| `WEB_REMIX` (what the APK's solver stack targets) | unreachable anonymously — needs cookie login |

`visitorData` is mandatory: without `X-Goog-Visitor-Id` the JSON clients answer
`UNPLAYABLE`/`LOGIN_REQUIRED` ("Sign in to confirm you're not a bot") with zero formats.
Format selection mirrors upstream `YTPlayerUtils.findFormat`: audio-only, not auto-dubbed,
≤ 160 kbps, highest bitrate with a +10 240 bias toward webm/opus (itag 251 wins).

### 6.2 Audio engine — `playback/FfmpegPlayer.kt` + `playback/Playback.kt`

`ffmpeg` (already on this machine via scoop) decodes adaptive audio (opus/AAC — neither is
JVM-decodable) to PCM:

```
ffmpeg -i <url> -vn -map 0:a:0 -f s16le -ar 44100 -ac 2 -progress pipe:2 pipe:1
                              ↓                                    ↓
                        PCM → SourceDataLine              out_time_us → position
```

- **Pause** = stop draining the pipe: the OS buffer fills, ffmpeg blocks on write, which stalls
  its network read too — a real pause, not silence. The line is stopped so buffered audio stops.
- **Seek** = respawn with input-side `-ss` (googlevideo answers Range requests); progress is
  rebased by the seek offset because input `-ss` zeroes output timestamps.
- **End** = stdout EOF → auto-advance the queue.
- `Playback` is the desktop stand-in for `MusicService` + `PlayerConnection`: queue, shuffle,
  repeat (off/all/one), 3 s restart-on-previous, stream caching (5.5 h), error surfacing,
  and `PlayHistory` persistence for the Library tab.

### 6.3 Verification — `gradlew :desktop:smokeTest`

Headless end-to-end check (no UI needed):

```
resolved in 1562ms   client VISIONOS 0.1, itag 251, audio/webm opus 136544 bps, 3433755 B
range fetch: HTTP 206, 65536 bytes
decoded 529200 bytes of PCM (3.00 s) in 696ms, exit=0
PASS: resolution, streaming and decoding all work
```

### 6.4 UI — Apple-Music-style redesign (`ui/`)

Ported from the Android sources with their exact metrics:

| Component | Upstream reference | Notes |
|---|---|---|
| `theme/Theme.kt` | `echomusicTheme` | same **materialKolor 4.1.1** TonalSpot/SPEC_2025 palette from seed `#ED5564`, `pureBlack`, upstream's full type scale |
| `components/Components.kt` | `NavigationTitle`, `ChipsRow`, `Items.kt` | uppercase shelf headers + 24.dp "Play all" pill, 32.dp chips (inverted selection), 64.dp/48.dp list rows, 128.dp grid cards, playing indicator |
| `player/MiniPlayer.kt` | `NewMiniPlayer` | 64.dp pill (480 max), 3.dp progress ring over 40.dp art, 32/48/32 transport, rotating 9-sided "cookie" button |
| `player/FullPlayer.kt` | `BottomSheetPlayer` (GRADIENT) | artwork-derived `[primary, primary×0.6, black]` gradient + 20 % scrim, 84.dp white play button, slim seek track, shuffle/repeat, plus desktop-only **queue panel** and **volume** |
| `FloatingNav.kt` | `FloatingNavigationToolbar` | bottom pill, `surfaceContainerHigh` + 1.dp outline@30 %, sliding `onSurface`@15 % highlight, active icon ×1.12, label only on the active tab |
| `ui/icons/EchoIcons.kt` | — | hand-drawn vector set: `material-icons-core` stops at 1.7.3 (folded into the platform libs after that), so pinning it beside Compose 1.12.2 would be a stale artifact |
| `HomeScreen` / `SearchScreen` / `LibraryScreen` | `ui/screens/*` | chip-rail re-query with the chip's `browseId`/`params`, shelf carousels, debounced search → list rows, play-history Library |

Every card and row routes through one `onMediaClick(...)`: song → play with its shelf as the
queue; album/playlist/artist → fetch tracks → play. No inert UI.

### 6.5 Seek latency — the `-ss` stall fix (`FfmpegPlayer` + `VideoEngine`)

The first release regressed the one thing that matters most in a player: jumping the timeline.
`seekTo()` could take **16 s** (and a stale-load race sometimes restarted the track from 0:00).
Two causes, both measured with `gradlew :desktop:seekDiag`:

1. **ffmpeg's http layer "soft-seeked" by draining** the rest of the open response
   (`Soft-seeking to offset 329300 by draining 3417371 remaining byte(s)` in a `-loglevel trace`)
   before reopening — googlevideo serves that at ~1 MB/s → 3–16 s per jump. The threshold comes
   from `http_get_short_seek()`, which falls through to the **TLS layer** when `short_seek_size`
   is 0 (the default). Fix: **`-short_seek_size 16384`** → ffmpeg skips the drain and reopens
   immediately with `Range: bytes=<offset>-` (206 in ~60–130 ms).
2. **A stale-job race**: coroutine cancellation can't stop a load past its last suspension
   point, so a superseded seek could call `engine.play()` *after* the newer one — restarting
   from 0:00 while the UI showed the target. Fix: monotonic `loadGeneration` + a `playGate`
   lock around check-and-spawn (newest request always wins; covered by the smoke test's
   rapid double-seek case).

Latency tuning applied to both engines: `-probesize 128k/256k` (default **5 MB**),
`-analyzeduration 0.2 s/0.4 s` (default **5 s of media**), `-fflags nobuffer`, plus the same
`-short_seek_size`. Buffering state is now tied to the engine's `onStarted` (first PCM byte)
instead of process spawn, so the UI spinner covers the *real* wait — YouTube-style scrim +
ring over the player (`MediaSurface`), spinner in the mini-player transport.

Measured end-to-end (smoke test, this machine):

```
first play → audible: 556–991ms
seek latency (seekTo → audio at target): 811–1198ms   (was 15991ms)
rapid double-seek honours the LAST target ✓ (race fixed)
video resolved in 284–325ms: 854x480@25 itag=135 video/mp4
video first-frame after -ss 10: 1.6–3.9s               (was 110s — same drain, video file)
ALL CHECKS PASSED
```

Diag matrix (`:desktop:seekDiag`): `-ss 30` with only tuned probes = 5.5–15 s (drain path);
with `short_seek_size` = 0.78–1.2 s; baseline open = 0.47 s. Range probes confirm mid-file
`bytes=513000-` answers **206 in ~130 ms**; suffix ranges (`bytes=-N`) answer **416** (not used).

### 6.6 Video in the player — `playback/VideoEngine.kt` + `StreamResolver.resolveVideo`

`resolveVideo(videoId)` picks the visual half of the same adaptive manifest (same VISIONOS →
ANDROID_VR → IOS ladder; itag 135/140-class mp4 or webm, direct URL, Range-capable). The engine
runs one ffmpeg producing raw RGBA (`-vf scale=W:H,showinfo` with **explicit even dimensions** —
never `-2`, a guessed stride would desync the frame reader), frames go skia `Image.makeRaster` →
`ImageBitmap`. A/V sync is built for *smoothness*, after user-reported judder:

- **Exact frame times** — `showinfo`'s per-frame `pts_time` (parsed off stderr, paired to frames
  by number) calibrated by relative delta (`pts - firstPts`) so container timestamp offsets never shift
  or delay initial playback.
- **Interpolated audio clock** — raw `positionMs` refreshed every `stats_period` (now **100 ms**,
  was ffmpeg's 500 ms) is extrapolated forward between reports, so the pacer never stalls ~250 ms
  and then burst-drops.
- **Drift-based seek detection & instant paint** — natural playback progression during spawn no longer
  triggers false seek jumps (`rawDelta` compared against elapsed wall clock, and explicit `Playback.seekTo`
  events notified directly). First frames paint immediately to prevent blank/delayed UI transitions.
- **Official YouTube Music UI** — full player features the iconic `[ Song | Video ]` segmented pill
  switcher at the top center. Pure audio playback runs in Song mode with album artwork (saving CPU & bandwidth).
  Switching to Video opens a 16:9 widescreen theater view in real-time sync with audio, with tap-to-pause/play.

### 6.7 Playlists — `data/Playlists.kt` + `ui/playlists/`

`PlaylistStore` (`~/.echo-music/playlists.json`, `@Synchronized`, corrupt-file tolerant,
write-through): `create/rename/delete/addSong/removeSong/contains/byId/all/export/restore`.
UI: Library → **Playlists** tab (grid of playlist cards, New playlist, Save current queue),
playlist detail (play-all, rename, delete, per-row remove), and **⋮ on every song row**
(search + history) → Add-to-playlist dialog with inline Create & add.

### 6.8 Taste algorithm — `data/TasteModel.kt` + `data/Recommendations.kt`

Spotify-style signals, persisted in `~/.echo-music/taste.json`:
start **+1**, completion **+2**, early skip **−0.75** (skip < 30 s listened; later skips only
record listen time), search-term boost `+0.1·min(count,5)`; read-time decay
`0.5^(daysSince(lastPlayed)/30)` (30-day half-life), hard-drop for >3 completions, cold-start
backfill from play history. Recommendations = local listen-again pool seeded through
`YouTube.next()` auto-play queues (parallel), `related()` / artist search as breadth fills →
scored `1.0·artistAffinity + 0.3·seedAffinity + 0.15·freshness + titleBoost − 0.5·recentRepeat
− 0.05·plays`, then `take(limit×1.5) → shuffle → take(limit)` for rotation; 10-min session
cache, never throws (degrades to local pool, then hides the shelf). Home shows a
**Made for you** shelf when `hasTaste()`. Wiring: `Playback` calls `onTrackStarted/Completed/
Skipped`, `SearchScreen` calls `recordSearch(query)`.

### 6.9 Google sign-in & cloud sync — removed at user request

The OAuth PKCE flow, Drive `appDataFolder` bundle sync, account sheet, and the top-bar PERSON
button (`auth/GoogleAuth.kt`, `auth/CloudSync.kt`, `ui/AccountDialog.kt`) were built, then
**removed in full at the user's request** — no auth code, token storage, or network account
traffic remains. History, playlists, and the taste model are purely local
(`~/.echo-music/*.json`); `PlaylistStore`/`PlayHistory`/`TasteModel` keep their
export/restore helpers, which now serve plain local backup.

### 6.10 Fonts — `ui/theme/Fonts.kt`

All four upstream TTFs ship in `desktop/src/main/resources/fonts/` and load via
`androidx.compose.ui.text.platform.Font(resource, weight, variationSettings)` (class-path
`FontMgr` + Skia variation axes — `Font(resId)` variants are Android-only). Exact upstream
`wght`/`wdth`/`ROND` axis settings preserved; brand typography defaults to **Google Sans**.

### 6.11 Known gaps

- **Library** = local play history + user playlists (all persistence is on-disk local JSON;
  the cloud sync that previously backed this up was removed along with sign-in — see 6.9).
- **SMTC / media keys, WASAPI exclusive, lyrics UI, detail routes** (album/artist/playlist
  pages) and the **settings screen** (font selection hardcoded to Google Sans) are not ported
  yet. Lyrics providers port headlessly (`:lyrics` aggregator skipped) but have no UI.

---

```powershell
cd EchoMusic-Desktop
.\gradlew.bat -g C:\Users\dhruv\Downloads\gradle-home :desktop:run   # launch the app
.\gradlew.bat -g C:\Users\dhruv\Downloads\gradle-home :desktop:createDistributable  # MSI/EXE
```

**Why `-g`:** this machine sets `GRADLE_USER_HOME` to
`C:\Users\dhruv\scoop\apps\gradle\current\.gradle`, which accumulated a stale
Kotlin-DSL compilation of `settings.gradle.kts` (surfaced as
`NoSuchMethodError: Settings_gradle.<init>`). The `-g` flag points Gradle at a clean user home.
The poisoned caches under that directory have also been deleted as of this session.

Toolchain: the upstream modules target JVM 21, but only JDK 17 is installed, so all shims use
`jvmToolchain(17)` — none of the reused code depends on Java 21 APIs.

---

## 5. Evidence index

| Path | Contents |
|---|---|
| `../Echo-Music` | upstream source, 2010 files, 22 Gradle modules |
| `../Echo-RE/EchoMusic.apk` | shipped v1.4.4, sha256 `26307a4dcbea4ad18521523a16e70100aebc5d7bd286254b993a1d80929bafe6` |
| `../Echo-RE/res` | apktool-decoded manifest + resources (43 components, 1757 strings) |
| `../Echo-RE/src` | jadx-decompiled DEX (17,579 classes / 93,055 methods) |
