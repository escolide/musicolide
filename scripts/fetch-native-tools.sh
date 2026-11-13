#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/src/main/native/linux-x86_64"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUT"
YT_DLP_VERSION="2026.10.26"
YT_DLP_SHA256="767ba9dce7052aa196f8b12652f433d76de128ba168966fa84b6394d5e6c6795"
FFMPEG_TAG="latest"
FFMPEG_ARCHIVE="ffmpeg-master-latest-linux64-lgpl.tar.xz"
FFMPEG_SHA256="a954b5f03a1b9c379113a40bcc84d2f773a7453d8bd555f4b600da8b39958586"
curl --fail --location --retry 3 --output "$WORK/yt-dlp" "https://github.com/yt-dlp/yt-dlp/releases/download/${YT_DLP_VERSION}/yt-dlp_linux"
echo "$YT_DLP_SHA256  $WORK/yt-dlp" | sha256sum --check --status
curl --fail --location --retry 3 --output "$WORK/ffmpeg.tar.xz" "https://github.com/FFmpeg-Builds/FFmpeg-Builds/releases/download/${FFMPEG_TAG}/${FFMPEG_ARCHIVE}"
echo "$FFMPEG_SHA256  $WORK/ffmpeg.tar.xz" | sha256sum --check --status
tar -tJf "$WORK/ffmpeg.tar.xz" > "$WORK/ffmpeg-files.txt"
grep -Eq "/bin/ffmpeg$" "$WORK/ffmpeg-files.txt"
grep -Eq "/bin/ffprobe$" "$WORK/ffmpeg-files.txt"
mkdir -p "$WORK/ffmpeg"
tar -xJf "$WORK/ffmpeg.tar.xz" -C "$WORK/ffmpeg"
FFMPEG_BIN="$(find "$WORK/ffmpeg" -type f -path "*/bin/ffmpeg" -print -quit)"
FFPROBE_BIN="$(find "$WORK/ffmpeg" -type f -path "*/bin/ffprobe" -print -quit)"
test -n "$FFMPEG_BIN" && test -n "$FFPROBE_BIN"
install -m 0755 "$FFMPEG_BIN" "$OUT/ffmpeg"
install -m 0755 "$FFPROBE_BIN" "$OUT/ffprobe"
install -m 0755 "$WORK/yt-dlp" "$OUT/yt-dlp"
LICENSES="$ROOT/src/main/native/licenses"
mkdir -p "$LICENSES/yt-dlp" "$LICENSES/ffmpeg"
curl --fail --location --retry 3 "https://raw.githubusercontent.com/yt-dlp/yt-dlp/${YT_DLP_VERSION}/LICENSE" --output "$LICENSES/yt-dlp/UNLICENSE.txt"
curl --fail --location --retry 3 "https://raw.githubusercontent.com/yt-dlp/yt-dlp/${YT_DLP_VERSION}/THIRD_PARTY_LICENSES.txt" --output "$LICENSES/yt-dlp/THIRD_PARTY_LICENSES.txt"
curl --fail --location --retry 3 "https://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt" --output "$LICENSES/ffmpeg/LGPL-2.1.txt"
cat > "$OUT/BUILD-INFO.txt" <<INFO
yt-dlp version: 2026.10.26
yt-dlp SHA-256: 767ba9dce7052aa196f8b12652f433d76de128ba168966fa84b6394d5e6c6795
FFmpeg build tag: autobuild-2026-11-12-15-40
FFmpeg archive: ffmpeg-master-latest-linux64-lgpl.tar.xz
FFmpeg archive SHA-256: a954b5f03a1b9c379113a40bcc84d2f773a7453d8bd555f4b600da8b39958586
FFmpeg variant: LGPL static
INFO
echo "Prepared pinned native tools in $OUT"
