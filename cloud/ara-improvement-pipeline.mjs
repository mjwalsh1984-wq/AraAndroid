/**
 * Proposal-only improvement pipeline.
 * Ara may draft and test changes in a sandbox, but cannot deploy them.
 */
export function createImprovementPipeline({ store, runTests, now = () => Date.now() } = {}) {
  if (!store || typeof store.get !== "function" || typeof store.set !== "function") {
    throw new TypeError("A persistent store with get/set methods is required");
  }
  if (typeof runTests !== "function") throw new TypeError("runTests callback is required");
  const key = "ara.improvements.proposals.v1";

  async function read() {
    const proposals = await store.get(key);
    return Array.isArray(proposals) ? proposals : [];
  }

  return Object.freeze({
    async propose({ title, description, patch, tests } = {}) {
      if (![title, description, patch].every((v) => typeof v === "string" && v.trim())) {
        throw new TypeError("Title, description and patch are required");
      }
      const proposal = {
        id: `proposal-${now()}-${Math.random().toString(36).slice(2, 8)}`,
        title: title.trim(),
        description: description.trim(),
        patch,
        tests: Array.isArray(tests) ? tests : [],
        status: "pending_review",
        createdAt: now(),
        testResult: null,
        reviewedAt: null,
      };
      const proposals = await read();
      proposals.push(proposal);
      await store.set(key, proposals);
      return { ...proposal };
    },

    async test(id) {
      const proposals = await read();
      const index = proposals.findIndex((p) => p.id === id);
      if (index < 0) throw new Error("Unknown proposal");
      const result = await runTests({ patch: proposals[index].patch, tests: proposals[index].tests });
      proposals[index] = {
        ...proposals[index],
        testResult: result,
        status: result?.passed === true ? "tested_ready_for_review" : "tests_failed",
      };
      await store.set(key, proposals);
      return { ...proposals[index] };
    },

    async review(id, { approved = false, ownerConfirmed = false } = {}) {
      if (!ownerConfirmed) throw new Error("Explicit owner review is required");
      const proposals = await read();
      const index = proposals.findIndex((p) => p.id === id);
      if (index < 0) throw new Error("Unknown proposal");
      if (approved && proposals[index].status !== "tested_ready_for_review") {
        throw new Error("Only proposals with passing tests can be approved");
      }
      proposals[index] = {
        ...proposals[index],
        status: approved ? "approved_for_manual_deployment" : "rejected",
        reviewedAt: now(),
      };
      await store.set(key, proposals);
      return { ...proposals[index] };
    },

    async list() {
      return (await read()).map((proposal) => ({ ...proposal }));
    },
  });
}
