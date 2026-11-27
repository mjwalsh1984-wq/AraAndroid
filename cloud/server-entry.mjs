import { createAraApiServer } from "./ara-api-server.mjs";

const port = Number(process.env.PORT || 10000);
const host = "0.0.0.0";
const server = createAraApiServer();

server.listen(port, host, () => {
  // Do not log secrets or request content.
  console.log(`Ara API listening on ${host}:${port}`);
});

function shutdown(signal) {
  console.log(`Ara API received ${signal}; closing server`);
  server.close((error) => {
    if (error) process.exitCode = 1;
  });
  setTimeout(() => process.exit(1), 10_000).unref();
}

process.on("SIGTERM", () => shutdown("SIGTERM"));
process.on("SIGINT", () => shutdown("SIGINT"));
