import { createAraApiServer } from "./ara-api-server.mjs";
import { createPostgresJsonStore } from "./postgres-json-store.mjs";

const port = Number(process.env.PORT || 10000);
const host = "0.0.0.0";
const store = createPostgresJsonStore();

// Fail startup if Neon is unavailable: the service must not report healthy
// while its configured durable store cannot be reached.
await store.initialize();
const server = createAraApiServer({ storageReady: true });

server.listen(port, host, () => {
  // Do not log secrets or request content.
  console.log(`Ara API listening on ${host}:${port}; Neon storage ready`);
});

function shutdown(signal) {
  console.log(`Ara API received ${signal}; closing server`);
  server.close(async (error) => {
    if (error) process.exitCode = 1;
    try {
      await store.close();
    } catch {
      process.exitCode = 1;
    }
  });
  setTimeout(() => process.exit(1), 10_000).unref();
}

process.on("SIGTERM", () => shutdown("SIGTERM"));
process.on("SIGINT", () => shutdown("SIGINT"));
