#!/usr/bin/env bash
set -euo pipefail

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

YT_DLP_VERSION="2026.10.26"
YT_DLP_SHA256="767ba9dce7052aa196f8b12652f433d76de128ba168966fa84b6394d5e6c6795"
YT_DLP_URL="https://github.com/yt-dlp/yt-dlp/releases/download/2026.10.26/yt-dlp_linux"

QUICKJS_VERSION="v0.18.0"
QUICKJS_SHA256="85ded23b9fabe8c1644fc40a8d2bbdd71157affcbd807359c618cc717ee24d7d"
QUICKJS_URL="https://github.com/quickjs-ng/quickjs/releases/download/v0.18.0/qjs-linux-x86_64"

FFMPEG_SHA256="92e3204c39c10fc40c0432f1b3be9e5817e0a62d6c638c37170b2cf3b8c6f215"
FFMPEG_ARCHIVE="ffmpeg-N-128837-g986bab2b47-linux64-lgpl.tar.xz"
FFMPEG_URL="https://github.com/FFmpeg-Builds/FFmpeg-Builds/releases/download/autobuild-2026-11-12-15-40/${FFMPEG_ARCHIVE}"

mkdir -p "$WORK/ffmpeg" "$WORK/bin"
curl --fail --location --retry 3 --connect-timeout 25 --max-time 900 \
  --output "$WORK/yt-dlp" "$YT_DLP_URL"
echo "$YT_DLP_SHA256  $WORK/yt-dlp" | sha256sum --check --status

curl --fail --location --retry 3 --connect-timeout 25 --max-time 900 \
  --output "$WORK/qjs" "$QUICKJS_URL"
echo "$QUICKJS_SHA256  $WORK/qjs" | sha256sum --check --status

curl --fail --location --retry 3 --connect-timeout 25 --max-time 900 \
  --output "$WORK/$FFMPEG_ARCHIVE" "$FFMPEG_URL"
echo "$FFMPEG_SHA256  $WORK/$FFMPEG_ARCHIVE" | sha256sum --check --status

tar -xJf "$WORK/$FFMPEG_ARCHIVE" -C "$WORK/ffmpeg" --wildcards '*/bin/ffmpeg'
FFMPEG_BIN="$(find "$WORK/ffmpeg" -type f -path '*/bin/ffmpeg' -print -quit)"
test -n "$FFMPEG_BIN"

install -m 0755 "$WORK/yt-dlp" "$WORK/bin/yt-dlp"
install -m 0755 "$WORK/qjs" "$WORK/bin/qjs"
install -m 0755 "$FFMPEG_BIN" "$WORK/bin/ffmpeg"

test "$("$WORK/bin/yt-dlp" --version)" = "$YT_DLP_VERSION"
"$WORK/bin/qjs" -v >/dev/null
"$WORK/bin/qjs" -e 'print("quickjs-ok")' | grep -Fx "quickjs-ok"
"$WORK/bin/ffmpeg" -hide_banner -version >/dev/null
"$WORK/bin/ffmpeg" -hide_banner -encoders 2>/dev/null | grep -q 'libvorbis'

"$WORK/bin/ffmpeg" -hide_banner -loglevel error \
  -f lavfi -i anullsrc=r=44100:cl=stereo -t 1 \
  -c:a libvorbis -q:a 5 "$WORK/smoke.ogg"
test -s "$WORK/smoke.ogg"

echo "Linux x86_64 native-tool smoke test passed:"
echo "  yt-dlp $YT_DLP_VERSION"
echo "  QuickJS-NG $QUICKJS_VERSION"
echo "  FFmpeg with libvorbis OGG encoding"
