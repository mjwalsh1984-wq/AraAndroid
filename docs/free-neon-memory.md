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
