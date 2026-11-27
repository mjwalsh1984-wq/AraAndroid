import test from "node:test";
import assert from "node:assert/strict";
import { createAraApiServer } from "../cloud/ara-api-server.mjs";

class MemoryStore {
  values = new Map();
  async get(key) { return this.values.get(key) ?? null; }
  async set(key, value) {
    if (value === null) this.values.delete(key);
    else this.values.set(key, structuredClone(value));
    return true;
  }
}

async function withServer(options, run) {
  const server = createAraApiServer(options);
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  try { await run(base); }
  finally { await new Promise((resolve) => server.close(resolve)); }
}

const auth = (token) => ({ authorization: `Bearer ${token}`, "content-type": "application/json" });

test("memory API requires the owner bearer token", async () => {
  await withServer({ appToken: "owner-token", store: new MemoryStore() }, async (base) => {
    const response = await fetch(`${base}/v1/memory`, { headers: auth("wrong-token") });
    assert.equal(response.status, 401);
  });
});

test("memory API saves and retrieves owner notes without exposing token material", async () => {
  const store = new MemoryStore();
  await withServer({ appToken: "owner-token", store, now: () => "2026-11-27T12:00:00.000Z" }, async (base) => {
    const initial = await fetch(`${base}/v1/memory`, { headers: auth("owner-token") });
    assert.deepEqual(await initial.json(), { notes: "", updatedAt: null });

    const saved = await fetch(`${base}/v1/memory`, {
      method: "PUT", headers: auth("owner-token"), body: JSON.stringify({ notes: "Remember my preferred name is Lola." }),
    });
    assert.deepEqual(await saved.json(), {
      notes: "Remember my preferred name is Lola.",
      updatedAt: "2026-11-27T12:00:00.000Z",
    });

    const loaded = await fetch(`${base}/v1/memory`, { headers: auth("owner-token") });
    assert.deepEqual(await loaded.json(), {
      notes: "Remember my preferred name is Lola.",
      updatedAt: "2026-11-27T12:00:00.000Z",
    });
    assert.equal(JSON.stringify([...store.values.entries()]).includes("owner-token"), false);
  });
});

test("memory records are isolated by bearer token and can be deleted", async () => {
  const store = new MemoryStore();
  await withServer({ appToken: "owner-a", store }, async (base) => {
    await fetch(`${base}/v1/memory`, {
      method: "PUT", headers: auth("owner-a"), body: JSON.stringify({ notes: "Private to owner A" }),
    });
  });
  await withServer({ appToken: "owner-b", store }, async (base) => {
    const response = await fetch(`${base}/v1/memory`, { headers: auth("owner-b") });
    assert.deepEqual(await response.json(), { notes: "", updatedAt: null });
  });
  await withServer({ appToken: "owner-a", store }, async (base) => {
    const response = await fetch(`${base}/v1/memory`, { method: "DELETE", headers: auth("owner-a") });
    assert.deepEqual(await response.json(), { deleted: true });
    const loaded = await fetch(`${base}/v1/memory`, { headers: auth("owner-a") });
    assert.deepEqual(await loaded.json(), { notes: "", updatedAt: null });
  });
});

test("memory API rejects oversized or malformed notes", async () => {
  await withServer({ appToken: "owner-token", store: new MemoryStore() }, async (base) => {
    const tooLong = await fetch(`${base}/v1/memory`, {
      method: "PUT", headers: auth("owner-token"), body: JSON.stringify({ notes: "x".repeat(4001) }),
    });
    assert.equal(tooLong.status, 400);
    const invalid = await fetch(`${base}/v1/memory`, {
      method: "PUT", headers: auth("owner-token"), body: JSON.stringify({ notes: ["not", "text"] }),
    });
    assert.equal(invalid.status, 400);
  });
});
