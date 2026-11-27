/**
 * Durable JSON key/value store backed by PostgreSQL (Neon free tier supported).
 * The table is namespaced for Ara and values are stored as JSONB.
 */
import pg from "pg";

const { Pool } = pg;

export function createPostgresJsonStore({
  connectionString = process.env.DATABASE_URL,
  PoolClass = Pool,
  max = 3,
} = {}) {
  if (typeof connectionString !== "string" || !connectionString.trim()) {
    throw new TypeError("DATABASE_URL is required for PostgreSQL storage");
  }
  const pool = new PoolClass({
    connectionString,
    max,
    connectionTimeoutMillis: 10_000,
    idleTimeoutMillis: 30_000,
    application_name: "ara-api",
  });

  let readyPromise;
  async function ready() {
    if (!readyPromise) {
      readyPromise = pool.query(`
        CREATE TABLE IF NOT EXISTS ara_kv (
          key TEXT PRIMARY KEY,
          value JSONB NOT NULL,
          updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
        )
      `).catch((error) => {
        readyPromise = null;
        throw error;
      });
    }
    await readyPromise;
  }

  return Object.freeze({
    async get(key) {
      if (typeof key !== "string" || !key) throw new TypeError("A non-empty key is required");
      await ready();
      const result = await pool.query("SELECT value FROM ara_kv WHERE key = $1", [key]);
      return result.rows[0]?.value ?? null;
    },
    async set(key, value) {
      if (typeof key !== "string" || !key) throw new TypeError("A non-empty key is required");
      await ready();
      await pool.query(
        "INSERT INTO ara_kv (key, value, updated_at) VALUES ($1, $2::jsonb, NOW()) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = NOW()",
        [key, JSON.stringify(value)],
      );
      return true;
    },
    async close() {
      await pool.end();
    },
  });
}
