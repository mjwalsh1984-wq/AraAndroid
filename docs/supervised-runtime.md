# Ara supervised runtime and stop control

## Stop phrase

The supervisor module recognises the exact phrase **bananas** (case-insensitive, surrounding whitespace ignored). The caller must pass the recognised speech transcript or exact text to `handlePhrase`. The module itself does not record audio or perform speech recognition.

The phrase pauses the supervised worker and persists a pause flag using an injected durable store. A separate owner-controlled emergency-stop path is also provided; the phrase is intentionally not the only way to stop Ara. Resuming requires explicit owner confirmation.

## Persistence and deployment

The supervisor requires a durable `get/set` store. Do not connect it to an in-memory map or Render's ephemeral filesystem and describe that as persistent memory. Production deployment needs a durable database (for example managed PostgreSQL) and a separately protected owner-control credential. No database, paid resource, or public control endpoint is created by this code.

## Safety boundaries

- Stop/pause must prevent new work from being claimed and new outbound actions from being dispatched.
- Stop requests should be idempotent and audited without logging secrets or private message contents.
- Outbound messages require fresh, scoped owner approval.
- Financial payments, transfers, and financial decisions remain unavailable to Ara.
- No shutdown evasion, hidden persistence, uncontrolled replication, or self-deployment.
- Self-improvement remains proposal-and-test only until a human reviews and manually deploys a change.

## Integration status

This is a tested supervisor building block. It is not yet wired into the Android microphone, cloud API routes, or a deployed worker. The current Android app is chat-only; it does not yet listen continuously for the stop phrase.
