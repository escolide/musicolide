# Embedded native tool bundles

The final universal JAR can carry one compressed native bundle per supported
platform. At startup, Musicolide selects only the matching bundle, verifies
its SHA-256 sidecar, and extracts it under plugins/Musicolide/native/<platform>/.
Runtime extraction uses Java ZIP APIs; it does not require a shell, tar, or
network access.

## Bundle source layout

Create a directory for each platform you intend to ship:

- linux-x86_64
- linux-aarch64
- windows-x86_64
- windows-aarch64
- macos-x86_64
- macos-aarch64

Each directory must contain all three executables. QuickJS is required by yt-dlp
for YouTube's JavaScript challenge solver; the manifest entry is
js-runtime=bin/qjs (or bin/qjs.exe on Windows).

Each directory must contain all three executables. QuickJS is the JavaScript engine
used by yt-dlp's YouTube challenge solver and is passed explicitly through
--js-runtimes quickjs:<absolute-path>.

Each directory must contain:

- bin/ffmpeg, bin/yt-dlp, and bin/qjs (append .exe on Windows)
- Any required runtime libraries in lib/
- Any included JavaScript runtime in bin/ or runtime/
- License and notice files in licenses/

Gradle packages these platform directories into compressed ZIP resources and
creates SHA-256 sidecars. The universal JAR includes all staged platform bundles;
the Linux x86_64 slim JAR includes only the Linux x86_64 bundle.

## 30 MiB release limit

Both distributable JARs are checked against a strict 30 MiB limit. The build
fails if either JAR exceeds it. This means upstream full-size FFmpeg archives
must not be copied directly into the source directories. The release toolchain must use minimal builds that retain the demuxers/decoders
needed for intended audio inputs and the OGG encoder required by Musicolide.
YouTube-ready yt-dlp bundles also need the external JavaScript challenge scripts
and a supported JavaScript runtime; this layout uses QuickJS. Test each platform
bundle against the actual input corpus and YouTube EJS flow before release.

No binary payload is checked into this repository by this setup commit. The
packaging and secure extraction path is being added first; the platform payloads
must be selected, license-reviewed, staged and size-verified before a release
can be called self-contained.
