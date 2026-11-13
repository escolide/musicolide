# Bundled native executables

The CI build fetches pinned, checksum-verified Linux x86_64 tools for the MineStrator target:
- yt-dlp 2026.10.26 standalone Linux x86_64 release.
- FFmpeg and FFprobe from the FFmpeg-Builds LGPL static Linux x86_64 build, autobuild 2026-11-12 15:40.

Run `bash scripts/fetch-native-tools.sh` before a local Linux x86_64 build. The build workflow runs the same script and smoke-tests all three executables before creating the plugin JAR.

The JAR contains native files at `native/linux-x86_64/`. Musicolide extracts them into `plugins/Musicolide/native/linux-x86_64/` and checks their versions asynchronously at startup. Other platforms retain the existing PATH/configured-executable fallback.

Upstream license notices are included in the JAR. The yt-dlp standalone executable includes GPL-licensed components; its third-party license notice is included. The FFmpeg archive is the LGPL static variant; its LGPL notice is included. Check upstream notices before redistributing modified builds.
