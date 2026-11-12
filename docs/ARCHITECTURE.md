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
