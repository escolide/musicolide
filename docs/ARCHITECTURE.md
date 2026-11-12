# Musicolide architecture notes

## Runtime flow

1. An administrator imports an audio file that they are authorized to distribute.
2. The audio worker validates input size/duration and uses FFmpeg to create stereo and mono OGG segments.
3. The pack builder creates a per-track ZIP with a unique namespace, sound definitions and the segments.
4. The built-in HTTP server exposes the ZIP through the configured public base URL.
5. A playback session requests the current track and a bounded number of upcoming tracks from each listener.
6. Resource-pack status events mark packs ready. Segment playback begins only after the current pack is loaded.
7. The spatial engine picks at most one eligible session per player. The first-started session wins by default.
8. A per-player sound is emitted in stereo inside the base zone, or mono from the closest zone boundary in the optional fade band.

## Vanilla audio constraints

- The server cannot issue a true pause or seek command to a running vanilla sound instance.
- Playback therefore advances through short OGG segments; seeking resumes at the segment containing the requested time.
- Segment duration trades control responsiveness against resource-pack event count and network/client overhead.
- Resource-pack acceptance is client-controlled. A declined pack means the client cannot hear the track.
- Stereo and mono assets are prepared separately because vanilla clients infer positional attenuation from audio channel layout.

## Spatial arbitration

Each tick refresh computes candidate sessions for each online player. A candidate is eligible when the player is in the base zone, or in its configured fade band. Candidates are ordered by manual priority when enabled, then by start timestamp, then by stable session ID. Only the winner receives playback. Session owners whose candidates lose for at least one player receive a cooldown-limited overlap warning.

## Source adapters

The repository stores local, rights-cleared audio. External source adapters are intentionally not implemented as extractors. URL aliases only resolve an external identifier to an already imported local track. Future adapters must independently satisfy the source provider's current API terms and grant rights for server-side distribution.


## Processing backends (planned)

The intended backend abstraction has three administrator-selectable modes:

- `local`: run FFmpeg/FFprobe in the Minecraft server environment.
- `cloud`: upload an authorized source file to a separately deployed Musicolide processing service; the service performs probing, segmentation, OGG encoding and pack generation.
- `auto`: prefer local processing when the configured executables are available, then fall back to the configured cloud service when local processing is unavailable.

The cloud service should also host the generated pack artifacts and return stable HTTPS pack URLs, because some managed Minecraft hosts do not expose a usable inbound port for the plugin's built-in HTTP server. The plugin must retain the local playback catalogue and playback/session logic.

The remote API must use per-server credentials, enforce configured upload and duration limits, validate file types and job ownership, return structured job status, and support idempotency keys. Source audio and generated assets must be retained only according to an explicit storage policy. The plugin must never send audio to a third party without the administrator explicitly configuring cloud mode.

**Implementation status:** the existing importer still uses the local FFmpeg processor. The cloud mode/API is an architectural target, not an active configuration option yet. The bulk import command is implemented separately and processes one file at a time to avoid resource spikes on shared hosts.
