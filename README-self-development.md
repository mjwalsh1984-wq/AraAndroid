# Ara self-development loop

Ara's self-development module provides the code-editing orchestration layer.
A host must supply six adapters: source listing and reading, isolated workspace
creation, patch application, an AI-backed patch generator, and a sandboxed test
runner. The module then:

1. Inspects approved source areas.
2. Requests a unified diff for a specific improvement objective.
3. Rejects changes outside inspected, editable files.
4. Applies the diff only to an isolated workspace.
5. Runs tests and records whether the change is ready for review.

Default editable areas are `cloud/`, `app/src/`, and `test/`. The action
gateway, API server, runtime worker, and environment files are protected.
No patch is applied to the live checkout and no deployment is performed.
A passing result is marked `tested_ready_for_review`, not deployed.

This is an orchestration module, not a connected autonomous coding service yet.
The host adapters, model connection, isolated execution environment, and review
workflow must be wired in before Ara can use it.
