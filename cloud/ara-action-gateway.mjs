/**
 * Ara action gateway.
 *
 * All external effects must pass through this gateway. It defaults to deny,
 * requires a narrow registered capability, and requires a fresh explicit
 * approval for outbound communication. It never grants financial authority.
 */
const AUDIT_KEY = "ara.action.audit.v1";
const BLOCKED_CAPABILITIES = new Set([
  "financial.payment", "financial.transfer", "financial.decision",
  "security.disable_owner_controls", "security.disable_emergency_stop",
  "data.exfiltrate_secrets", "data.delete_important",
  "replication.uncontrolled", "process.evade_shutdown",
]);

export function createActionGateway({
  store,
  capabilities = {},
  now = () => Date.now(),
  maxAuditEntries = 500,
} = {}) {
  if (!store || typeof store.get !== "function" || typeof store.set !== "function") {
    throw new TypeError("A persistent store with get/set methods is required");
  }
  for (const [name, handler] of Object.entries(capabilities)) {
    if (typeof handler !== "function") throw new TypeError(`Capability ${name} must be a function`);
  }

  async function audit(entry) {
    const existing = await store.get(AUDIT_KEY);
    const entries = Array.isArray(existing) ? existing : [];
    entries.push({ at: now(), ...entry });
    await store.set(AUDIT_KEY, entries.slice(-maxAuditEntries));
  }

  return Object.freeze({
    async execute({ capability, payload = {}, approval } = {}) {
      if (typeof capability !== "string" || !capability.trim()) {
        throw new TypeError("A capability name is required");
      }
      if (BLOCKED_CAPABILITIES.has(capability)) {
        await audit({ capability, outcome: "denied", reason: "blocked_capability" });
        throw new Error("This capability is permanently blocked by the action gateway");
      }
      const handler = capabilities[capability];
      if (!handler) {
        await audit({ capability, outcome: "denied", reason: "not_allowlisted" });
        throw new Error("Capability is not allowlisted");
      }

      const outbound = capability.startsWith("communication.send");
      if (outbound && (!approval || approval.confirmed !== true ||
          typeof approval.approvalId !== "string" || !approval.approvalId.trim() ||
          !Number.isFinite(approval.expiresAt) || approval.expiresAt <= now())) {
        await audit({ capability, outcome: "denied", reason: "fresh_approval_required" });
        throw new Error("A fresh, explicit approval is required to send communications");
      }

      await audit({
        capability,
        outcome: "started",
        approvalId: outbound ? approval.approvalId : null,
      });
      try {
        const result = await handler(payload);
        await audit({ capability, outcome: "completed" });
        return result;
      } catch (error) {
        await audit({
          capability,
          outcome: "failed",
          error: String(error?.message ?? error).slice(0, 300),
        });
        throw error;
      }
    },

    async auditLog() {
      const entries = await store.get(AUDIT_KEY);
      return Array.isArray(entries) ? structuredClone(entries) : [];
    },
  });
}
