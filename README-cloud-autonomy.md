# Ara cloud autonomy foundation

This is an additive foundation for owner-controlled cloud operation. It does not
deploy a server, create cloud resources, start background processes, or copy
itself. A hosting provider, credentials, deployment workflow and runtime
integration must be configured separately.

## Modules

- `cloud/ara-cloud-manager.mjs`: registers a small, explicitly approved set
  of named instances, accepts heartbeats, reports stale instances and records
  snapshots when explicitly approved.
- `cloud/ara-improvement-pipeline.mjs`: stores improvement proposals, runs
  supplied tests and marks passing proposals for owner review. It never deploys
  a patch.
- `cloud/ara-runtime.mjs`: an always-on worker loop with a persistent task queue,
  allowlisted handlers, bounded retries, heartbeats and clean shutdown. The host
  platform must supervise/restart the process; this module does not evade shutdown.

## Operating model

1. The owner provisions and pays for hosting.
2. The owner explicitly approves each instance and sets the instance limit.
3. Instances use a persistent store supplied by the host application.
4. The host application schedules heartbeats and snapshots.
5. Ara can propose improvements; tests run in a disposable sandbox.
6. A human reviews and manually deploys approved changes.

## Security boundaries

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

Tasks must be explicitly added to the queue and their handler type must be allowlisted by the host. External actions should be implemented only through narrowly scoped handlers with their own permissions and approval checks. The runtime does not grant arbitrary shell, filesystem, messaging or financial access.

Run the foundation tests with:

```sh
node --test test/cloud-foundation.test.mjs
```
