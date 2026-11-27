import test from "node:test";
import assert from "node:assert/strict";
import { createAraSupervisor } from "../cloud/ara-supervisor.mjs";

function fixture(initial) {
  const values = new Map(initial ? [["ara.supervisor.state.v1", initial]] : []);
  const calls = { starts: 0, stops: 0 };
  const store = { get: async (key) => values.get(key), set: async (key, value) => values.set(key, structuredClone(value)) };
  const runtime = { start: async () => { calls.starts += 1; }, stop: async () => { calls.stops += 1; } };
  return { supervisor: createAraSupervisor({ store, runtime, now: () => 1234 }), calls };
}

test("only the exact bananas phrase pauses the worker", async () => {
  const { supervisor, calls } = fixture();
  assert.equal((await supervisor.handlePhrase("hello bananas")).accepted, false);
  assert.equal((await supervisor.mayRun()), true);
  const result = await supervisor.handlePhrase(" BANANAS ");
  assert.equal(result.accepted, true);
  assert.equal((await supervisor.mayRun()), false);
  assert.equal(calls.stops, 1);
});

test("independent emergency stop also pauses the worker", async () => {
  const { supervisor, calls } = fixture();
  await supervisor.emergencyStop();
  assert.equal((await supervisor.status()).reason, "independent_owner_emergency_stop");
  assert.equal(await supervisor.mayRun(), false);
  assert.equal(calls.stops, 1);
});

test("resume requires explicit owner confirmation", async () => {
  const { supervisor, calls } = fixture({ paused: true, reason: "voice_or_text_stop_phrase", pausedAt: 100 });
  await assert.rejects(supervisor.resume(), /owner confirmation/);
  assert.equal(calls.starts, 0);
  await supervisor.resume({ ownerConfirmed: true });
  assert.equal(await supervisor.mayRun(), true);
  assert.equal(calls.starts, 1);
});

test("paused state survives supervisor recreation when the supplied store is durable", async () => {
  const values = new Map();
  const store = { get: async (key) => values.get(key), set: async (key, value) => values.set(key, structuredClone(value)) };
  const first = createAraSupervisor({ store, runtime: { start: async () => {}, stop: async () => {} } });
  await first.handlePhrase("bananas");
  const second = createAraSupervisor({ store, runtime: { start: async () => {}, stop: async () => {} } });
  assert.equal(await second.mayRun(), false);
});
