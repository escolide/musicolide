# Musicolide

Musicolide is a Paper plugin for server-managed music playback through ordinary Minecraft Java clients. Players do not need a client mod or a browser. The client downloads generated resource packs on demand and plays custom sounds through Minecraft's audio engine.

## Supported versions

- **Paper 1.21.1 through 26.3**, using one plugin JAR.
- **Java 21 bytecode**. Minecraft 1.21.x servers run on Java 21; Minecraft 26.1+ servers must use their required Java 25 runtime.
- Purpur is expected to work because it is Paper-compatible.
- This release uses Paper's resource-pack stacking API. Spigot is not currently supported.

The plugin is compiled against Paper 1.21.1, the oldest supported API, to avoid accidentally linking methods introduced in newer releases. Its plugin API version is set to 1.21.1.

## Version-aware resource packs

Musicolide detects the running server version and writes the resource-pack metadata format that version expects:

| Minecraft release | Resource-pack metadata |
|---|---|
| 1.21.1 | pack_format: 34 |
| 1.21.2–1.21.3 | pack_format: 42 |
| 1.21.4 | pack_format: 46 |
| 1.21.5 | pack_format: 55 |
| 1.21.6 | pack_format: 63 |
| 1.21.7–1.21.8 | pack_format: 64 |
| 1.21.9–1.21.10 | min_format: [69, 0], max_format: 69 |
| 1.21.11 | min_format: [75, 0], max_format: 75 |
| 26.1 | min_format: [84, 0], max_format: 84 |
| 26.2 | min_format: [88, 0], max_format: 88 |
| 26.3 | min_format: [97, 1], max_format: 97 |

This is selected from the **server's** Minecraft version. If a proxy permits clients from a different Minecraft version to join the server (for example, via a protocol translation plugin), their resource-pack compatibility is not guaranteed; test those mixed-version clients separately.

## Design

- Java plugin owns commands, sessions, permissions, zones, queues and player arbitration.
- SQLite stores track metadata, external URL aliases, playlists and user preferences.
- FFmpeg/FFprobe prepare imported audio into stereo OGG segments only. Fade zones adjust per-player stereo volume instead of generating duplicate mono audio.
- A small built-in HTTP server serves generated per-track resource packs. Put HTTPS in front of it for production.
- Resource packs are requested only for the current track and a rolling prefetch window (four upcoming tracks by default). Client-side pack hashes allow cached packs to be reused.
- Spatial zones support circles, world-aligned or player-facing squares, optional vertical limits, and optional volume-based stereo fade outside the main zone.
- Overlap is resolved per player. The default policy is first-started session wins; lower-priority music continues in the exclusive part of its zone.

## Audio source and rights

Musicolide deliberately does **not** extract or rebroadcast audio from YouTube Music, Spotify or SoundCloud. Their platform terms and streaming APIs impose restrictions on downloading, caching, game integration and/or multi-listener rebroadcast. A URL from one of those services can be stored as an alias to a track that the server administrator has independently obtained permission to distribute. The alias is an identification hint, not a source of audio.

Import only audio that you own or are authorized to distribute to your players. Musicolide does not circumvent DRM, subscriptions, region controls or platform protections.

## Build

1. Install JDK 21 or newer.
2. Install FFmpeg and FFprobe on the server machine and ensure both executables are on your PATH (or configure their paths). Optional binaries can be packaged under src/main/native/<platform>/; see src/main/native/README.md for the platform layout and redistribution/licensing requirements.
3. Run: ./gradlew clean test shadowJar

The native extraction system does not include binary payloads in this source repository. Release packagers must add and license-check their own pinned builds.

The deployable plugin is build/libs/musicolide-0.1.0.jar.

## Server setup

