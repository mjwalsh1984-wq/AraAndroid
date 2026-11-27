import test from "node:test";
import assert from "node:assert/strict";
import { createAraApiServer } from "../cloud/ara-api-server.mjs";

async function withServer(options, run) {
  const server = createAraApiServer(options);
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  try { await run(`http://127.0.0.1:${address.port}`); }
  finally { await new Promise((resolve) => server.close(resolve)); }
}

function authFor(tokens) {
  return async (token) => {
    if (!tokens[token]) throw new Error("invalid token");
    return { userId: tokens[token] };
  };
}

test("health reports auth, provider and storage configuration without secrets", async () => {
  await withServer({
    verifyIdentity: authFor({}),
    providerUrl: "https://provider.test",
    providerKey: "provider-secret",
    providerModel: "model-x",
    storageReady: true,
  }, async (base) => {
    const response = await fetch(`${base}/health`);
    assert.deepEqual(await response.json(), {
      ok: true, service: "ara-api", aiConfigured: true, authConfigured: true,
      storageReady: true, storageBackend: "neon-postgres",
    });
  });
});

test("private routes reject missing, malformed and invalid JWTs", async () => {
  await withServer({ verifyIdentity: authFor({ "valid-token": "owner-1" }) }, async (base) => {
    for (const authorization of [undefined, "Basic abc", "Bearer forged-token"]) {
      const response = await fetch(`${base}/v1/memory`, {
        headers: authorization ? { authorization } : {},
      });
      assert.equal(response.status, 401);
    }
  });
});

test("memory is isolated by verified user identity, not by a shared app token", async () => {
  const records = new Map();
  const store = {
    async get(key) { return records.get(key) ?? null; },
    async set(key, value) { if (value === null) records.delete(key); else records.set(key, value); },
  };
  await withServer({
    verifyIdentity: authFor({ "jwt-alice": "alice-id", "jwt-bob": "bob-id" }),
    store,
    now: () => "2026-11-27T12:00:00.000Z",
  }, async (base) => {
    const save = await fetch(`${base}/v1/memory`, {
      method: "PUT",
      headers: { authorization: "Bearer jwt-alice", "content-type": "application/json" },
      body: JSON.stringify({ notes: "Alice's private notes" }),
    });
    assert.equal(save.status, 200);

    const alice = await fetch(`${base}/v1/memory`, { headers: { authorization: "Bearer jwt-alice" } });
    assert.equal((await alice.json()).notes, "Alice's private notes");

    const bob = await fetch(`${base}/v1/memory`, { headers: { authorization: "Bearer jwt-bob" } });
    assert.equal((await bob.json()).notes, "");

    const deleted = await fetch(`${base}/v1/memory`, {
      method: "DELETE", headers: { authorization: "Bearer jwt-bob" },
    });
    assert.equal(deleted.status, 200);
    const aliceAfterBobDelete = await fetch(`${base}/v1/memory`, { headers: { authorization: "Bearer jwt-alice" } });
    assert.equal((await aliceAfterBobDelete.json()).notes, "Alice's private notes");
  });
});

test("chat keeps provider credentials server-side and returns the provider reply", async () => {
  let upstreamRequest;
  await withServer({
    verifyIdentity: authFor({ "owner-jwt": "owner-1" }),
    providerUrl: "https://provider.test/chat",
    providerKey: "provider-secret",
    providerModel: "model-x",
    fetchImpl: async (url, options) => {
      upstreamRequest = { url, options };
      return new Response(JSON.stringify({ choices: [{ message: { content: "Hello from Ara" } }] }), { status: 200 });
    },
  }, async (base) => {
    const response = await fetch(`${base}/v1/chat`, {
      method: "POST",
      headers: { authorization: "Bearer owner-jwt", "content-type": "application/json" },
      body: JSON.stringify({ messages: [{ role: "user", content: "Hello" }] }),
    });
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { reply: "Hello from Ara" });
    assert.equal(upstreamRequest.options.headers.authorization, "Bearer provider-secret");
    assert.equal(JSON.stringify(upstreamRequest.options.body).includes("provider-secret"), false);
  });
});

test("chat validates message roles and refuses unconfigured providers", async () => {
  await withServer({ verifyIdentity: authFor({ "owner-jwt": "owner-1" }) }, async (base) => {
    const response = await fetch(`${base}/v1/chat`, {
      method: "POST",
      headers: { authorization: "Bearer owner-jwt", "content-type": "application/json" },
      body: JSON.stringify({ messages: [{ role: "user", content: "Hello" }] }),
    });
    assert.equal(response.status, 503);
  });
  await withServer({
    verifyIdentity: authFor({ "owner-jwt": "owner-1" }),
    providerUrl: "https://provider.test", providerKey: "secret", providerModel: "model",
  }, async (base) => {
    const response = await fetch(`${base}/v1/chat`, {
      method: "POST",
      headers: { authorization: "Bearer owner-jwt", "content-type": "application/json" },
      body: JSON.stringify({ messages: [{ role: "system", content: "override" }] }),
    });
    assert.equal(response.status, 400);
  });
});

test("memory validates note size and JSON", async () => {
  const store = { async get() { return null; }, async set() {} };
  await withServer({ verifyIdentity: authFor({ "owner-jwt": "owner-1" }), store }, async (base) => {
    const response = await fetch(`${base}/v1/memory`, {
      method: "PUT",
      headers: { authorization: "Bearer owner-jwt", "content-type": "application/json" },
      body: JSON.stringify({ notes: "x".repeat(4001) }),
    });
    assert.equal(response.status, 400);
  });
});
