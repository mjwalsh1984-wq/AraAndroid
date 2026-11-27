/**
 * Ara cloud manager foundation.
 * This module provides bounded, owner-controlled instance configuration,
 * health reporting and state snapshots. It does not deploy itself or replicate.
 */
import { randomUUID } from "node:crypto";

const DEFAULT_LIMITS = Object.freeze({
  maxInstances: 2,
  heartbeatTimeoutMs: 90_000,
  snapshotIntervalMs: 300_000,
});

export function createCloudManager({ store, now = () => Date.now(), limits = {} } = {}) {
  if (!store || typeof store.get !== "function" || typeof store.set !== "function") {
    throw new TypeError("A persistent store with get/set methods is required");
  }
  const config = { ...DEFAULT_LIMITS, ...limits };
  const key = "ara.cloud.instances.v1";

  async function read() {
    const value = await store.get(key);
    return Array.isArray(value) ? value : [];
  }

  async function write(instances) {
    await store.set(key, instances);
  }

  return Object.freeze({
    async register({ name, ownerApproved = false } = {}) {
      if (!ownerApproved) throw new Error("Owner approval is required");
      if (typeof name !== "string" || !name.trim()) throw new TypeError("Instance name is required");
      const instances = await read();
      if (instances.length >= config.maxInstances) throw new Error("Configured instance limit reached");
      if (instances.some((item) => item.name === name.trim())) throw new Error("Instance name already exists");
      const instance = {
        id: randomUUID(),
        name: name.trim(),
        status: "registered",
        createdAt: now(),
        lastHeartbeatAt: null,
        lastSnapshotAt: null,
      };
      instances.push(instance);
      await write(instances);
      return { ...instance };
    },

    async heartbeat(id) {
      const instances = await read();
      const index = instances.findIndex((item) => item.id === id);
      if (index < 0) throw new Error("Unknown instance");
      instances[index] = { ...instances[index], status: "healthy", lastHeartbeatAt: now() };
      await write(instances);
      return { ...instances[index] };
    },

    async reportHealth() {
      const instances = await read();
      return instances.map((item) => ({
        ...item,
        status: item.lastHeartbeatAt !== null && now() - item.lastHeartbeatAt <= config.heartbeatTimeoutMs
          ? "healthy"
          : item.status === "registered" ? "registered" : "stale",
      }));
    },

    async recordSnapshot(id, snapshot, { ownerApproved = false } = {}) {
      if (!ownerApproved) throw new Error("Owner approval is required to record a snapshot");
      if (snapshot === undefined) throw new TypeError("Snapshot data is required");
      const instances = await read();
      const index = instances.findIndex((item) => item.id === id);
      if (index < 0) throw new Error("Unknown instance");
      instances[index] = { ...instances[index], lastSnapshotAt: now() };
      await write(instances);
      return { instance: { ...instances[index] }, snapshot };
    },

    async unregister(id, { ownerApproved = false } = {}) {
      if (!ownerApproved) throw new Error("Owner approval is required");
      const instances = await read();
      const next = instances.filter((item) => item.id !== id);
      if (next.length === instances.length) return false;
      await write(next);
      return true;
    },

    limits: Object.freeze({ ...config }),
  });
}
