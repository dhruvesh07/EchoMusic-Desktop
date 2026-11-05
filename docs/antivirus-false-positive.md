# Antivirus false-positive report pack

Use these details when a vendor flags a release. Submit only files you built or
downloaded from the official GitHub release, and include the SHA-256.

## v1.4.4 — the reported file

| Field | Value |
|---|---|
| Filename | `Echo-Music-Windows-x64-v1.4.4.exe` |
| Size | 92,090,368 bytes |
| SHA-256 | `fec86ea0b98e253a1bb73b7b22f9030391593eacfb0337a26c2a6ff5b208e78` |
| Authenticode | Not signed |
| VirusTotal | https://www.virustotal.com/gui/file/fec86ea0b98e253a1bb73b7b22f9030391593eacfb0337a26c2a6ff5b208e78 |
| Gridinsoft verdict | `Trojan.Heur.02012123` (heuristic) |
| Trapmine verdict | `Malicious.moderate.ml.score` (ML score) |
| Microsoft Defender | No threats found (full file scan) |
| Project | https://github.com/dhruvesh07/EchoMusic-Desktop |
| Build | JDK 17 `jpackage` + WiX 3.14 |

Analysis notes for the vendor:

- The `.exe` is a WiX **`msiwrapper`** stub. It contains the real MSI as a
  ~91 MB `RCDATA` resource. It is not packed/obfuscated and has no downloader.
- The MSI payload contains the Compose app JARs, a Temurin 17 runtime, the
  Skiko native DLL, icons, and standard VC runtime/API-set DLLs. There is no
  bundled `ffmpeg.exe` or other third-party executable.
- The installer installs machine-wide to `Program Files` (same scope as
  v1.4.4), creates Start Menu and desktop shortcuts and an uninstall entry,
  and installs no service, driver, scheduled task, or autorun entry.
- Source is public GPL-3.0 code. The build is reproducible from the tag.

## v1.4.5 — replacement release

| Field | Value |
|---|---|
| Filename | `Echo-Music-Windows-x64-v1.4.5.msi` |
| SHA-256 | `cbc69f32a1f796291a83af226fd3c75d3d6d1735c89809d578ea915b0fce1820` |
| Authenticode | Not signed (unless the release workflow signing secret is set) |
| Packaging | Plain machine-wide MSI, no self-extracting wrapper |
| Portable alternative | `Echo-Music-Windows-x64-v1.4.5-portable.zip` (`fb0114d2d523b668c7252369bafe83556f3adc521a0d1d14f6df25bf29a72cfc`) |
| Microsoft Defender | No threats found (full file scan) |
| Build provenance | GitHub Actions run 42041877542, commit `094c711`, tag `v1.4.5` |

## Report template

**Subject:** False positive — Echo Music Desktop (open-source, unsigned JVM app)

```text
Hello,

Please re-analyze the following file, which is being flagged by a generic
heuristic/ML rule:

  Product:      Echo Music Desktop
  Version:      1.4.4
  Filename:     Echo-Music-Windows-x64-v1.4.4.exe
  SHA-256:      fec86ea0b98e253a1bb73b7b22f9030391593eacfb0337a26c2a6ff5b208e78
  Size:         92,090,368 bytes
  Signature:    Not signed
  VirusTotal:   https://www.virustotal.com/gui/file/fec86ea0b98e253a1bb73b7b22f9030391593eacfb0337a26c2a6ff5b208e78
  Detection:    Trojan.Heur.02012123
  Source:       https://github.com/dhruvesh07/EchoMusic-Desktop

The file is an open-source Compose Multiplatform/JVM music client packaged with
JDK 17 jpackage and WiX. The EXE is a WiX msiwrapper containing the MSI as a
resource; the payload has no downloader, persistence, service, driver, or
third-party executable. Microsoft Defender reports no threats.

The detection appears to be a heuristic/ML false positive triggered by the
unsigned wrapper + embedded MSI shape. The project now publishes a plain MSI
(no wrapper), SHA-256 checksums, and GitHub build provenance.

Please review and remove the detection if confirmed. I can provide the source
tag, build logs, or a fresh sample if needed.

Thank you.
```

## Vendor channels

| Vendor | Channel |
|---|---|
| Gridinsoft | https://support.gridinsoft.com/portal/en/newticket |
| Trapmine | fp@trapmine.com |
| Microsoft | https://www.microsoft.com/en-us/wdsi/filesubmission |
| VirusTotal | https://docs.virustotal.com/docs/false-positive |

For Gridinsoft, include the detection name, exact file path, publisher
("Echo Music"), product version, download source, and a screenshot of the scan
result. For Microsoft, upload the MSI/EXE and select **Incorrectly detected as
malware**.