1. Install the JAR in plugins/ and start Paper once.
2. Put authorized source files in plugins/Musicolide/incoming/.
3. Configure server.public-base-url to an address your players can reach. The built-in HTTP server binds to server.bind-address:server.port. For public servers, use an HTTPS reverse proxy and expose only the configured pack path.
4. Run /music import <filename> [title] as an administrator, or use /music import all to process every supported file in incoming/ sequentially.
5. Use /music list to see imported tracks.
6. Start playback with /music play <id-or-title>.

## Commands

- /music help
- /music import <filename> [title] (admin)
- /music import all (admin; imports supported files from incoming/ one at a time)
- /music import-url <url> --rights-confirmed [title] (admin; requires yt-dlp and an allowlisted source host)
- /music diagnostics (admin; reports native audio-tool availability)
- /music list [search]
- /music play <track-id-or-title> [--all | --player name | --circle radius | --square widthxlength | --region id] [--fade blocks]
- /music playlist create <name>
- /music playlist add <name> <track-id>
- /music playlist list
- /music playlist play <name> [zone options]
- /music queue <track-id-or-title>
- /music pause, /music resume, /music skip, /music stop
- /music nowplaying, /music progress
- /music volume <0-100>, /music mute, /music unmute
- /music seek <seconds>
- /music alias add <external-url> <track-id> (admin)
- /music reload (admin)

A player who starts playback without a target gets a zone centered on their current location. Console playback must specify --all or --player.

## Server setup and aliases

For example, an administrator can map a Spotify or YouTube Music URL to a locally imported, authorized track:

    /music alias add https://open.spotify.com/track/EXAMPLE track-id
    /music play https://open.spotify.com/track/EXAMPLE

Musicolide warns that an alias may point to a different recording and can require confirmation. It does not fetch the stream from the external platform.

## Geyser and Bedrock

The Geyser integration in this branch is experimental. When Geyser-Spigot is installed on the same Paper server, Musicolide builds one aggregate Bedrock mcpack from imported track archives and registers it for Bedrock sessions through Geyser's public resource-pack event. Bedrock players use the same Musicolide session and custom sound identifiers, but they do not receive Java Edition resource packs.

Geyser's current custom-sound translator forwards unmapped custom Java sound identifiers. That is the basis of the shared playback trigger, but it has not yet been validated with a live Bedrock client. The Bedrock library is delivered when a player connects; when a new track changes the pack version, already-connected Bedrock players must reconnect to receive it. A player who has not received the current pack is held out of playback rather than sent silent audio.

This branch does not currently support a separately hosted Geyser proxy without the Geyser plugin/API being available on the Paper server. See docs/GEYSER.md for requirements and the remaining client acceptance tests.

## Client behavior

The first time a Java player needs music, Musicolide sends a tiny stable metadata-only base pack with the consent prompt. After it is accepted, per-track packs are requested without another prompt and use the client's background download path where supported. Unused packs are removed from the active stack, including deferred removal after an in-flight download completes. Declining the base pack prevents custom audio playback. Minecraft controls the actual downloaded ZIP cache on the player's device; the server cannot remotely delete those files.

Minecraft's vanilla sound API does not expose a true pause/seek operation for an already-playing sound. Musicolide uses short segments, so pause/resume and seeking are segment-accurate rather than sample-accurate. This is an inherent vanilla-client limitation; no client mod is installed.

## Configuration

See the fully commented config.yml for all available settings. The resource-pack format is selected automatically from the running server version; do not manually set a pack format. Use /music reload after changing reloadable settings. HTTP listener settings require a server restart.

Important settings include range geometry, prefetch depth, segment duration, cache limits, HTTP hosting, overlap priorities, fade behavior, permissions and messages. URL import is off by default and restricted to administrator-allowlisted hosts; YouTube, Spotify and SoundCloud are blocked as audio sources. Cloud processing remains a design only and is not implemented.

## Status

This repository is under active development. CI verifies compilation against the oldest supported Paper API and tests resource-pack metadata generation for both legacy and modern pack-format eras. Test on staging servers for 1.21.1, 1.21.9, 1.21.11 and 26.3 before production use, particularly resource-pack stacking, client download behavior and positional audio transitions.
