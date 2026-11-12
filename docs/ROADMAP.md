# Musicolide project objectives and implementation status

This file is the working scope for Musicolide. It separates the agreed product objectives from features already present in the code. A planned item must not be described as working until its implementation and tests exist.

## 1. Platform and client experience

| Objective | Status |
|---|---|
| Paper-first Java Edition plugin | Implemented baseline |
| Purpur compatibility | Expected through Paper compatibility; staging verification required |
| Spigot compatibility where feasible | Not currently supported |
| No client mods, browser, voice-chat mod or external player | Core design |
| Java client minimum of 1.20.3 for resource-pack stacking | Target; enforce and test before release |
| One initial resource-pack consent flow, then background pack delivery where supported | Not yet verified end to end |
| Small permanent base pack plus reusable/stacked track content | Planned refinement; current implementation is per-track packs |
| Avoid repeated prompts and unnecessary downloads | Planned; must be tested on real clients |
| Remove unused packs from the active stack | Partial Java implementation; does not erase client cache |
| Never promise remote deletion of a downloaded pack from a player's device | Platform limitation |

Minecraft's client owns its downloaded-pack cache. Removing a pack from the active stack is not the same as deleting the downloaded ZIP from the player's device. Musicolide must not claim it can enforce a hard client-side cache size.

## 2. Playback and controls

- Shared session engine for independent sessions in different locations.
- Playback to all players, selected players, one player, or players inside a configured zone.
- Circle, square and optional WorldGuard region targeting.
- Per-player volume, mute, stop, pause/resume, skip, queue and playlist controls.
- Multiple concurrent zones with deterministic overlap arbitration and configurable priority.
- Stereo custom sounds; fade behavior should adjust the stereo playback path rather than ship duplicate mono assets.
- Configurable track fade-in/fade-out and smooth zone transitions, with vanilla-client limitations documented.
- No physical jukebox or music disc requirement.

The current Java playback engine and command system provide a baseline for sessions, zones, playlists, queues, player preferences and custom sounds. Fine-grained fade behavior and mixed-edition parity still require verification.

## 3. Audio processing and source handling

| Objective | Status |
|---|---|
| OGG output compatible with vanilla custom sounds | Implemented Java pipeline |
| Stereo-only assets; no duplicate mono conversion | Implemented in this feature branch; CI pending |
| One global processing job at a time | Implemented in this feature branch; CI pending |
| Bounded processing queue | Implemented in this feature branch; CI pending |
| FFmpeg limited to one thread by default | Implemented in this feature branch; CI pending |
| FFprobe/FFmpeg timeouts and safe process shutdown | Implemented in this feature branch; CI pending |
| Process I/O and conversion off the Bukkit main thread | Existing architecture; review continuously |
| Delete temporary OGG segments after pack creation | Implemented in this feature branch; CI pending |
| Delete original input after successful import by default, with a retention option | Implemented in this feature branch; CI pending |
| Crash cleanup of stale temporary files and pack retention/TTL | Planned |
| Optional bundled native executables, extraction and startup diagnostics | Extraction/diagnostics implemented; binary payloads not included and host permissions unverified |
| Verify MineStrator/native-process compatibility | Open investigation; do not assume ProcessBuilder is allowed |

The audio worker remains independent from the server tick thread. It uses fixed argument arrays, path validation, input/duration/queue limits and process timeouts. The single worker awaits yt-dlp completion before starting FFprobe/FFmpeg. Bundled executables are supported by the extraction code, but the repository does not contain third-party native binaries; release packaging and platform permission tests remain necessary.

## 4. Source links, rights and metadata

- A future /music play <link> flow may accept YouTube, Spotify and other URLs as identifiers for lookup or matching.
- A platform URL is not permission to download, cache or rebroadcast the platform's audio.
- External links may resolve metadata or map to an independently imported recording that the server operator is authorized to distribute.
- Any future source adapter must comply with the provider's current terms and separately establish distribution rights.
- Do not bypass DRM, account restrictions, paywalls or platform protections.
- yt-dlp is not a rights grant and must not be used to imply that restricted streaming audio can be redistributed.

The current alias implementation points external IDs at locally imported audio; it does not extract audio from the platform.

## 5. Local and cloud processing

Target administrator-selectable modes:

- local: use the server's configured or bundled audio tools.
- cloud: opt in to a separately deployed Musicolide service for authorized uploads and pack generation.
- auto: prefer local processing and fall back only when local tools are unavailable and cloud is explicitly configured.

The cloud service and local/cloud/auto selection are not implemented. The design must include per-server credentials, revocation, upload limits, job ownership, idempotency, bounded polling, output validation, HTTPS artifact hosting, quotas and explicit source/pack retention. No audio is uploaded to a third party.

## 6. Resource-pack delivery and storage

- Java 1.20.3+ resource-pack stacking is the target for the one-time consent and subsequent background pack workflow.
- Reuse identical pack content and hashes. Avoid creating unique packs when an existing immutable pack can be reused.
- Keep the base pack small; group or reuse track assets where practical.
- Remove unused packs from the active stack after playback when the client API supports it.
- Never assume unapplying a pack removes the downloaded file from client storage.
- Keep generated packs available while a player may still be downloading or using them.
- Prefer external HTTPS/object storage for public distribution where the host cannot expose a stable inbound port.
- Use configurable server-side retention and safe cleanup; do not delete an artifact while a download is in progress.

## 7. Geyser and Bedrock Edition

**Experimental implementation on this branch; live Bedrock client acceptance is still pending.**

- Geyser remains optional. Java-only servers must not require Geyser classes, configuration or files.
- Detect Bedrock players and select the correct edition-specific adapter.
- Reuse the shared catalogue, sessions, queues, playlists, targeting and arbitration.
- Generate one aggregate Bedrock resource pack with its own manifest, sound definitions and compatible OGG assets. Java packs are not Bedrock packs and Geyser does not convert them. (Implemented; test with clients.)
- Use documented Geyser APIs for player identification and per-connection pack registration. (Implemented with optional Geyser-Spigot API integration.)
- Bedrock pack registration is tied to the connection lifecycle. Do not assume a new per-track pack can be added to an already connected player. Initial support may require a stable library pack or an explicit reconnect/repack workflow.
- Use the same namespaced custom sound identifiers through Geyser's custom-sound translation path. (Implemented at source level; live Bedrock playback remains unverified.)
- Test start, stop, volume, fade, synchronization, player targeting, multiple sessions and Java/Bedrock coexistence with real clients.

See GEYSER.md for the detailed integration boundary and acceptance tests.

## 8. Release and verification gates

A release should not be considered complete until:

1. Gradle clean test shadowJar succeeds in CI.
2. The generated JAR loads on the minimum Paper version and a current supported Paper version.
3. Purpur compatibility is tested.
4. Resource-pack prompt, stacking, cache reuse and removal behavior are checked on real Java clients.
5. The minimum supported Java client protocol is enforced or clearly constrained by the server setup.
6. Local native processing is tested on the target host, including MineStrator if used.
7. Cloud fallback is tested only after the service exists.
8. Geyser/Bedrock pack delivery and sound emission pass the acceptance tests in GEYSER.md.
9. Rights, security, storage retention and failure behavior are documented.

CI compilation does not replace staging-server or real-client testing.
