# Echo Music Desktop v1.4.5 (Windows)

Security-hardening release in response to the VirusTotal report on v1.4.4.

## What changed

- **MSI replaces the self-extracting EXE.** The old `.exe` was a WiX
  `msiwrapper` stub with a ~91 MB embedded MSI — a shape heuristic and ML
  scanners often flag. The MSI is installed by the trusted Windows Installer
  service, with no wrapper.
- **Standard machine-wide installation.** Installs to `Program Files` with the
  same scope as v1.4.4, so the new MSI replaces the old version in place. No
  service, driver, or background component.
- **Portable ZIP.** A no-installer option for users who prefer to run the app
  from a folder.
- **Build provenance.** Releases are built by GitHub Actions from the tagged
  commit. Each MSI/ZIP has a GitHub build-provenance attestation.
- **`SHA256SUMS.txt`** is attached to every release.
- **ffmpeg discovery fixed.** The audio engine no longer points at a hardcoded
  developer path. It now checks `ECHO_FFMPEG`, `FFMPEG_HOME`, the app directory,
  then `PATH`.
- **Source cleanup.** Removed a developer-only launcher script with hardcoded
  local paths and `CreateProcess`/`cmd.exe` logic.
- **GPL-3.0 license added**, matching upstream Echo Music.

## Antivirus note

The v1.4.4 report was Gridinsoft `Trojan.Heur.02012123` and Trapmine
`Malicious.moderate.ml.score` — generic heuristics/ML, not signature matches.
The installer was unsigned; Microsoft Defender found no threats; the payload
contained no bundled third-party executables.

v1.4.5 is **not code-signed yet**. A code-signing certificate is the permanent
fix for SmartScreen and reputation warnings. Until one is configured, verify the
SHA-256 and the build attestation, use the portable ZIP, or build from source.
See [SECURITY.md](https://github.com/dhruvesh07/EchoMusic-Desktop/blob/main/SECURITY.md).

## Verify your download

```powershell
Get-FileHash .\Echo-Music-Windows-x64-v1.4.5.msi -Algorithm SHA256
Get-Content .\SHA256SUMS.txt

gh attestation verify .\Echo-Music-Windows-x64-v1.4.5.msi `
  --repo dhruvesh07/EchoMusic-Desktop
```

## Requirements

- Windows 10 / 11 x64
- **ffmpeg** on `PATH`, or `ECHO_FFMPEG` set to the full path of `ffmpeg.exe`.
  It is not bundled by design: shipping a third-party `ffmpeg.exe` is itself a
  common antivirus false-positive trigger.

## Upgrading from v1.4.4

The MSI keeps the same upgrade UUID and install scope as v1.4.4, so it replaces
the old version in place and preserves local data in `%USERPROFILE%\.echo-music`.
If Windows Installer reports a pending reboot or a partially removed old build,
uninstall **Echo Music** from Apps & Features first, then install the MSI.

## Build from source

See [BUILD.md](https://github.com/dhruvesh07/EchoMusic-Desktop/blob/main/BUILD.md).
