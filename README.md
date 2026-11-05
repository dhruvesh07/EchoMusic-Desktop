<div align="center">
  <img src="assets/banner.png" alt="Echo Music Desktop Logo" width="800"/>

  <p><b>The official, modern PC desktop client for Echo Music — ad-free YouTube Music streaming, 16:9 theater visuals, lossless audio, dynamic artwork gradients, and zero bloat for Windows.</b></p>
</div>

---

## Overview

**Echo Music Desktop** brings the beloved [Echo Music](https://github.com/EchoMusicApp/Echo-Music) experience natively to Windows PC. Powered by Kotlin and Compose Multiplatform, Echo Music Desktop provides direct access to YouTube Music's entire catalog with zero advertisements, pristine audio quality, and a desktop-optimized interface.

Built from the ground up for desktop listening, it features:
- **Zero Ads**: Stream music continuously without commercial interruptions.
- **Pure Native Desktop Performance**: Hardware-accelerated Skia rendering with low RAM and CPU usage.
- **Dual [Song | Video] Switcher**: Instantly toggle between 1:1 square album art and a cinematic 16:9 theater experience.
- **Auto-Reconnect Audio Engine**: Lossless stream playback with intelligent network dropout recovery and zero desync.
- **Local Playlists & History**: Offline playlist management, taste profile tracking, and complete playback history.

---

## Screenshots

<div align="center">
  <table style="margin: 0 auto; border-collapse: collapse;">
    <tr>
      <td align="center" style="padding: 15px; border: none;">
        <b>Home Screen</b><br><br>
        <img src="Screenshots/desktop_home.png" alt="Home Screen" width="380" style="border-radius: 12px; box-shadow: 0 8px 16px rgba(0,0,0,0.2);"/>
      </td>
      <td align="center" style="padding: 15px; border: none;">
        <b>Full Player (Song Mode)</b><br><br>
        <img src="Screenshots/desktop_player_song.png" alt="Full Player (Song Mode)" width="380" style="border-radius: 12px; box-shadow: 0 8px 16px rgba(0,0,0,0.2);"/>
      </td>
    </tr>
    <tr>
      <td align="center" style="padding: 15px; border: none;">
        <b>16:9 Theater Mode (Video)</b><br><br>
        <img src="Screenshots/desktop_player_video.png" alt="16:9 Theater Mode" width="380" style="border-radius: 12px; box-shadow: 0 8px 16px rgba(0,0,0,0.2);"/>
      </td>
      <td align="center" style="padding: 15px; border: none;">
        <b>Playback Queue</b><br><br>
        <img src="Screenshots/desktop_queue.png" alt="Playback Queue" width="380" style="border-radius: 12px; box-shadow: 0 8px 16px rgba(0,0,0,0.2);"/>
      </td>
    </tr>
    <tr>
      <td align="center" style="padding: 15px; border: none;">
        <b>Search & Explore</b><br><br>
        <img src="Screenshots/desktop_search.png" alt="Search & Explore" width="380" style="border-radius: 12px; box-shadow: 0 8px 16px rgba(0,0,0,0.2);"/>
      </td>
      <td align="center" style="padding: 15px; border: none;">
        <b>Music Library & Playlists</b><br><br>
        <img src="Screenshots/desktop_library.png" alt="Music Library" width="380" style="border-radius: 12px; box-shadow: 0 8px 16px rgba(0,0,0,0.2);"/>
      </td>
    </tr>
  </table>
</div>

---

## Features

- **Ad-Free Streaming**: Listen to millions of tracks, albums, and artists without interruptions.
- **Dual Display Modes**: Effortlessly switch between high-resolution album artwork and 16:9 video theater view.
- **Dynamic Palette Backgrounds**: Visuals adapt in real time to the dominant tones of the playing song.
- **Queue & Navigation**: Floating pill navigation, mini-player preview, and comprehensive Up Next queue management.
- **Native Windows Desktop Integration**: Custom branded application icon, window state memory, and keyboard navigation (`Esc` to collapse player, `Space` for play/pause).
- **Personalized Music Library**: Create and organize custom playlists and review your listening history.

---

## Download

Windows 10 / 11 x64.

| Package | Use it if |
|---|---|
| [**Echo Music v1.4.5 — MSI**](https://github.com/dhruvesh07/EchoMusic-Desktop/releases/download/v1.4.5/Echo-Music-Windows-x64-v1.4.5.msi) | You want a normal Windows install (administrator approval required). |
| [**Echo Music v1.4.5 — Portable ZIP**](https://github.com/dhruvesh07/EchoMusic-Desktop/releases/download/v1.4.5/Echo-Music-Windows-x64-v1.4.5-portable.zip) | You do not want to run an installer. Unzip and launch `Echo Music.exe`. |
| [All releases](https://github.com/dhruvesh07/EchoMusic-Desktop/releases) | Older builds and checksums. |

Both artifacts are built from the tagged source by GitHub Actions and receive a
build-provenance attestation. Verify before running:

```powershell
Get-FileHash .\Echo-Music-Windows-x64-v1.4.5.msi -Algorithm SHA256
Get-Content .\SHA256SUMS.txt

gh attestation verify .\Echo-Music-Windows-x64-v1.4.5.msi `
  --repo dhruvesh07/EchoMusic-Desktop
```

### A note about antivirus warnings

Echo Music is currently **unsigned**. Unsigned JVM/installer builds are
regularly flagged by heuristic and ML engines. The v1.4.4 report (Gridinsoft
`Trojan.Heur.02012123`, Trapmine `Malicious.moderate.ml.score`) was generic
heuristic/ML, not a signature match; Microsoft Defender found no threats, and
the payload contained no bundled third-party executables.

v1.4.5 removes the self-extracting EXE wrapper and ships a plain MSI. It also
includes `SHA256SUMS.txt` and a GitHub build attestation. A code-signing
certificate is the permanent fix and is the next step.

See **[SECURITY.md](SECURITY.md)** for the full analysis and verification
steps. If a scanner still flags the app, use the portable ZIP, verify the hash,
or **[build it yourself](BUILD.md)**.

### Requirements

- **ffmpeg** on `PATH`, or `ECHO_FFMPEG` pointing at `ffmpeg.exe`. Audio and
  video decoding use ffmpeg. It is not bundled, because shipping a third-party
  `ffmpeg.exe` is itself a common antivirus false-positive trigger.

---

## Build from source

See [BUILD.md](BUILD.md) for prerequisites (JDK 17, WiX 3.14, ffmpeg) and
release packaging. Quick start:

```powershell
.\gradlew.bat :desktop:run
```

## License

GNU General Public License v3.0. This is a desktop port of
[Echo Music](https://github.com/EchoMusicApp/Echo-Music), also GPL-3.0.
See [LICENSE](LICENSE).
