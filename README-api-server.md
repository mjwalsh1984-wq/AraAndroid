# Ara API server

This is a small server-side boundary between the Android app and an
OpenAI-compatible chat endpoint. It does not provision hosting or configure an
AI provider.

## Required host secrets / settings

- `ARA_APP_TOKEN`: long random token shared with the provisioned Android client.
- `ARA_PROVIDER_URL`: complete provider chat-completions URL.
- `ARA_PROVIDER_API_KEY`: provider key, held only by the server.
- `ARA_PROVIDER_MODEL`: model identifier accepted by that provider.

Run with Node.js 22 by importing `createAraApiServer` from
`cloud/ara-api-server.mjs` and listening on the host-provided port. Put it
behind the host's HTTPS termination. Do not expose public plain HTTP.

## Endpoints

- `GET /health`: public liveness/configuration status; never returns secrets.
- `POST /v1/chat`: requires `Authorization: Bearer <ARA_APP_TOKEN>` and a
  JSON body containing 1–30 user/assistant messages.

The API validates message shape and size, limits request bodies, times out
provider requests, does not log message content, and does not relay provider
error bodies. It does not provide persistent chat history, user accounts,
per-user token rotation, or distributed rate limiting yet.

The Android app is not connected to this API yet. Do not put the provider API
key in the APK, source code, GitHub Actions logs, or a committed environment
file. No provider or paid hosting has been selected or provisioned.
