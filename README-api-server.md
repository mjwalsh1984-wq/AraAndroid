# Ara API server

This is the HTTPS boundary between the Android app, Ara's Neon-backed private memory, and the configured AI provider.

## Authentication

Ara uses Neon Managed Better Auth. The Android app signs in or creates an account against the branch Auth URL, keeps its session cookie encrypted with Android Keystore, and obtains short-lived signed JWTs for API requests. The API verifies each JWT against the branch JWKS and requires the issuer to match the Neon Auth origin.

Required Render environment variables:

- `NEON_AUTH_BASE_URL`: the production branch's Managed Better Auth base URL.
- `NEON_AUTH_JWKS_URL`: the matching production branch's JWKS URL.
- `DATABASE_URL`: Neon PostgreSQL connection string (secret; already configured in Render).

Never put session cookies, JWTs, database credentials, or provider keys in source control, app logs, or support messages.

## AI provider configuration

Set these values in the Render service environment (never commit keys):

- `ARA_PROVIDER_URL`: complete OpenAI-compatible chat-completions URL.
- `ARA_PROVIDER_MODEL`: model identifier accepted by the provider.
- `ARA_PROVIDER_API_KEY`: provider API key, held only by the server.

The provider key stays server-side. The Android app only receives Ara's reply.

## Endpoints

- `GET /health`: public liveness/configuration status; never returns secrets.
- `POST /v1/chat`: requires a valid Neon Auth JWT in `Authorization: Bearer <jwt>`.
- `GET /v1/memory`: returns notes belonging to the verified JWT subject.
- `PUT /v1/memory`: saves notes belonging to the verified JWT subject (maximum 4,000 characters).
- `DELETE /v1/memory`: deletes only the verified user's cloud notes.

The API rejects missing, malformed, expired, or invalid JWTs. User IDs come only from the verified JWT subject; clients cannot select or override the memory owner. Memory keys are one-way fingerprints of that subject. Chat transcripts are not automatically stored. The Android app stores the auth session cookie and short-lived JWT encrypted with Android Keystore and does not persist the password.

## Operational boundaries

The API does not send email, WhatsApp, or other outbound messages. It does not perform payments or financial actions. External actions and code changes remain separately permission-gated. No paid hosting or paid API tier is authorised by this setup.
