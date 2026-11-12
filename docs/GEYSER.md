# Geyser / Bedrock integration plan

## Status

**Planned; not yet enabled for playback.** Musicolide currently generates Java Edition resource packs and emits Java custom sounds. A Bedrock player joining through Geyser must not be advertised as supported until the Bedrock sound-trigger path has been tested end to end.

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

Initial Bedrock support should use a stable, versioned library pack that is available before a Bedrock player connects. If the server imports new tracks after a player has joined, the client may need to reconnect before those tracks are available. Do not silently restart or transfer players; any reconnect workflow must be an explicit administrator/player choice.

## Sound-trigger investigation gate

The Geyser API provides connection identification and resource-pack registration, but the pack APIs alone do not prove that Java Edition custom sound identifiers will be played by Bedrock clients. Before enabling the Bedrock playback adapter, verify one of these paths against the supported Geyser version:

1. A supported Geyser API route for emitting a custom Bedrock sound to one connection; or
2. A documented and tested translation path from Musicolide's Java custom-sound trigger to the custom Bedrock sound identifier.

If neither route exists, Bedrock sound emission requires a separate Geyser extension or supported protocol-level integration. Do not use reflection into Geyser internals or unversioned packet classes as a production solution.

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
