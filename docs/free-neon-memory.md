# Free PostgreSQL memory for Ara

Ara can use a free Neon PostgreSQL database through the `DATABASE_URL` environment
variable. Neon has a free tier; its usage limits and terms can change, so check the
provider dashboard before relying on it for always-on workloads.

## What this adds

- A PostgreSQL JSON key/value store implementing the `get(key)` and `set(key, value)`
  interface used by Ara's supervisor and runtime.
- Automatic creation of the small `ara_kv` table.
- Parameterized SQL; keys and values are never interpolated into SQL.
- A small connection pool (three connections by default).
- No database password or connection string in source control.

## Setup

1. Create a free project at https://neon.com/.
2. Create a database and copy its pooled connection string.
3. Add it to the Render `ara-api` service as the secret environment variable
   `DATABASE_URL`. Do not commit or paste the connection string into GitHub.
4. Deploy this branch and verify the service can connect before enabling any worker.

The API does not start a background worker merely because `DATABASE_URL` exists.
Worker activation, owner-only pause/resume endpoints, and voice recognition are
separate steps and must be tested before autonomous tasks are enabled.

## Free-tier limitation

A free database can suspend when idle and has usage limits. The first request after
idle may be slower. This is suitable for development and modest persistent state,
not a promise of 24/7 compute or high availability. Keep independent backups of
important data.

## Owner-scoped notes API

The API exposes an explicit, small notes resource:

- `GET /v1/memory` reads the authenticated owner's notes.
- `PUT /v1/memory` replaces notes with JSON `{"notes":"..."}`.
- `DELETE /v1/memory` deletes the authenticated owner's notes.

Every request requires the existing app bearer token. The database key contains a
SHA-256 fingerprint of that token, never the token itself. Different tokens map to
different memory records; devices intentionally sharing one token share one owner
memory. Notes are limited to 4,000 characters and responses are marked no-store.
The API does not automatically save chat transcripts or send notes to Gemini.

This is a single-owner-token model, not a multi-account identity system. Do not
reuse the same app token across unrelated people. Token rotation changes the
derived memory namespace, so export/migration support is needed before rotating
a live owner's token. The Android memory screen remains local-only until a
separate user-facing sync/consent control is implemented and tested.
