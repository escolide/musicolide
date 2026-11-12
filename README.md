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
2. Install FFmpeg and FFprobe on the server machine and ensure both executables are on your PATH (or configure their paths).
3. Run: ./gradlew clean test shadowJar

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

## Geyser and Bedrock roadmap

Geyser is an explicit compatibility target, but this Java Edition build does not yet claim Bedrock playback. Geyser can deliver Bedrock-format resource packs to Bedrock clients, but it does not translate Java resource packs. Bedrock packs need their own manifest and sound definitions, and pack registration follows the Bedrock connection lifecycle. Newly imported tracks may require a reconnect before they are available to a Bedrock player. The custom sound-trigger path must also be verified against the supported Geyser API before Bedrock playback is enabled.

See docs/GEYSER.md for the integration plan and current limitations.

## Client behavior

The first time a player needs a track, Minecraft may show a resource-pack prompt and download it. Declining the pack prevents custom audio playback. Later uses can be served from the client's resource-pack cache.

Minecraft's vanilla sound API does not expose a true pause/seek operation for an already-playing sound. Musicolide uses short segments, so pause/resume and seeking are segment-accurate rather than sample-accurate. This is an inherent vanilla-client limitation; no client mod is installed.

## Configuration

See the fully commented config.yml for all available settings. The resource-pack format is selected automatically from the running server version; do not manually set a pack format. Use /music reload after changing reloadable settings. HTTP listener settings require a server restart.

Important settings include range geometry, prefetch depth, segment duration, cache limits, HTTP hosting, overlap priorities, fade behavior, permissions and messages. Cloud processing is being designed as a separate deployable service; this branch adds the bulk-import command but does not yet enable cloud mode.

## Status

This repository is under active development. CI verifies compilation against the oldest supported Paper API and tests resource-pack metadata generation for both legacy and modern pack-format eras. Test on staging servers for 1.21.1, 1.21.9, 1.21.11 and 26.3 before production use, particularly resource-pack stacking, client download behavior and positional audio transitions.
