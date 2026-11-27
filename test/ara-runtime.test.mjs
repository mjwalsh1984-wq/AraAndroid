import test from "node:test";
import assert from "node:assert/strict";
import { createAraRuntime } from "../cloud/ara-runtime.mjs";

function memoryStore(initial = []) {
  const data = new Map([["ara.runtime.queue.v1", structuredClone(initial)]]);
  return {
    get: async (key) => data.get(key),
    set: async (key, value) => data.set(key, structuredClone(value)),
  };
}

test("runtime only accepts allowlisted task types", async () => {
  const runtime = createAraRuntime({ store: memoryStore(), handlers: { research: async () => "ok" } });
  await assert.rejects(runtime.enqueue({ type: "send_unsolicited_message" }), /allowlisted/);
});

test("runtime executes queued task and records completion", async () => {
  const store = memoryStore();
  const runtime = createAraRuntime({
    store,
    handlers: { research: async ({ topic }) => ({ researched: topic }) },
  });
  const task = await runtime.enqueue({ type: "research", payload: { topic: "cloud reliability" } });
  await runtime.start();
  for (let i = 0; i < 30; i += 1) {
    if ((await runtime.status()).completed === 1) break;
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
  await runtime.stop();
  const status = await runtime.status();
  assert.equal(status.completed, 1);
  assert.equal(status.queued, 0);
  assert.ok(task.id);
});

test("runtime retries a failed task and then marks it failed", async () => {
  const store = memoryStore();
  let calls = 0;
  const runtime = createAraRuntime({
    store, config: { pollIntervalMs: 2, retryDelayMs: 0, maxAttempts: 2 },
    handlers: { flaky: async () => { calls += 1; throw new Error("temporary"); } },
  });
  await runtime.enqueue({ type: "flaky" });
  await runtime.start();
  for (let i = 0; i < 50; i += 1) {
    if ((await runtime.status()).failed === 1) break;
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
  await runtime.stop();
  assert.equal(calls, 2);
  assert.equal((await runtime.status()).failed, 1);
});

test("runtime recovers an expired task lease after a worker restart", async () => {
  let clock = 1000;
  const store = memoryStore([{
    id: "interrupted-task", type: "research", payload: {}, runAt: 0,
    attempts: 1, status: "running", createdAt: 0, leaseOwner: "old-worker",
    leaseUntil: 900, lastError: null,
  }]);
  let calls = 0;
  const runtime = createAraRuntime({
    store, now: () => clock,
    config: { pollIntervalMs: 2, retryDelayMs: 0, taskLeaseMs: 100 },
    handlers: { research: async () => { calls += 1; return "recovered"; } },
  });
  await runtime.start();
  for (let i = 0; i < 30; i += 1) {
    if ((await runtime.status()).completed === 1) break;
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
  await runtime.stop();
  assert.equal(calls, 1);
  assert.equal((await runtime.status()).completed, 1);
});

test("runtime does not overwrite a task enqueued while a handler is running", async () => {
  const store = memoryStore();
  let release;
  const blocked = new Promise((resolve) => { release = resolve; });
  const runtime = createAraRuntime({
    store, config: { pollIntervalMs: 2 },
    handlers: { slow: async () => { await blocked; return "done"; }, quick: async () => "queued" },
  });
  await runtime.enqueue({ type: "slow" });
  await runtime.start();
  for (let i = 0; i < 30; i += 1) {
    if ((await runtime.status()).runningTasks === 1) break;
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
  await runtime.enqueue({ type: "quick" });
  release();
  for (let i = 0; i < 30; i += 1) {
    if ((await runtime.status()).completed === 2) break;
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
  await runtime.stop();
  assert.equal((await runtime.status()).completed, 2);
});
