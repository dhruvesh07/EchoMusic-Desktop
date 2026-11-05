# Building Echo Music Desktop from source

These are the exact steps used to produce the official Windows release.

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Windows | 10 / 11 x64 | Packaging is Windows-only. |
| JDK | 17 | Temurin 17 is what CI uses. `jpackage` ships with it. |
| WiX Toolset | **3.14** | Required for the MSI. JDK 17's `jpackage` uses `candle.exe`/`light.exe`; WiX 4+ is not compatible. |
| ffmpeg | Any recent build | Runtime dependency for audio/video. Must be on `PATH`, or set `ECHO_FFMPEG` to `ffmpeg.exe`. |
| Gradle | Wrapper | Do not install Gradle separately. |

WiX 3.14 is normally installed at
`C:\Program Files (x86)\WiX Toolset v3.14\bin`. That directory must be on
`PATH` (the installer does this by default).

## Run from source

```powershell
git clone https://github.com/dhruvesh07/EchoMusic-Desktop.git
cd EchoMusic-Desktop
.\gradlew.bat :desktop:run
```

`run.bat` does the same thing and works from any checkout location.

## Package

```powershell
# MSI installer (recommended release artifact)
.\gradlew.bat :desktop:packageMsi

# Portable app image in a ZIP (no installer)
.\gradlew.bat :desktop:packagePortableZip
```

Outputs:

- `desktop\build\compose\binaries\main\msi\Echo Music-<version>.msi`
- `desktop\build\dist\Echo-Music-Windows-x64-v<version>-portable.zip`
- Unpacked app image: `desktop\build\compose\binaries\main\app\Echo Music\`

## Tests

Offline unit tests:

```powershell
.\gradlew.bat :domain:test :metadata:test
```

The `:innertube:test` suite makes live network requests, and `smokeTest` needs
ffmpeg plus a working YouTube stream.

## Verify a release artifact

```powershell
Get-FileHash .\Echo-Music-Windows-x64-v1.4.5.msi -Algorithm SHA256
Get-Content .\SHA256SUMS.txt

gh attestation verify .\Echo-Music-Windows-x64-v1.4.5.msi `
  --repo dhruvesh07/EchoMusic-Desktop
```

## Release process (maintainers)

1. Bump `version` in the root `build.gradle.kts`. The MSI version and portable
   ZIP name are derived from it.
2. Update `docs/release-notes.md`.
3. Commit, then push a tag:

   ```powershell
   git tag v1.4.5
   git push origin main --tags
   ```

4. `.github/workflows/build-windows.yml` builds on `windows-latest`, creates
   `SHA256SUMS.txt`, attests the artifacts, and publishes the GitHub release.

### Code signing

The workflow signs the MSI when these repository secrets exist:

| Secret | Contents |
|---|---|
| `WINDOWS_CERT_PFX_BASE64` | Base64 of the `.pfx` code-signing certificate |
| `WINDOWS_CERT_PASSWORD` | Password for the `.pfx` |

To sign a local build:

```powershell
.\scripts\sign-release.ps1 `
  -CertificatePath C:\path\to\cert.pfx `
  -CertificatePassword "***"
```

A publicly trusted code-signing certificate (OV/EV, or Azure Trusted Signing) is
the durable fix for SmartScreen and heuristic reputation warnings. The build
works without one, but the artifacts will remain unsigned.

Certificate options that accept open-source projects:

- **SignPath Foundation** — free code signing for eligible OSS projects:
  https://signpath.org/
- **Azure Trusted Signing** — low-cost Microsoft service with identity
  validation: https://azure.microsoft.com/products/trusted-signing
- **Certum Open Source Code Signing** — discounted OSS certificate:
  https://certum.store/

Once a certificate is issued, store the `.pfx` as base64 in the
`WINDOWS_CERT_PFX_BASE64` secret, add `WINDOWS_CERT_PASSWORD`, and the release
workflow will sign automatically.

## Why the installer is MSI-only

The previous release used Compose Desktop's `Exe` target. That target produces a
WiX `msiwrapper` — a small executable with the whole MSI embedded as a resource.
VirusTotal's heuristic/ML engines flagged that shape. The MSI target hands the
package to `msiexec` and removes the wrapper entirely.
