/**
 * Ara's supervised worker loop.
 *
 * Run one active worker against this simple get/set queue store. Multi-worker
 * failover requires a transactional claim/lease store; plain get/set is not
 * sufficient for distributed locking. The host supervises process restarts.
 */
import { randomUUID } from "node:crypto";

const QUEUE_KEY = "ara.runtime.queue.v1";
const DEFAULTS = Object.freeze({
  pollIntervalMs: 5_000,
  maxTasksPerCycle: 10,
  maxAttempts: 3,
  retryDelayMs: 30_000,
  taskLeaseMs: 60_000,
});

export function createAraRuntime({
  store, handlers, heartbeat = async () => {}, now = () => Date.now(),
  sleep = (ms, signal) => new Promise((resolve) => {
    const finish = () => { clearTimeout(timer); signal?.removeEventListener("abort", finish); resolve(); };
    const timer = setTimeout(finish, ms);
    signal?.addEventListener("abort", finish, { once: true });
  }),
  config = {}, logger = { info() {}, error() {} },
} = {}) {
  if (!store || typeof store.get !== "function" || typeof store.set !== "function") {
    throw new TypeError("A persistent store with get/set methods is required");
  }
  if (!handlers || typeof handlers !== "object" || Array.isArray(handlers)) {
    throw new TypeError("An allowlisted task-handler map is required");
  }
  for (const [name, handler] of Object.entries(handlers)) {
    if (!/^[a-z][a-z0-9_.-]{0,63}$/i.test(name) || typeof handler !== "function") {
      throw new TypeError("Task handlers must have valid names and be functions");
    }
  }

  const options = { ...DEFAULTS, ...config };
  const workerId = randomUUID();
  let running = false;
  let controller = null;
  let loopPromise = null;
  let queueLock = Promise.resolve();

  function withQueueLock(operation) {
    const result = queueLock.then(operation, operation);
    queueLock = result.then(() => undefined, () => undefined);
    return result;
  }
  async function readQueue() {
    const value = await store.get(QUEUE_KEY);
    return Array.isArray(value) ? value : [];
  }
  async function enqueue({ type, payload = {}, runAt = now() } = {}) {
    if (!Object.hasOwn(handlers, type)) throw new Error("Task type is not allowlisted");
    if (!Number.isFinite(runAt)) throw new TypeError("runAt must be a timestamp");
    return withQueueLock(async () => {
      const queue = await readQueue();
      const task = {
        id: `task-${randomUUID()}`, type, payload, runAt, attempts: 0,
        status: "queued", createdAt: now(), lastError: null,
      };
      queue.push(task);
      await store.set(QUEUE_KEY, queue);
      return { ...task };
    });
  }

  async function recoverExpiredLeases() {
    return withQueueLock(async () => {
      const queue = await readQueue();
      let changed = false;
      for (const task of queue) {
        if (task.status !== "running" || task.leaseUntil > now()) continue;
        task.lastError = "Worker lease expired; task recovered after interruption";
        task.leaseOwner = null;
        task.leaseUntil = null;
        if (task.attempts >= options.maxAttempts) {
          task.status = "failed";
        } else {
          task.status = "queued";
          task.runAt = now() + options.retryDelayMs;
        }
        changed = true;
      }
      if (changed) await store.set(QUEUE_KEY, queue);
      return queue;
    });
  }

  async function claimDueTasks() {
    return withQueueLock(async () => {
      const queue = await readQueue();
      const due = queue.filter((task) => task.status === "queued" && task.runAt <= now())
        .slice(0, options.maxTasksPerCycle);
      for (const task of due) {
        task.status = "running";
        task.attempts += 1;
        task.leaseOwner = workerId;
        task.leaseUntil = now() + options.taskLeaseMs;
      }
      if (due.length) await store.set(QUEUE_KEY, queue);
      return due.map((task) => ({ ...task }));
    });
  }

  async function finishTask(taskId, update) {
    return withQueueLock(async () => {
      const queue = await readQueue();
      const index = queue.findIndex((task) => task.id === taskId);
      if (index < 0 || queue[index].leaseOwner !== workerId) return false;
      queue[index] = { ...queue[index], ...update, leaseOwner: null, leaseUntil: null };
      await store.set(QUEUE_KEY, queue);
      return true;
    });
  }

  async function processCycle() {
    await heartbeat({ at: now(), status: "running", workerId });
    await recoverExpiredLeases();
    const due = await claimDueTasks();
    let completed = 0;
    for (const task of due) {
      if (controller.signal.aborted) break;
      try {
        const result = await handlers[task.type](task.payload, {
          taskId: task.id, signal: controller.signal,
        });
        const saved = await finishTask(task.id, {
          status: "completed", result: result ?? null, completedAt: now(), lastError: null,
        });
        if (saved) completed += 1;
      } catch (error) {
        const lastError = String(error?.message ?? error).slice(0, 500);
        const terminal = task.attempts >= options.maxAttempts;
        await finishTask(task.id, {
          lastError,
          status: terminal ? "failed" : "queued",
          runAt: terminal ? task.runAt : now() + options.retryDelayMs,
        });
        logger.error("Ara task failed", { taskId: task.id, error: lastError });
      }
    }
    return { checked: due.length, completed };
  }

  async function runLoop() {
    while (!controller.signal.aborted) {
      try { await processCycle(); }
      catch (error) {
        logger.error("Ara runtime cycle failed", { error: String(error?.message ?? error) });
      }
      if (!controller.signal.aborted) await sleep(options.pollIntervalMs, controller.signal);
    }
  }

  return Object.freeze({
    enqueue,
    async start() {
      if (running) return;
      running = true;
      controller = new AbortController();
      loopPromise = runLoop().finally(() => { running = false; });
      logger.info("Ara runtime started", { workerId });
    },
    async stop() {
      if (!running) return;
      controller.abort();
      await loopPromise;
      logger.info("Ara runtime stopped cleanly", { workerId });
    },
    async status() {
      const queue = await readQueue();
      return {
        running,
        queued: queue.filter((task) => task.status === "queued").length,
        runningTasks: queue.filter((task) => task.status === "running").length,
        completed: queue.filter((task) => task.status === "completed").length,
        failed: queue.filter((task) => task.status === "failed").length,
      };
    },
  });
}
