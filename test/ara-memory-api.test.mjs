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
const verifyIdentity = async (token) => {
  const identities = { "jwt-owner": "owner-id", "jwt-other": "other-id" };
  if (!identities[token]) throw new Error("invalid JWT");
  return { userId: identities[token] };
};

test("memory API requires a valid Neon Auth JWT", async () => {
  await withServer({ verifyIdentity, store: new MemoryStore() }, async (base) => {
    const response = await fetch(`${base}/v1/memory`, { headers: auth("wrong-token") });
    assert.equal(response.status, 401);
  });
});

test("memory API saves and retrieves notes for the verified account", async () => {
  const store = new MemoryStore();
  await withServer({ verifyIdentity, store, now: () => "2026-11-27T12:00:00.000Z" }, async (base) => {
    const initial = await fetch(`${base}/v1/memory`, { headers: auth("jwt-owner") });
    assert.deepEqual(await initial.json(), { notes: "", updatedAt: null });

    const saved = await fetch(`${base}/v1/memory`, {
      method: "PUT", headers: auth("jwt-owner"), body: JSON.stringify({ notes: "Remember my preferred name is Lola." }),
    });
    assert.deepEqual(await saved.json(), {
      notes: "Remember my preferred name is Lola.",
      updatedAt: "2026-11-27T12:00:00.000Z",
    });

    const loaded = await fetch(`${base}/v1/memory`, { headers: auth("jwt-owner") });
    assert.deepEqual(await loaded.json(), {
      notes: "Remember my preferred name is Lola.",
      updatedAt: "2026-11-27T12:00:00.000Z",
    });
    assert.equal(JSON.stringify([...store.values.entries()]).includes("jwt-owner"), false);
  });
});

test("memory records are isolated by verified user ID and deletion is scoped", async () => {
  const store = new MemoryStore();
  await withServer({ verifyIdentity, store }, async (base) => {
    await fetch(`${base}/v1/memory`, {
      method: "PUT", headers: auth("jwt-owner"), body: JSON.stringify({ notes: "Private to owner A" }),
    });
    const other = await fetch(`${base}/v1/memory`, { headers: auth("jwt-other") });
    assert.deepEqual(await other.json(), { notes: "", updatedAt: null });

    const deleteOther = await fetch(`${base}/v1/memory`, { method: "DELETE", headers: auth("jwt-other") });
    assert.deepEqual(await deleteOther.json(), { deleted: true });
    const ownerAfterDelete = await fetch(`${base}/v1/memory`, { headers: auth("jwt-owner") });
    assert.equal((await ownerAfterDelete.json()).notes, "Private to owner A");

    const deleteOwner = await fetch(`${base}/v1/memory`, { method: "DELETE", headers: auth("jwt-owner") });
    assert.deepEqual(await deleteOwner.json(), { deleted: true });
    const loaded = await fetch(`${base}/v1/memory`, { headers: auth("jwt-owner") });
    assert.deepEqual(await loaded.json(), { notes: "", updatedAt: null });
  });
});

test("memory API rejects oversized or malformed notes", async () => {
  await withServer({ verifyIdentity, store: new MemoryStore() }, async (base) => {
    const tooLong = await fetch(`${base}/v1/memory`, {
      method: "PUT", headers: auth("jwt-owner"), body: JSON.stringify({ notes: "x".repeat(4001) }),
    });
    assert.equal(tooLong.status, 400);
    const invalid = await fetch(`${base}/v1/memory`, {
      method: "PUT", headers: auth("jwt-owner"), body: JSON.stringify({ notes: ["not", "text"] }),
    });
    assert.equal(invalid.status, 400);
  });
});
