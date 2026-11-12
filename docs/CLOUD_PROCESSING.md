# Cloud processing service design

This document defines the planned contract for making Musicolide usable on managed Minecraft hosts that cannot install FFmpeg/FFprobe or expose an inbound HTTP port.

## Goals

- Keep the Paper plugin responsible for commands, permissions, SQLite catalogue, playlists, queues, zones and playback.
- Allow administrators to select `local`, `cloud` or `auto` processing.
- Keep local processing fully usable without an external account.
- Make cloud processing opt-in; never upload audio merely because a URL is configured.
- Host generated resource-pack ZIPs at stable HTTPS URLs returned by the service.

## Target configuration

The following is a design example, not an active configuration schema in the current plugin build:

```yaml
processing:
  mode: local # local | cloud | auto

  local:
    ffmpeg: "ffmpeg"
    ffprobe: "ffprobe"

  cloud:
    endpoint: "https://musicolide-service.example"
    api-key: ""
    connect-timeout-seconds: 10
    request-timeout-minutes: 30
    max-upload-mb: 100
```

## Target import flow

1. Plugin validates that the selected source is inside `plugins/Musicolide/incoming/` and within configured limits.
2. In `local` mode, the local backend performs FFprobe validation, OGG segmentation and resource-pack generation.
3. In `cloud` mode, the plugin submits the source file with a server-scoped credential and idempotency key.
4. The service returns a job ID. The plugin polls job status with bounded backoff rather than holding a long HTTP request open.
5. On completion, the service returns track metadata and a stable HTTPS resource-pack URL, pack SHA-1 and file size.
6. The plugin stores the metadata and uses the remote pack URL for resource-pack delivery. Playback controls remain entirely in the plugin.
7. In `auto` mode, local processing is attempted first. Fallback is allowed only for local-tool-unavailable errors, not for invalid audio, rights/validation failures or arbitrary processing errors.

## API shape

- `POST /v1/jobs`: multipart source upload; requires authentication and an idempotency key.
- `GET /v1/jobs/{jobId}`: queued/running/succeeded/failed state and progress.
- `GET /v1/tracks/{trackId}`: immutable metadata and pack URL.
- `DELETE /v1/jobs/{jobId}`: cancel an unstarted job where supported.

The service must limit concurrent jobs, upload size, audio duration, output size and per-server storage. It must validate all returned metadata and never accept arbitrary filesystem paths from the plugin.

## Storage and security

- Use per-server API keys with revocation and server-scoped quotas.
- Store uploaded source audio only as long as required by the configured retention policy; allow deletion after successful conversion.
- Keep generated resource packs in durable object storage or a persistent volume, not an ephemeral application filesystem.
- Return HTTPS URLs with immutable content hashes.
- Do not expose FFmpeg command construction to callers; use fixed, argument-array process invocations and strict input validation.
- Do not fetch audio from streaming-platform URLs. The service accepts administrator-uploaded audio that the server operator is authorized to distribute.

## Free-tier deployment notes

A conventional VM/container is needed for FFmpeg jobs. Serverless edge functions are suitable for authentication, job dispatch and metadata, but not for running the encoder itself under very small CPU limits. A free application tier that sleeps or has ephemeral storage is useful for demos, not for a dependable shared music library.

## Current status

This is the target contract only. The current plugin build still performs local conversion. Do not set cloud mode in a live config until the remote backend and service are implemented and released together.
