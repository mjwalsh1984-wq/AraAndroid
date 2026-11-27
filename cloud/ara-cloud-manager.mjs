/**
 * Ara cloud manager foundation.
 * Snapshots are stored separately from instance metadata. This module does not
 * provision infrastructure or replicate processes.
 */
import { randomUUID } from "node:crypto";

const DEFAULT_LIMITS = Object.freeze({
  maxInstances: 2,
  heartbeatTimeoutMs: 90_000,
  snapshotIntervalMs: 300_000,
  maxSnapshotsPerInstance: 20,
});

export function createCloudManager({ store, now = () => Date.now(), limits = {} } = {}) {
  if (!store || typeof store.get !== "function" || typeof store.set !== "function") {
    throw new TypeError("A persistent store with get/set methods is required");
  }
  const config = { ...DEFAULT_LIMITS, ...limits };
  const instancesKey = "ara.cloud.instances.v1";
  const snapshotsKey = "ara.cloud.snapshots.v1";

  async function readInstances() {
    const value = await store.get(instancesKey);
    return Array.isArray(value) ? value : [];
  }
  async function writeInstances(value) {
    await store.set(instancesKey, value);
  }
  async function readSnapshots() {
    const value = await store.get(snapshotsKey);
    return Array.isArray(value) ? value : [];
  }

  return Object.freeze({
    async register({ name, ownerApproved = false } = {}) {
      if (!ownerApproved) throw new Error("Owner approval is required");
      if (typeof name !== "string" || !name.trim()) throw new TypeError("Instance name is required");
      const instances = await readInstances();
      if (instances.length >= config.maxInstances) throw new Error("Configured instance limit reached");
      if (instances.some((item) => item.name === name.trim())) throw new Error("Instance name already exists");
      const instance = {
        id: randomUUID(), name: name.trim(), status: "registered",
        createdAt: now(), lastHeartbeatAt: null, lastSnapshotAt: null,
      };
      instances.push(instance);
      await writeInstances(instances);
      return { ...instance };
    },

    async heartbeat(id) {
      const instances = await readInstances();
      const index = instances.findIndex((item) => item.id === id);
      if (index < 0) throw new Error("Unknown instance");
      instances[index] = { ...instances[index], status: "healthy", lastHeartbeatAt: now() };
      await writeInstances(instances);
      return { ...instances[index] };
    },

    async reportHealth() {
      const instances = await readInstances();
      return instances.map((item) => ({
        ...item,
        status: item.lastHeartbeatAt !== null && now() - item.lastHeartbeatAt <= config.heartbeatTimeoutMs
          ? "healthy"
          : item.status === "registered" ? "registered" : "stale",
      }));
    },

    async isSnapshotDue(id) {
      const instances = await readInstances();
      const instance = instances.find((item) => item.id === id);
      if (!instance) throw new Error("Unknown instance");
      return instance.lastSnapshotAt === null || now() - instance.lastSnapshotAt >= config.snapshotIntervalMs;
    },

    async recordSnapshot(id, snapshot, { ownerApproved = false } = {}) {
      if (!ownerApproved) throw new Error("Owner approval is required to record a snapshot");
      if (snapshot === undefined) throw new TypeError("Snapshot data is required");
      const instances = await readInstances();
      const index = instances.findIndex((item) => item.id === id);
      if (index < 0) throw new Error("Unknown instance");

      const snapshots = await readSnapshots();
      const record = { id: randomUUID(), instanceId: id, createdAt: now(), data: structuredClone(snapshot) };
      snapshots.push(record);
      const retained = snapshots.filter((item) => item.instanceId === id)
        .slice(-config.maxSnapshotsPerInstance);
      const keepIds = new Set(retained.map((item) => item.id));
      await store.set(snapshotsKey, snapshots.filter((item) => item.instanceId !== id || keepIds.has(item.id)));

      instances[index] = { ...instances[index], lastSnapshotAt: record.createdAt };
      await writeInstances(instances);
      return { instance: { ...instances[index] }, snapshot: structuredClone(record) };
    },

    async listSnapshots(id) {
      return (await readSnapshots())
        .filter((item) => item.instanceId === id)
        .map((item) => structuredClone(item));
    },

    async unregister(id, { ownerApproved = false } = {}) {
      if (!ownerApproved) throw new Error("Owner approval is required");
      const instances = await readInstances();
      const next = instances.filter((item) => item.id !== id);
      if (next.length === instances.length) return false;
      await writeInstances(next);
      return true;
    },

    limits: Object.freeze({ ...config }),
  });
}
