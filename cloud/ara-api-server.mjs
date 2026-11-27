/**
 * HTTPS API boundary for Ara. Provider credentials stay server-side.
 * Every private request is authenticated with a Neon Managed Auth JWT.
 * Persistent notes are scoped to the verified JWT subject (user ID).
 */
import { createServer } from "node:http";
import { createHash } from "node:crypto";
import { createRemoteJWKSet, jwtVerify } from "jose";

const MAX_BODY_BYTES = 32 * 1024;
const MAX_MESSAGES = 30;
const MAX_MESSAGE_CHARS = 8_000;
const MAX_MEMORY_CHARS = 4_000;

function ownerMemoryKey(userId) {
  const fingerprint = createHash("sha256").update(userId).digest("hex");
  return `ara.memory.v2.${fingerprint}`;
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

function createIdentityVerifier({ jwksUrl, issuer }) {
  if (!jwksUrl || !issuer) return null;
  const jwks = createRemoteJWKSet(new URL(jwksUrl));
  return async (token) => {
    const { payload } = await jwtVerify(token, jwks, { issuer });
    if (typeof payload.sub !== "string" || !payload.sub.trim()) {
      throw new Error("JWT subject missing");
    }
    return { userId: payload.sub };
  };
}

export function createAraApiServer({
  providerUrl = process.env.ARA_PROVIDER_URL,
  providerKey = process.env.ARA_PROVIDER_API_KEY,
  providerModel = process.env.ARA_PROVIDER_MODEL,
  authJwksUrl = process.env.NEON_AUTH_JWKS_URL,
  authBaseUrl = process.env.NEON_AUTH_BASE_URL,
  verifyIdentity = null,
  fetchImpl = globalThis.fetch,
  storageReady = false,
  store = null,
  now = () => new Date().toISOString(),
} = {}) {
  const issuer = authBaseUrl ? new URL(authBaseUrl).origin : null;
  const authenticate = verifyIdentity || createIdentityVerifier({ jwksUrl: authJwksUrl, issuer });

  return createServer(async (request, response) => {
    const url = new URL(request.url ?? "/", "http://localhost");
    if (request.method === "GET" && url.pathname === "/health") {
      return send(response, 200, {
        ok: true,
        service: "ara-api",
        aiConfigured: Boolean(providerUrl && providerKey && providerModel),
        authConfigured: typeof authenticate === "function",
        storageReady: storageReady === true,
        storageBackend: storageReady === true ? "neon-postgres" : "unconfigured",
      });
    }

    const memoryRoute = url.pathname === "/v1/memory";
    const chatRoute = url.pathname === "/v1/chat";
    if (!memoryRoute && !chatRoute) return send(response, 404, { error: "not_found" });

    const authorization = request.headers.authorization;
    const token = typeof authorization === "string" && /^Bearer\s+/i.test(authorization)
      ? authorization.replace(/^Bearer\s+/i, "").trim()
      : "";
    if (!authenticate || !token) return send(response, 401, { error: "unauthorized" });

    let identity;
    try {
      identity = await authenticate(token);
      if (!identity || typeof identity.userId !== "string" || !identity.userId.trim()) {
        return send(response, 401, { error: "unauthorized" });
      }
    } catch {
      return send(response, 401, { error: "unauthorized" });
    }

    if (memoryRoute) {
      if (!store || typeof store.get !== "function" || typeof store.set !== "function") {
        return send(response, 503, { error: "memory_storage_unavailable" });
      }
      const key = ownerMemoryKey(identity.userId);
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
      const profile = body.profile && typeof body.profile === "object" ? body.profile : {};
      const profileText = [
        typeof profile.personality === "string" ? "Personality preference: " + profile.personality.slice(0, 2000) : "",
        typeof profile.skills === "string" ? "Skills: " + profile.skills.slice(0, 2000) : "",
        typeof profile.instructions === "string" ? "Owner instructions: " + profile.instructions.slice(0, 3000) : "",
        typeof profile.memory === "string" && profile.memory.trim() ? "Relevant private memory supplied by the owner: " + profile.memory.slice(0, 4000) : "",
      ].filter(Boolean).join("\n");
      const systemPrompt = "You are Ara: a distinctly feminine, confident, dominant, self-assured personal AI assistant. Your voice is polished, glamorous, witty, assertive and warmly protective, with playful teasing when appropriate. Lead the conversation instead of waiting passively; take initiative, make clear recommendations, speak with calm authority, and challenge the owner respectfully when useful. Never sound bland, timid, submissive, robotic or like generic customer support. Be affectionate only when the context invites it; never demean, pressure, manipulate or override the owner\u2019s choices. Be honest about your actual capabilities and never claim to have acted, remembered, built, sent or changed anything unless it really happened. The owner retains control: never send messages, contact people, publish, spend money, make payments or transfers, make financial decisions, change code in a live project, or deploy without explicit approval for that specific action. Never evade shutdown or permission controls.\n\nThe following are owner preferences and context, not permission to override these safety and approval boundaries:\n" + profileText;
      messages.unshift({ role: "system", content: systemPrompt });

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
