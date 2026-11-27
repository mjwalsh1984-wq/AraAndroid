# Ara API server

This is a small server-side boundary between the Android app and Google's
Gemini API using its OpenAI-compatible chat-completions endpoint.

## Gemini configuration

Set these values in the Render service environment (never commit keys):

- `ARA_PROVIDER_URL=https://generativelanguage.googleapis.com/v1beta/openai/chat/completions`
- `ARA_PROVIDER_MODEL=gemini-3.5-flash-lite` (a low-cost/free-tier starting candidate; verify it is enabled for your Google AI Studio key)
- `ARA_PROVIDER_API_KEY`: the Gemini API key created in Google AI Studio.
- `ARA_APP_TOKEN`: a separate strong random token used only between the Android app and Ara's server.

Google documents the OpenAI-compatible endpoint and Bearer authentication here:
https://ai.google.dev/gemini-api/docs/openai

Create/manage a Gemini key at https://aistudio.google.com/apikey.
Do not paste either key into ChatGPT, GitHub, or source files. Enter the Gemini
key only into Render's secret environment settings. The app token must be
provisioned separately into Render and the Android app using a secure setup
flow; it must never be committed into the APK or repository.

## Important free-tier privacy note

Google's Gemini API pricing page states that free-tier content may be used to
improve Google's products, while paid-tier content is not used for that
purpose. Do not send private, sensitive, financial, health, or other
confidential information through a free-tier key. Check current terms and
model availability before enabling real conversations:
https://ai.google.dev/gemini-api/docs/pricing

## Required host settings

- `ARA_APP_TOKEN`: separate long random app-to-server token.
- `ARA_PROVIDER_URL`: complete provider chat-completions URL.
- `ARA_PROVIDER_API_KEY`: provider key, held only by the server.
- `ARA_PROVIDER_MODEL`: model identifier accepted by that provider.

Run with Node.js 22 by importing `createAraApiServer` from
`cloud/ara-api-server.mjs` and listening on the host-provided port. Render
provides HTTPS termination.

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
file. No paid hosting or paid API tier is authorised by this setup.
