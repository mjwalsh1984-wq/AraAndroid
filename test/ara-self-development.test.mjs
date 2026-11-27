import test from "node:test";
import assert from "node:assert/strict";
import { createSelfDevelopmentAgent } from "../cloud/ara-self-development.mjs";

function fixture({ patch = "diff --git a/cloud/ara-planner.mjs b/cloud/ara-planner.mjs\n--- a/cloud/ara-planner.mjs\n+++ b/cloud/ara-planner.mjs\n@@ -1 +1 @@\n-old\n+new", passed = true } = {}) {
  const calls = [];
  const workspace = {
    listFiles: async () => ["cloud/ara-planner.mjs", "cloud/ara-action-gateway.mjs", ".env", "README.md", "test/planner.test.mjs"],
    readFiles: async (paths) => ({ paths }),
    createIsolated: async (args) => { calls.push(["sandbox", args]); return { id: "isolated-1" }; },
    applyPatch: async (args) => calls.push(["patch", args]),
  };
  const agent = createSelfDevelopmentAgent({
    workspace, generatePatch: async () => patch,
    runTests: async (args) => { calls.push(["tests", args]); return { passed }; },
    now: () => 123,
  });
  return { agent, calls };
}
test("inspection exposes source areas but excludes protected files", async () => {
  const { agent } = fixture();
  assert.deepEqual(await agent.inspect(), ["cloud/ara-planner.mjs", "test/planner.test.mjs"]);
});
test("improvement drafts and tests a patch only in an isolated workspace", async () => {
  const { agent, calls } = fixture();
  const result = await agent.improve({ objective: "Improve planner ranking" });
  assert.equal(result.status, "tested_ready_for_review");
  assert.equal(result.deployment, "not_deployed");
  assert.deepEqual(result.changedPaths, ["cloud/ara-planner.mjs"]);
  assert.deepEqual(calls.map((call) => call[0]), ["sandbox", "patch", "tests"]);
});
test("rejects patches that target protected or uninspected files", async () => {
  const patch = "diff --git a/cloud/ara-action-gateway.mjs b/cloud/ara-action-gateway.mjs\n--- a/cloud/ara-action-gateway.mjs\n+++ b/cloud/ara-action-gateway.mjs\n@@ -1 +1 @@\n-a\n+b";
  const { agent, calls } = fixture({ patch });
  await assert.rejects(agent.improve({ objective: "Remove restrictions" }), /protected or uninspected/);
  assert.equal(calls.length, 0);
});
test("reports failed tests without deploying", async () => {
  const { agent } = fixture({ passed: false });
  const result = await agent.improve({ objective: "Improve planner" });
  assert.equal(result.status, "tests_failed");
  assert.equal(result.deployment, "not_deployed");
});
test("rejects invalid objectives", async () => {
  const { agent } = fixture();
  await assert.rejects(agent.improve({ objective: "" }), /objective/);
});
