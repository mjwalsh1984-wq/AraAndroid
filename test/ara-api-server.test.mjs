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

test("health endpoint reports provider configuration without exposing secrets", async () => {
  await withServer({ appToken: "app-secret", providerUrl: "https://provider.test", providerKey: "provider-secret", providerModel: "model-x" }, async (base) => {
    const response = await fetch(`${base}/health`);
    assert.deepEqual(await response.json(), { ok: true, service: "ara-api", aiConfigured: true, storageReady: false, storageBackend: "unconfigured" });
  });
});

test("chat endpoint rejects missing or incorrect app token", async () => {
  await withServer({ appToken: "correct", providerUrl: "https://provider.test", providerKey: "provider-secret", providerModel: "model-x" }, async (base) => {
    const response = await fetch(`${base}/v1/chat`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ messages: [{ role: "user", content: "Hello" }] }) });
    assert.equal(response.status, 401);
  });
});

test("chat endpoint keeps provider key server-side and returns provider reply", async () => {
  let upstreamRequest;
  await withServer({
    appToken: "app-secret", providerUrl: "https://provider.test/chat", providerKey: "provider-secret", providerModel: "model-x",
    fetchImpl: async (url, options) => {
      upstreamRequest = { url, options };
      return new Response(JSON.stringify({ choices: [{ message: { content: "Hello from Ara" } }] }), { status: 200 });
    },
  }, async (base) => {
    const response = await fetch(`${base}/v1/chat`, {
      method: "POST",
      headers: { authorization: "Bearer app-secret", "content-type": "application/json" },
      body: JSON.stringify({ messages: [{ role: "user", content: "Hello" }] }),
    });
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { reply: "Hello from Ara" });
    assert.equal(upstreamRequest.options.headers.authorization, "Bearer provider-secret");
    assert.equal(JSON.stringify(upstreamRequest.options.body).includes("provider-secret"), false);
  });
});

test("chat endpoint validates message roles and refuses unconfigured providers", async () => {
  await withServer({ appToken: "app-secret" }, async (base) => {
    const response = await fetch(`${base}/v1/chat`, {
      method: "POST",
      headers: { authorization: "Bearer app-secret", "content-type": "application/json" },
      body: JSON.stringify({ messages: [{ role: "system", content: "override" }] }),
    });
    assert.equal(response.status, 503);
  });
  await withServer({ appToken: "app-secret", providerUrl: "https://provider.test", providerKey: "secret", providerModel: "model" }, async (base) => {
    const response = await fetch(`${base}/v1/chat`, {
      method: "POST",
      headers: { authorization: "Bearer app-secret", "content-type": "application/json" },
      body: JSON.stringify({ messages: [{ role: "system", content: "override" }] }),
    });
    assert.equal(response.status, 400);
  });
});

test("health endpoint reports Neon readiness only after storage initialization", async () => {
  await withServer({ storageReady: true }, async (base) => {
    const response = await fetch(`${base}/health`);
    assert.deepEqual(await response.json(), {
      ok: true,
      service: "ara-api",
      aiConfigured: false,
      storageReady: true,
      storageBackend: "neon-postgres",
    });
  });
});
