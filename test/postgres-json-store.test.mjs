import test from "node:test";
import assert from "node:assert/strict";
import { createPostgresJsonStore } from "../cloud/postgres-json-store.mjs";

class FakePool {
  static instances = [];
  constructor(options) {
    this.options = options;
    this.rows = new Map();
    this.calls = [];
    FakePool.instances.push(this);
  }
  async query(sql, params = []) {
    this.calls.push({ sql, params });
    if (sql.includes("CREATE TABLE")) return { rows: [] };
    if (sql.startsWith("SELECT")) {
      const value = this.rows.get(params[0]);
      return { rows: value === undefined ? [] : [{ value }] };
    }
    if (sql.startsWith("INSERT")) {
      this.rows.set(params[0], JSON.parse(params[1]));
      return { rows: [] };
    }
    throw new Error("Unexpected SQL");
  }
  async end() {}
}

test("PostgreSQL JSON store creates schema once and persists structured values", async () => {
  FakePool.instances = [];
  const store = createPostgresJsonStore({ connectionString: "postgres://test", PoolClass: FakePool });
  const value = { paused: true, reason: "owner_stop", count: 3 };
  await store.set("ara.supervisor.state.v1", value);
  assert.deepEqual(await store.get("ara.supervisor.state.v1"), value);
  const pool = FakePool.instances[0];
  assert.equal(pool.calls.filter((call) => call.sql.includes("CREATE TABLE")).length, 1);
  assert.equal(pool.options.max, 3);
  await store.close();
});

test("PostgreSQL JSON store rejects missing connection string", () => {
  assert.throws(() => createPostgresJsonStore({ connectionString: "" }), /DATABASE_URL is required/);
});
