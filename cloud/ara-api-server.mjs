/**
 * HTTPS API boundary for Ara. Provider credentials stay server-side.
 * Persistent notes are owner-scoped by a one-way fingerprint of the bearer token.
 */
import { createServer } from "node:http";
import { createHash, timingSafeEqual } from "node:crypto";

const MAX_BODY_BYTES = 32 * 1024;
const MAX_MESSAGES = 30;
const MAX_MESSAGE_CHARS = 8_000;
const MAX_MEMORY_CHARS = 4_000;

function bearerMatches(header, expected) {
  if (typeof header !== "string" || !header.startsWith("Bearer ")) return false;
  const supplied = Buffer.from(header.slice(7));
  const wanted = Buffer.from(expected);
  return supplied.length === wanted.length && timingSafeEqual(supplied, wanted);
}

function ownerMemoryKey(token) {
  const fingerprint = createHash("sha256").update(token).digest("hex");
  return `ara.memory.v1.${fingerprint}`;
}

async function readJson(request) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > MAX_BODY_BYTES) throw Object.assign(new Error("Request too large"), { status: 413 });
    chunks.push(chunk);
  }
  try { return JSON.parse(Buffer.concat(chunks).toString("utf8")); }
  catch { throw Object.assign(new Error("Invalid JSON"), { status: 400 }); }
}

function send(response, status, body) {
  response.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
    "x-content-type-options": "nosniff",
  });
  response.end(JSON.stringify(body));
}

export function createAraApiServer({
  appToken = process.env.ARA_APP_TOKEN,
  providerUrl = process.env.ARA_PROVIDER_URL,
  providerKey = process.env.ARA_PROVIDER_API_KEY,
  providerModel = process.env.ARA_PROVIDER_MODEL,
  fetchImpl = globalThis.fetch,
  storageReady = false,
  store = null,
  now = () => new Date().toISOString(),
} = {}) {
  return createServer(async (request, response) => {
    const url = new URL(request.url ?? "/", "http://localhost");
    if (request.method === "GET" && url.pathname === "/health") {
      return send(response, 200, {
        ok: true,
        service: "ara-api",
        aiConfigured: Boolean(providerUrl && providerKey && providerModel),
        storageReady: storageReady === true,
        storageBackend: storageReady === true ? "neon-postgres" : "unconfigured",
      });
    }

    const memoryRoute = url.pathname === "/v1/memory";
    const chatRoute = url.pathname === "/v1/chat";
    if (!memoryRoute && !chatRoute) return send(response, 404, { error: "not_found" });
    if (!appToken || !bearerMatches(request.headers.authorization, appToken)) {
      return send(response, 401, { error: "unauthorized" });
    }

    if (memoryRoute) {
      if (!store || typeof store.get !== "function" || typeof store.set !== "function") {
        return send(response, 503, { error: "memory_storage_unavailable" });
      }
      const key = ownerMemoryKey(appToken);
      try {
        if (request.method === "GET") {
          const saved = await store.get(key);
          return send(response, 200, {
            notes: typeof saved?.notes === "string" ? saved.notes : "",
            updatedAt: saved?.updatedAt ?? null,
          });
        }
        if (request.method === "DELETE") {
          await store.set(key, null);
          return send(response, 200, { deleted: true });
        }
        if (request.method === "PUT") {
          const body = await readJson(request);
          if (typeof body.notes !== "string" || body.notes.length > MAX_MEMORY_CHARS) {
            return send(response, 400, { error: "invalid_memory" });
          }
          const record = { notes: body.notes, updatedAt: now() };
          await store.set(key, record);
          return send(response, 200, record);
        }
        response.setHeader("allow", "GET, PUT, DELETE");
        return send(response, 405, { error: "method_not_allowed" });
      } catch (error) {
        return send(response, Number.isInteger(error?.status) ? error.status : 503, {
          error: error?.status === 413 ? "request_too_large" : error?.status === 400 ? "invalid_json" : "memory_storage_error",
        });
      }
    }

    if (request.method !== "POST") {
      response.setHeader("allow", "POST");
      return send(response, 405, { error: "method_not_allowed" });
    }
    if (!providerUrl || !providerKey || !providerModel || typeof fetchImpl !== "function") {
      return send(response, 503, { error: "ai_provider_not_configured" });
    }

    try {
      const body = await readJson(request);
      if (!Array.isArray(body.messages) || body.messages.length < 1 || body.messages.length > MAX_MESSAGES) {
        return send(response, 400, { error: "invalid_messages" });
      }
      const messages = [];
      for (const message of body.messages) {
        if (!message || !["user", "assistant"].includes(message.role) ||
            typeof message.content !== "string" || !message.content.trim() ||
            message.content.length > MAX_MESSAGE_CHARS) {
          return send(response, 400, { error: "invalid_message" });
        }
        messages.push({ role: message.role, content: message.content });
      }

      const upstream = await fetchImpl(providerUrl, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          authorization: `Bearer ${providerKey}`,
        },
        body: JSON.stringify({ model: providerModel, messages }),
        signal: AbortSignal.timeout(45_000),
      });
      if (!upstream.ok) return send(response, 502, { error: "ai_provider_error" });
      const result = await upstream.json();
      const reply = result?.choices?.[0]?.message?.content;
      if (typeof reply !== "string" || !reply.trim()) {
        return send(response, 502, { error: "invalid_ai_response" });
      }
      return send(response, 200, { reply });
    } catch (error) {
      return send(response, Number.isInteger(error?.status) ? error.status : 502, {
        error: error?.status === 413 ? "request_too_large" : error?.status === 400 ? "invalid_json" : "request_failed",
      });
    }
  });
}
