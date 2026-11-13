# Native runtime tools

Musicolide keeps FFmpeg and yt-dlp executables out of both plugin JARs.

The Gradle build produces:
- A Linux x86_64 slim plugin JAR, which includes only SQLite's Linux x86_64 JNI library.
- A universal plugin JAR, which keeps SQLite's native libraries for all supported upstream platforms.

On Linux x86_64, the first-start installer downloads and SHA-256-verifies:
- yt-dlp standalone Linux x86_64, version 2026.10.26.
- The FFmpeg-Builds Linux x86_64 LGPL archive, pinned by its SHA-256 digest in `NativeToolInstaller`.

Only the FFmpeg executable is extracted. The source archive is deleted after installation. FFprobe is not used: FFmpeg's own input metadata output supplies the track duration without decoding the entire file.

The installed files live under `plugins/Musicolide/native/linux-x86_64/` and are reused after restart. The installer requires outbound HTTPS and GNU tar with XZ decompression support. If the host blocks native process execution or outbound downloads, provide compatible `ffmpeg` and `yt-dlp` executables through the server PATH or config.

The slim JAR is verified during the Gradle build by loading its SQLite driver in an isolated classloader and executing a test query. The universal JAR remains available for non-Linux-x86_64 installations. Automatic native-tool downloads currently target Linux x86_64 only.

## Upstream licensing

- yt-dlp release and license files: https://github.com/yt-dlp/yt-dlp/tree/2026.10.26
- FFmpeg source and LGPL licensing: https://ffmpeg.org/legal.html
- FFmpeg-Builds build variants and license information: https://github.com/FFmpeg-Builds/FFmpeg-Builds

The chosen FFmpeg build is the LGPL variant. Verify the upstream license and notices for the exact binary before redistributing it independently.
