# Native runtime tools

Musicolide keeps all native executables out of both plugin JARs. The universal
JAR remains below 30 MiB and carries only the Java installer plus the archive
libraries needed to extract downloaded dependencies.

## First-start installation

On first startup, a background worker detects the host OS and CPU architecture,
then downloads pinned, SHA-256-verified versions of:

- yt-dlp 2026.10.26
- FFmpeg (an LGPL build for Linux and Windows; a separately published macOS build)
- QuickJS-NG 0.18.0, used by yt-dlp's YouTube JavaScript challenge support

The current automatic platform profiles are Linux x86_64, Linux ARM64, Windows
x86_64, Windows ARM64, macOS x86_64 and macOS ARM64.

Downloads are streamed to temporary files, checksum-verified, and only then
installed under `plugins/Musicolide/native/<platform>/bin/`. FFmpeg archives
are extracted with Java archive libraries; only the FFmpeg executable is kept.
The large source archive and partial files are removed after installation.
A small marker records exact tool versions and checksums, and the binaries are
verified again before reuse after a restart.

No shell, system tar program, Python installation, or manual native-tool
installation is required. Musicolide starts yt-dlp, FFmpeg and QuickJS as native
processes through Java `ProcessBuilder`; the host must permit process execution.

## Network and host requirements

The first startup needs outbound HTTPS access to GitHub release assets. YouTube
EJS components are fetched by yt-dlp from the official yt-dlp/ejs GitHub source
when required, then retained in Musicolide's yt-dlp cache. Hosts that block
outbound HTTPS can disable `audio.tools.download-on-first-start` and provide
compatible executables through the existing executable settings or PATH.

FFmpeg archives can be large (roughly 120–175 MB for some Linux/Windows
platforms). Downloads are streamed to disk rather than held in memory. Allow
enough free disk space for the download and installed executable.

## Licensing

- yt-dlp: Unlicense/public-domain dedication; third-party notices are provided
  by the yt-dlp project.
- QuickJS-NG: MIT.
- FFmpeg: licensing depends on the build and enabled codecs. Linux and Windows
  use the FFmpeg-Builds LGPL variants. The macOS package is a third-party build
  with a mixture of GPL/LGPL codecs according to its publisher.

The installed `licenses/NATIVE-TOOLS-NOTICES.txt` records the chosen release
URLs and hashes. Review upstream license notices before redistributing any of
the downloaded executables independently.

## Build outputs

- `musicolide-0.1.0-universal.jar`: recommended for portable installation.
- `musicolide-0.1.0.jar`: lightweight Linux x86_64 build.

Both builds enforce a strict 30 MiB limit. The universal JAR includes SQLite's
native driver libraries for all supported platforms and the first-start
installer, but no platform FFmpeg/yt-dlp/QuickJS binaries.
