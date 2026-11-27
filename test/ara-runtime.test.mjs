import test from "node:test";
import assert from "node:assert/strict";
import { createAraRuntime } from "../cloud/ara-runtime.mjs";

function memoryStore() {
  const data = new Map();
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
  let clock = 100;
  const runtime = createAraRuntime({
    store,
    now: () => clock,
    handlers: { research: async ({ topic }) => ({ researched: topic }) },
  });
  const task = await runtime.enqueue({ type: "research", payload: { topic: "cloud reliability" } });
  await runtime.start();
  for (let i = 0; i < 30; i += 1) {
    const status = await runtime.status();
    if (status.completed === 1) break;
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
  let clock = 100;
  let calls = 0;
  const runtime = createAraRuntime({
    store,
    now: () => clock,
    config: { pollIntervalMs: 2, retryDelayMs: 0, maxAttempts: 2 },
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
