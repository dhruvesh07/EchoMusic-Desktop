# Security Policy

## Supported versions

Only the latest release receives security fixes. The desktop port is currently
distributed as a Windows MSI and as a portable ZIP.

## Reporting a vulnerability

Please report vulnerabilities privately through GitHub Security Advisories:

https://github.com/dhruvesh07/EchoMusic-Desktop/security/advisories/new

Do not open a public issue for a vulnerability. Include the affected version,
reproduction steps, and impact.

---

## Antivirus and SmartScreen detections

### Short version

Echo Music Desktop is an **unsigned**, open-source application. Unsigned
installers from small projects are routinely flagged by heuristic and
machine-learning engines. The report on **v1.4.4** — Gridinsoft
`Trojan.Heur.02012123` and Trapmine `Malicious.moderate.ml.score` — was **not a
signature detection**. Both names are generic heuristics/ML scores.

### What was verified for v1.4.4

| Property | Value |
|---|---|
| File | `Echo-Music-Windows-x64-v1.4.4.exe` |
| Size | 92,090,368 bytes |
| SHA-256 | `fec86ea0b98e253a1bb73b7b22f9030391593eacfb0337a26c2a6ff5b208e78` |
| Authenticode | **Not signed** |
| Packaging | JDK 17 `jpackage` + WiX. The `.exe` was a WiX `msiwrapper` stub carrying a ~91 MB embedded MSI. |
| Microsoft Defender | No threats found |
| Payload | App JARs, JRE 17 runtime, Skiko native library, icons. **No bundled `ffmpeg.exe`**, downloader, service, driver, or scheduled task. |

A small PE file with a large embedded MSI is a shape that heuristic and ML
scanners often flag. That is consistent with the two generic verdicts; it is not
evidence of a specific malware family.

### What changed in v1.4.5

- **The self-extracting `.exe` wrapper is gone.** Releases are now an MSI
  installed by the trusted Windows Installer service, plus an optional portable
  ZIP. There is no small PE stub with a large embedded payload.
- **Per-user installation.** No administrator elevation, no machine-wide
  changes, no service or driver.
- **Build provenance.** Every release is built by GitHub Actions from the tagged
  commit. The workflow is public at
  `.github/workflows/build-windows.yml`, and each artifact gets a GitHub
  build-provenance attestation.
- **Checksums.** Every release includes `SHA256SUMS.txt`.
- **Source cleanup.** Developer-only scripts containing hardcoded local paths
  and a `CreateProcess`/`cmd.exe` launcher were removed. The hardcoded
  developer `ffmpeg` path was removed from the audio engine.
- **Optional Authenticode signing.** The release workflow signs the MSI when a
  certificate is provided through repository secrets. Code signing is the
  permanent fix for reputation-based warnings; until a certificate is
  configured, heuristics can still flag the binary.

### Verify a download

```powershell
Get-FileHash .\Echo-Music-Windows-x64-v1.4.5.msi -Algorithm SHA256
Get-Content .\SHA256SUMS.txt

# Requires GitHub CLI 2.49+
gh attestation verify .\Echo-Music-Windows-x64-v1.4.5.msi `
  --repo dhruvesh07/EchoMusic-Desktop
```

The SHA-256 must match `SHA256SUMS.txt` exactly. The attestation proves the file
was produced by this repository's workflow from a specific commit.

If you do not want to run an installer, use the portable ZIP or build from
source — see [BUILD.md](BUILD.md).

### If a scanner still flags a release

1. Do **not** disable real-time protection or add a blanket folder exclusion.
2. Verify the SHA-256 against the release's `SHA256SUMS.txt`.
3. Prefer the MSI or build from source.
4. Open an **Antivirus / SmartScreen report** issue with the vendor, detection
   name, exact filename, SHA-256, and VirusTotal link. Reports are submitted to
   the vendor.

Vendor false-positive channels:

- Gridinsoft: https://support.gridinsoft.com/portal/en/newticket
- Trapmine: fp@trapmine.com
- Microsoft: https://www.microsoft.com/en-us/wdsi/filesubmission
- VirusTotal dispute guidance: https://docs.virustotal.com/docs/false-positive

A ready-to-send report with the v1.4.4 hash and analysis is in
[docs/antivirus-false-positive.md](docs/antivirus-false-positive.md).

---

## Data and network behaviour

- **Local data:** `%USERPROFILE%\.echo-music` contains playback history,
  playlists, and taste signals. It is not uploaded anywhere.
- **Network:** the app talks to the music, lyrics, and artwork services it
  displays (YouTube Music / googlevideo CDNs and the configured lyrics
  providers). There is no analytics, telemetry, crash reporting, or
  advertising SDK.
- **Updates:** the app does not self-update or install background components.
- **ffmpeg:** audio/video decoding executes an `ffmpeg` binary that **you**
  install. It is deliberately not bundled. Lookup order is `ECHO_FFMPEG`,
  `FFMPEG_HOME`, next to the installed app, then `PATH`.
- **Installer:** per-user MSI, Start Menu and desktop shortcuts, and an
  uninstall entry. No service, driver, scheduled task, or autorun entry.
