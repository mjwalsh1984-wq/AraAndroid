# Ara cloud autonomy foundation

This is an additive foundation for owner-controlled cloud operation. It does not
deploy a server, create cloud resources, start background processes, or copy
itself. A hosting provider, credentials, deployment workflow and runtime
integration must be configured separately.

## Modules

- `cloud/ara-cloud-manager.mjs`: registers a small, explicitly approved set
  of named instances, accepts heartbeats, reports stale instances, stores
  snapshots separately, enforces snapshot retention and exposes the snapshot
  interval check.
- `cloud/ara-improvement-pipeline.mjs`: stores improvement proposals, runs
  supplied tests and marks passing proposals for owner review. It never deploys
  a patch.
- `cloud/ara-action-gateway.mjs`: default-deny capability gateway with an audit log. Outbound communication requires a fresh explicit approval; financial actions, uncontrolled replication and shutdown evasion are blocked.\n- `cloud/ara-runtime.mjs`: a worker loop with a persistent task queue,
  allowlisted handlers, bounded retries, heartbeat reporting, expiring task
  leases and recovery of interrupted tasks. The host platform must supervise
  and restart the process; this module does not evade shutdown.

## Operating model

1. The owner provisions and pays for hosting.
2. The owner explicitly approves each instance and sets the instance limit.
3. Instances use a persistent store supplied by the host application.
4. The host application schedules heartbeats and calls the snapshot API.
5. A supervisor restarts the worker after an unexpected process failure.
6. Ara can propose improvements; tests run in a disposable sandbox.
7. A human reviews and manually deploys approved changes.

## Reliability limits

The runtime currently assumes one active worker using a simple asynchronous
`get(key)` / `set(key, value)` store. It serializes queue changes inside that
process and uses task leases to recover work after a crash. This is not a
distributed lock: do not run multiple active workers against the same store
until a transactional compare-and-set or database claim implementation exists.
Task handlers should be idempotent because a crash after an external side
effect but before completion is recorded can cause a retry.

Snapshots are stored in the configured persistent store under a separate key.
The default retention is 20 snapshots per instance. The host must call
`isSnapshotDue` and `recordSnapshot`; no scheduler is provisioned here.

## External action control\n\nExternal integrations must be exposed only as named capabilities registered with the action gateway. Do not give task handlers raw credentials or unrestricted APIs. Every send operation must use a fresh, expiring approval token and log its outcome. The gateway is a guardrail in application code, not a substitute for provider-side least-privilege credentials.\n\n## Security boundaries

- No hidden or uncontrolled replication.
- No evasion of shutdown, owner controls or emergency access.
- No autonomous credential creation, privilege escalation or secret extraction.
- No outbound messages without explicit approval by the host application.
- No financial decisions, payments or transfers.
- No deletion of important data without explicit owner approval.
- Store secrets in the hosting provider's secret manager; never commit them.

## Integration

The modules are framework-neutral ES modules. The host app must provide a
persistent store implementing asynchronous `get(key)` and `set(key, value)`.
The improvement pipeline also requires a `runTests({ patch, tests })` callback
that executes in a restricted sandbox.

Tasks must be explicitly added to the queue and their handler type must be
allowlisted by the host. External actions should be implemented only through
narrowly scoped handlers with their own permissions and approval checks. The
runtime does not grant arbitrary shell, filesystem, messaging or financial access.

Run the foundation tests with:

```sh
node --test test/*.test.mjs
```
