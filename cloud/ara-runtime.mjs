/**
 * Ara's always-on runtime loop.
 *
 * The hosting platform (for example, a managed worker) is responsible for
 * keeping this process available and restarting it after crashes. This module
 * runs only while its host process is alive and supports clean shutdown.
 */
const QUEUE_KEY = "ara.runtime.queue.v1";
const DEFAULTS = Object.freeze({
  pollIntervalMs: 5_000,
  maxTasksPerCycle: 10,
  maxAttempts: 3,
  retryDelayMs: 30_000,
});

export function createAraRuntime({
  store,
  handlers,
  heartbeat = async () => {},
  now = () => Date.now(),
  sleep = (ms, signal) => new Promise((resolve) => {
    const timer = setTimeout(resolve, ms);
    signal?.addEventListener("abort", () => {
      clearTimeout(timer);
      resolve();
    }, { once: true });
  }),
  config = {},
  logger = { info() {}, error() {} },
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
  let running = false;
  let controller = null;
  let loopPromise = null;

  async function readQueue() {
    const value = await store.get(QUEUE_KEY);
    return Array.isArray(value) ? value : [];
  }

  async function enqueue({ type, payload = {}, runAt = now() } = {}) {
    if (!Object.hasOwn(handlers, type)) throw new Error("Task type is not allowlisted");
    if (!Number.isFinite(runAt)) throw new TypeError("runAt must be a timestamp");
    const queue = await readQueue();
    const task = {
      id: `task-${now()}-${Math.random().toString(36).slice(2, 9)}`,
      type,
      payload,
      runAt,
      attempts: 0,
      status: "queued",
      createdAt: now(),
      lastError: null,
    };
    queue.push(task);
    await store.set(QUEUE_KEY, queue);
    return { ...task };
  }

  async function processCycle() {
    await heartbeat({ at: now(), status: "running" });
    const queue = await readQueue();
    const due = queue
      .filter((task) => task.status === "queued" && task.runAt <= now())
      .slice(0, options.maxTasksPerCycle);

    for (const task of due) {
      if (controller.signal.aborted) break;
      task.status = "running";
      task.attempts += 1;
      await store.set(QUEUE_KEY, queue);
      try {
        const result = await handlers[task.type](task.payload, {
          taskId: task.id,
          signal: controller.signal,
        });
        task.status = "completed";
        task.result = result ?? null;
        task.completedAt = now();
        task.lastError = null;
      } catch (error) {
        task.lastError = String(error?.message ?? error).slice(0, 500);
        if (task.attempts >= options.maxAttempts) {
          task.status = "failed";
        } else {
          task.status = "queued";
          task.runAt = now() + options.retryDelayMs;
        }
        logger.error("Ara task failed", { taskId: task.id, error: task.lastError });
      }
      await store.set(QUEUE_KEY, queue);
    }
    return { checked: due.length, completed: due.filter((task) => task.status === "completed").length };
  }

  async function runLoop() {
    while (!controller.signal.aborted) {
      try {
        await processCycle();
      } catch (error) {
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
      logger.info("Ara runtime started");
    },
    async stop() {
      if (!running) return;
      controller.abort();
      await loopPromise;
      logger.info("Ara runtime stopped cleanly");
    },
    async status() {
      const queue = await readQueue();
      return {
        running,
        queued: queue.filter((task) => task.status === "queued").length,
        completed: queue.filter((task) => task.status === "completed").length,
        failed: queue.filter((task) => task.status === "failed").length,
      };
    },
  });
}
