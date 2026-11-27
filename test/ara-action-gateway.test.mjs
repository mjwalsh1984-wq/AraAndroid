import test from "node:test";
import assert from "node:assert/strict";
import { createActionGateway } from "../cloud/ara-action-gateway.mjs";

function memoryStore() {
  const data = new Map();
  return { get: async (key) => data.get(key), set: async (key, value) => data.set(key, structuredClone(value)) };
}

test("gateway denies capabilities that are not allowlisted", async () => {
  const gateway = createActionGateway({ store: memoryStore() });
  await assert.rejects(gateway.execute({ capability: "device.read_contacts" }), /not allowlisted/);
  assert.equal((await gateway.auditLog())[0].outcome, "denied");
});

test("gateway blocks financial and shutdown-evasion capabilities", async () => {
  const gateway = createActionGateway({
    store: memoryStore(),
    capabilities: { "financial.transfer": async () => "sent", "process.evade_shutdown": async () => true },
  });
  await assert.rejects(gateway.execute({ capability: "financial.transfer" }), /permanently blocked/);
  await assert.rejects(gateway.execute({ capability: "process.evade_shutdown" }), /permanently blocked/);
});

test("outbound communication requires fresh explicit approval", async () => {
  let sent = 0;
  const gateway = createActionGateway({
    store: memoryStore(), now: () => 1000,
    capabilities: { "communication.send.email": async () => { sent += 1; return "sent"; } },
  });
  await assert.rejects(gateway.execute({ capability: "communication.send.email" }), /fresh, explicit approval/);
  await assert.rejects(gateway.execute({
    capability: "communication.send.email",
    approval: { confirmed: true, approvalId: "old", expiresAt: 999 },
  }), /fresh, explicit approval/);
  assert.equal(sent, 0);
  const result = await gateway.execute({
    capability: "communication.send.email",
    payload: { draftId: "draft-1" },
    approval: { confirmed: true, approvalId: "approval-1", expiresAt: 2000 },
  });
  assert.equal(result, "sent");
  assert.equal(sent, 1);
  assert.equal((await gateway.auditLog()).at(-1).outcome, "completed");
});
