# Optional native executables

Musicolide can extract optional native executables from the plugin JAR at startup.
Place binaries in platform folders before building:

- `linux-x86_64/ffmpeg`, `linux-x86_64/ffprobe`, `linux-x86_64/yt-dlp`
- `linux-aarch64/ffmpeg`, `linux-aarch64/ffprobe`, `linux-aarch64/yt-dlp`
- `windows-x86_64/ffmpeg.exe`, `windows-x86_64/ffprobe.exe`, `windows-x86_64/yt-dlp.exe`
- `macos-x86_64/ffmpeg`, `macos-x86_64/ffprobe`, `macos-x86_64/yt-dlp`
- `macos-aarch64/ffmpeg`, `macos-aarch64/ffprobe`, `macos-aarch64/yt-dlp`

Gradle copies these files to `native/<platform>/` inside the JAR. At startup, Musicolide extracts the matching platform files into `plugins/Musicolide/native/<platform>/`, marks Unix binaries executable and runs a bounded version check. Missing embedded binaries fall back to the executable names or configured paths in `config.yml`.

**No native binary payloads are committed here.** Release packagers must select and pin trusted builds, record checksums, include the corresponding license and notices, and confirm that redistribution is permitted. In particular, FFmpeg licensing depends on the exact build configuration; a binary with GPL or other optional components has additional obligations. Do not add unverified binaries to a release artifact.
