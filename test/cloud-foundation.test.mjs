import test from "node:test";
import assert from "node:assert/strict";
import { createCloudManager } from "../cloud/ara-cloud-manager.mjs";
import { createImprovementPipeline } from "../cloud/ara-improvement-pipeline.mjs";

function memoryStore() {
  const data = new Map();
  return { get: async (key) => data.get(key), set: async (key, value) => data.set(key, structuredClone(value)) };
}

test("cloud manager requires owner approval and enforces instance limit", async () => {
  const manager = createCloudManager({ store: memoryStore(), limits: { maxInstances: 1 } });
  await assert.rejects(manager.register({ name: "primary" }), /Owner approval/);
  const instance = await manager.register({ name: "primary", ownerApproved: true });
  await assert.rejects(manager.register({ name: "backup", ownerApproved: true }), /limit/);
  assert.equal(instance.name, "primary");
});

test("heartbeat marks an instance healthy", async () => {
  const manager = createCloudManager({ store: memoryStore(), now: () => 1000 });
  const instance = await manager.register({ name: "primary", ownerApproved: true });
  const updated = await manager.heartbeat(instance.id);
  assert.equal(updated.status, "healthy");
  assert.equal(updated.lastHeartbeatAt, 1000);
});

test("improvement proposals require tests and owner review", async () => {
  const pipeline = createImprovementPipeline({
    store: memoryStore(),
    runTests: async () => ({ passed: true }),
    now: () => 1234,
  });
  const proposal = await pipeline.propose({ title: "Improve planner", description: "Refine ranking", patch: "diff" });
  await assert.rejects(pipeline.review(proposal.id, { approved: true }), /owner review/);
  const tested = await pipeline.test(proposal.id);
  assert.equal(tested.status, "tested_ready_for_review");
  const approved = await pipeline.review(proposal.id, { approved: true, ownerConfirmed: true });
  assert.equal(approved.status, "approved_for_manual_deployment");
});
