# Native runtime tools

Musicolide keeps native executables out of the distributable plugin JAR.

On Linux x86_64, the first-start installer downloads and SHA-256-verifies:
- yt-dlp standalone Linux x86_64, version 2026.10.26.
- The FFmpeg-Builds Linux x86_64 LGPL archive, checksum-pinned in `NativeToolInstaller`.

Only the FFmpeg executable is extracted. The source archive is deleted after installation. FFprobe is not used: FFmpeg's own input metadata output supplies the track duration without decoding the entire file.

The installed files live under `plugins/Musicolide/native/linux-x86_64/` and are reused after restart. The build includes a 30 MiB JAR-size guard. Other operating systems use the configured executable or the server process PATH; automatic downloads are currently limited to Linux x86_64.

The installer requires outbound HTTPS and GNU tar with XZ decompression support. If the host blocks native process execution or outbound downloads, provide compatible `ffmpeg` and `yt-dlp` executables through the server PATH or config.

## Upstream licensing

- yt-dlp release and license files: https://github.com/yt-dlp/yt-dlp/tree/2026.10.26
- FFmpeg source and LGPL licensing: https://ffmpeg.org/legal.html
- FFmpeg-Builds build variants and license information: https://github.com/FFmpeg-Builds/FFmpeg-Builds

The chosen FFmpeg build is the LGPL variant. Verify the upstream license and notices for the exact binary before redistributing it independently.
