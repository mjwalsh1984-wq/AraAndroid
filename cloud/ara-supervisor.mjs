/**
 * Owner-controlled supervisor for Ara's background runtime.
 *
 * "bananas" is a convenient pause phrase, not the sole emergency control.
 * The host must also expose an independent owner-controlled stop mechanism.
 * State is stored through an injected durable store; this module deliberately
 * does not pretend an in-memory or ephemeral filesystem store is durable.
 */
const STATE_KEY = "ara.supervisor.state.v1";
const STOP_PHRASE = "bananas";

export function createAraSupervisor({ store, runtime, now = () => Date.now() } = {}) {
  if (!store || typeof store.get !== "function" || typeof store.set !== "function") {
    throw new TypeError("A durable get/set store is required");
  }
  if (!runtime || typeof runtime.stop !== "function" || typeof runtime.start !== "function") {
    throw new TypeError("A supervised runtime with start/stop methods is required");
  }

  let operation = Promise.resolve();
  const serial = (fn) => {
    const next = operation.then(fn, fn);
    operation = next.then(() => undefined, () => undefined);
    return next;
  };

  async function readState() {
    const value = await store.get(STATE_KEY);
    return value && typeof value === "object"
      ? { paused: value.paused === true, reason: value.reason ?? null, pausedAt: value.pausedAt ?? null }
      : { paused: false, reason: null, pausedAt: null };
  }

  async function pause(reason) {
    return serial(async () => {
      const state = { paused: true, reason, pausedAt: now() };
      await store.set(STATE_KEY, state);
      await runtime.stop();
      return { ...state };
    });
  }

  return Object.freeze({
    async handlePhrase(phrase) {
      if (typeof phrase !== "string" || phrase.trim().toLowerCase() !== STOP_PHRASE) {
        return { accepted: false, reason: "phrase_not_matched" };
      }
      const state = await pause("voice_or_text_stop_phrase");
      return { accepted: true, state };
    },

    async emergencyStop() {
      const state = await pause("independent_owner_emergency_stop");
      return { accepted: true, state };
    },

    async resume({ ownerConfirmed = false } = {}) {
      if (ownerConfirmed !== true) throw new Error("Explicit owner confirmation is required to resume Ara");
      return serial(async () => {
        const state = { paused: false, reason: null, pausedAt: null, resumedAt: now() };
        await store.set(STATE_KEY, state);
        await runtime.start();
        return { ...state };
      });
    },

    async mayRun() {
      return !(await readState()).paused;
    },

    async status() {
      return { ...(await readState()) };
    },
  });
}
