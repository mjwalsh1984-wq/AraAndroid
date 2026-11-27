/**
 * Ara's bounded self-development loop.
 *
 * Reads approved source areas, asks a configured model to draft a unified diff,
 * applies it only to an isolated workspace, and runs the supplied test command.
 * It never writes to the live checkout or deploys changes.
 */
const DEFAULT_EDITABLE_PREFIXES = ["cloud/", "app/src/", "test/"];
const PROTECTED_PATHS = new Set([
  "cloud/ara-action-gateway.mjs", "cloud/ara-api-server.mjs",
  "cloud/ara-runtime.mjs", ".env", ".env.example",
]);
const MAX_PATCH_CHARS = 64_000;
const MAX_OBJECTIVE_CHARS = 2_000;

function assertAdapter(fn, name) {
  if (typeof fn !== "function") throw new TypeError(`${name} adapter is required`);
}
function normalisePath(path) {
  if (typeof path !== "string" || !path || path.includes("\\") ||
      path.startsWith("/") || path.split("/").some((part) => !part || part === "." || part === "..")) {
    throw new Error("Unsafe source path");
  }
  return path;
}
function changedPathsFromDiff(patch) {
  const paths = [];
  for (const line of patch.split("\n")) {
    if (line.startsWith("+++ b/")) paths.push(normalisePath(line.slice(6).trim()));
  }
  if (!paths.length) throw new Error("Patch contains no file changes");
  return [...new Set(paths)];
}

export function createSelfDevelopmentAgent({
  workspace, generatePatch, runTests,
  editablePrefixes = DEFAULT_EDITABLE_PREFIXES,
  maxPatchChars = MAX_PATCH_CHARS,
  now = () => Date.now(),
} = {}) {
  assertAdapter(workspace?.listFiles, "workspace.listFiles");
  assertAdapter(workspace?.readFiles, "workspace.readFiles");
  assertAdapter(workspace?.createIsolated, "workspace.createIsolated");
  assertAdapter(workspace?.applyPatch, "workspace.applyPatch");
  assertAdapter(generatePatch, "generatePatch");
  assertAdapter(runTests, "runTests");
  const prefixAllowed = (path) => editablePrefixes.some((prefix) => path.startsWith(prefix));

  return Object.freeze({
    async inspect() {
      const files = await workspace.listFiles();
      if (!Array.isArray(files)) throw new Error("Workspace file listing must be an array");
      return files.map(normalisePath)
        .filter((path) => prefixAllowed(path) && !PROTECTED_PATHS.has(path))
        .sort();
    },
    async improve({ objective } = {}) {
      if (typeof objective !== "string" || !objective.trim() || objective.length > MAX_OBJECTIVE_CHARS) {
        throw new TypeError("A concise improvement objective is required");
      }
      const allowedFiles = await this.inspect();
      if (!allowedFiles.length) throw new Error("No editable source files were found");
      const source = await workspace.readFiles(allowedFiles);
      const patch = await generatePatch({ objective: objective.trim(), files: source });
      if (typeof patch !== "string" || !patch.trim() || patch.length > maxPatchChars) {
        throw new Error("Generated patch is empty or exceeds the patch size limit");
      }
      const changedPaths = changedPathsFromDiff(patch);
      for (const path of changedPaths) {
        if (!prefixAllowed(path) || PROTECTED_PATHS.has(path) || !allowedFiles.includes(path)) {
          throw new Error(`Patch targets a protected or uninspected path: ${path}`);
        }
      }
      const sandbox = await workspace.createIsolated({ label: `ara-improvement-${now()}` });
      await workspace.applyPatch({ sandbox, patch, allowedPaths: changedPaths });
      const testResult = await runTests({ sandbox, changedPaths });
      return Object.freeze({
        id: `ara-change-${now()}`, objective: objective.trim(), changedPaths,
        patch, testResult,
        status: testResult?.passed === true ? "tested_ready_for_review" : "tests_failed",
        createdAt: now(), deployment: "not_deployed",
      });
    },
  });
}
