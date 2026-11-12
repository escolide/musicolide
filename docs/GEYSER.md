# Geyser / Bedrock integration plan

## Status

**Experimental implementation in the current feature branch; live-client acceptance is still pending.**

The branch now:
- Builds one aggregate Bedrock `.mcpack` from OGG assets inside retained Java track-pack archives.
- Writes a Bedrock `manifest.json` and `sounds/sound_definitions.json` with the same namespaced custom sound identifiers used by Musicolide.
- Uses an optional compile-only Geyser API adapter, loaded only when Geyser-Spigot is detected.
- Registers the aggregate pack for each incoming Bedrock session through `SessionLoadResourcePacksEvent`.
- Uses the shared Musicolide playback session engine for Bedrock clients without sending them Java Edition resource packs.
- Requires Bedrock players to reconnect when a new import changes the aggregate pack.

This is not a release-level compatibility claim: the repository's CI does not run a Bedrock client, and no live client test has been completed.

## Design boundaries

- Geyser remains optional. Paper servers without Geyser must start and run Musicolide normally.
- Keep the catalogue, playlists, queues, session clock, spatial arbitration, permissions and player targeting shared.
- Use separate Java and Bedrock output pipelines. Do not put Java pack metadata in the Bedrock pack directory or assume Geyser converts Java packs.
- Keep the audio source pipeline edition-neutral: authorized source audio is encoded to the required output assets, with stereo as the only retained mix.
- Bedrock-specific delivery and playback must be selected per player. Do not send Java resource packs to Bedrock players.

## Bedrock resource-pack requirements

A Bedrock pack needs its own manifest.json, UUID/version metadata, Bedrock sound definitions and Bedrock-compatible asset paths. Geyser supports sending Bedrock resource packs from its packs directory or through its API. For remote packs, the download must be direct and the HTTP response must include the exact Content-Length and Content-Type application/zip.

Geyser's API exposes pack lifecycle events including SessionLoadResourcePacksEvent and GeyserDefineResourcePacksEvent. A pack registered for a player is delivered as part of the connection resource-pack flow.

## Lifecycle constraint

Bedrock clients apply server resource packs when connecting. Unlike the Java pack stack used by Musicolide, a Bedrock client cannot be assumed to accept arbitrary new packs during an existing session. A per-track ZIP-per-playback design is therefore not appropriate for Geyser.

Initial Bedrock support uses one stable-UUID music library pack. Its version is refreshed when imported track assets change. Geyser registers the pack during the Bedrock connection resource-pack phase; Musicolide does not assume it can add the pack to an already-connected client. When the library updates, existing Bedrock players are asked to reconnect. No player is transferred or restarted automatically.

## Sound-trigger path and remaining verification

Musicolide calls the normal Bukkit player sound API with its namespaced custom sound identifier. In the current Geyser source, the Java sound translator passes an unmapped custom Java sound identifier through to a Bedrock play-sound packet; the generated Bedrock pack defines that same identifier in its sound definitions. This provides a source-level shared-trigger path without reflection into Geyser internals or unversioned packet classes.

This is still a hypothesis until a real Bedrock client confirms sound playback. Verify start, stop, volume, positional attenuation/fade, segment continuity and multiple simultaneous sessions. Also verify pack acceptance and updates on each target Bedrock platform. If an official Geyser release changes custom-sound translation, disable Bedrock playback until the adapter has been retested.

## Acceptance tests before claiming support

- Java-only server starts with no Geyser classes or files present.
- Geyser-Paper server identifies Bedrock players without misclassifying Java players.
- A Bedrock pack with a valid manifest and one test sound is accepted and applied on initial connection.
- A test sound can be started, stopped, faded and synchronized for one Bedrock player without affecting nearby Java players.
- Multiple concurrent sessions remain correctly targeted.
- Newly imported music is available to already-connected Bedrock players only through a tested pack refresh/reconnect workflow.
- Test on at least one non-console Bedrock client and one console/client platform where the pack acceptance model differs.

## References

- Geyser API: https://geysermc.org/wiki/geyser/api/
- Geyser resource packs: https://geysermc.org/wiki/geyser/packs/
- Geyser API setup: https://geysermc.org/wiki/geyser/getting-started-with-the-api/

These upstream APIs and client behaviors must be rechecked when the Geyser version targeted by the implementation is selected.
