# Managed JSON acceptance for Goals

Open a conversation with an existing Goal, click the Goals button in its header, and expand Managed JSON acceptance. Ordinary workspace members can manage requirements for their own conversations without access to the administrator plan board. Administrators can also use the Goal panel on that board. The conversation owner or an administrator explicitly saves up to eight requirements, each mapping an artifact slot to 1–16 top-level fields. Fields must exist and be non-null; false, zero and empty strings are allowed. This is a presence check, not a quality judgment. Opt-in is durable: requirements can be revised using their current revision, but required mode cannot be disabled. Stale revisions produce a conflict.

User configuration, independent managed versions, binding checks and the shared completion gate are connected. Every current requirement needs a matching valid binding before a selected goal can complete under its existing completion rules. Automatic evaluation, explicit completeGoal and retries share that gate. Unselected goals retain existing behavior.

The conversation Goals panel includes paused and terminal goals, loading 20 at a time with an option to load older records. Paused goals still allow requirement edits, publication and checks; terminal goals only expose existing requirements and content. Closing the panel, switching conversations or leaving the page clears its contents, and a failed refresh clears the old list. Reading history does not resume execution.

## Managed version API

Prefix: `/api/v1/goals/{goalId}/json-acceptance`. An enabled account with conversation-owner or administrator permission is required. Preserve IDs, revisions and generations as strings in clients.

- `GET /`: read required mode and requirements.
- `PUT /requirements/{criterionKey}`: send `expectedRevision`, `artifactSlot` and `requiredFields`; use revision `0` for a new requirement.
- `GET /artifacts`: list required slots and current versions; an empty slot has generation `0`.
- `POST /artifacts/{slot}`: send `expectedGeneration` and `jsonContent` (a string containing the original JSON body) to append a version and atomically advance the slot.
- `GET /artifacts/versions/{artifactId}`: read metadata and exact content of a version belonging to this goal, including historical versions. Read access does not imply current acceptance eligibility.

Publication requires an active or paused goal and a slot referenced by a current requirement. Content must be a strict JSON object: duplicate keys, trailing documents, nesting beyond 32 levels and UTF-8 content over 1 MiB are rejected. Each goal can retain at most 32 versions; the limit rejects new publication instead of overwriting history. Each version expires after 24 hours. Republishing identical bytes still creates a new version. Reload after a generation conflict rather than automatically overwriting another publication.

Managed bodies live independently in the database. Ordinary workspace files, cache paths and hashes in text are not substitutes. No publication API edits historical bodies; bodies and pointers commit together. SHA-256 identifies content and supports integrity checks; it does not isolate an attacker with database credentials or host privileges. The database and service host are trusted foundations of this limited protocol. The JSON service contract has been exercised on H2, MySQL 8.0.46 and PostgreSQL 16.14. The MySQL run isolated an existing V192 migration failure using a test-only migration copy; PostgreSQL used the original Kingbase migration tree while skipping an unrelated bundled-skill import failure. These are protocol tests, not confirmation that an unmodified full installation succeeds. The proprietary Kingbase engine has not been tested.

## Agent publication

`getManagedGoalJsonSlots` returns current user requirements, slots and generations. `publishManagedGoalJson` accepts `artifactSlot`, a string `expectedGeneration` and `jsonContent`. Tools cannot configure requirements or supply goal IDs, accounts or owner fences. Interactive sessions require the authenticated account's internal ID. Scheduled persistent-goal execution must match the current continuation, attempt, owner token and live leases. Both paths recheck the conversation, workspace, agent and enabled account. A conversation that has been archived or assigned to another agent no longer authorizes its old runtime to read managed state, publish, check or complete; authorized users can still read the stored evidence. The default delegation deny list includes both tools; the service still independently validates identity.

Publication and scheduler settlement serialize through the goal lock, rejecting late writes by former owners. Ending a lease does not mutate previously published versions. Anonymous sessions and cron runs without a bound goal attempt are outside this publication protocol. Missing identity is rejected instead of trusting a display username.


## Binding checks

After publication, call `POST /checks/{criterionKey}` with `expectedRequirementRevision`, `artifactId` and `expectedGeneration`, or use the agent tool `checkManagedGoalJson` with the same fields. Tool revisions and generations are strings. The server checks the specified current slot version using its own fields recipe; it never accepts a caller-provided PASS. `acceptanceEligible=true` applies to that requirement at the time of checking, not to whole-goal completion.

`GET /checks` reads each requirement's current eligibility. Requirement edits, goal-definition edits, a new slot version, expiry or failed body integrity checks invalidate previous bindings. Recheck the current inputs. Binding and goal-version updates share a transaction; rollback cannot leave a passing credential. Historical diagnostic APIs retain `acceptanceEligible=false`; only managed checks create bindings. Completion events retain the accepted requirement revisions, artifact IDs and generations. Transaction rollback emits neither a completion event nor completion memory.

This is an explicit per-goal managed JSON protocol with a limited scope. The broad execution-evidence ledger retains its existing prerequisites for global ENFORCE. Ordinary tool-success text and diagnostic MATCH results never become bindings automatically. Backend services cover success, invalidation, races and rollback; a real JWT/browser/service fixture and file-backed H2 upgrade/restart have also passed. Online-model execution and complete product end-to-end coverage are not implied.

ReAct, Plan and persistent-goal continuations receive managed JSON instructions. Business-skill tool allowlists retain the three goal-level read, publish and check tools, while service identity checks and child-agent restrictions still apply. For selected goals, follow-up and scheduling projections cannot end on a model completion claim or segment Complete alone: the Goal must already have committed completed status. Rejected automatic completion produces a continue result with recheck guidance.

Runtime completion must also carry server-issued identity. The completeGoal tool and automatic evaluation node use runtime completion entry points that recheck the enabled account, current goal/conversation/workspace/agent and scheduled-owner leases in the same transaction as all bindings. Valid bindings do not authorize an expired owner, a different goal or a revoked identity to complete. Internal platform completion APIs retain the binding gate; they are not identity-free model or HTTP entry points.

The Goal panel's Versions and checks section lets users inspect stored JSON, paste content and explicitly publish a version, then check each requirement. It shows requirement revisions, versions, expiry, quota and snapshot load time; final completion still checks current state. Conflicts require a reload, revoked access clears old content, and terminal goals are read-only. JSON is displayed as text rather than rendered HTML.

`GET /snapshot` returns requirements, current slots, check eligibility, goal status and version count under one goal lock. The agent's `getManagedGoalJsonSlots` uses this snapshot too, avoiding a mixed view from separate requirement and artifact reads.

## Deployment and upgrades

Deploy the managed JSON service and all goal writers together. Stop old application/scheduler instances before enabling requirements; do not run older binaries against goals using this protocol. Old writers do not know its completion gate. A rollback must preserve a compatible writer or restore a coordinated pre-upgrade application/database backup; never clear the required flag or delete requirements to make an old binary proceed.

V197 makes expiry authoritative in epoch seconds, independent of the JVM/JDBC timezone. Earlier managed records have wall-clock timestamps without a recoverable timezone, so upgrading keeps their bodies, requirements, generations and history but expires their acceptance eligibility. Publish a new version and check it under the current requirement. Existing completed goals remain historical completions; the migration does not reopen them. The 32-version quota still counts retained history.

The host clock, database credentials and service host remain trusted. Run arbitrary external code without service/database credentials and outside the service's storage permissions if it is not trusted; setting a working directory or scanning paths does not provide OS isolation. Use coordinated backups of the database for requirements, bodies, pointers and bindings; workspace-file backups alone cannot restore managed acceptance.

V198 also stores absolute scheduler lease deadlines. Existing leases expire during upgrade and are recovered from their persisted checkpoints: safe work may receive a new attempt; uncertain side effects remain blocked for review. Expired owners cannot renew, checkpoint, settle or use managed JSON tools. Renewal checks both current lease records after acquiring the goal lock, so a delayed scheduler tick cannot reuse an old timestamp to revive its owner. New valid owners can continue under the existing requirements. Recovery skips a scanned attempt while its continuation lease is still live or its owner has changed, so other eligible recoveries can proceed. A passing JSON binding does not override a pause caused by an uncertain tool outcome.

Validation snapshot (2026-09-14): the full default backend test run passed 5,249 executed tests with 33 conditional skips; the frontend passed 386 tests. The managed contract also has real compiled ReAct/Plan graph tests for account and scheduled-owner execution. Their model choices and semantic verdicts are controlled fixtures, not online-model benchmarks. Specialized integration profiles and the proprietary Kingbase engine are outside that full-default-suite claim.

Built-in shell/code execution is not OS-isolated from the service host. Selecting JSON acceptance does not sandbox those tools, and the protocol cannot defend against host code that can access database credentials or files. Environment-name filtering and workspace path checks do not replace that isolation. Lease deadlines are calculated from absolute instants, including daylight-saving clock rollback; scheduling display fields remain local timestamps.

Recovery attempts receive guidance to inspect existing evidence before repeating work. If the first recovered segment is deferred before execution, its recovery context is retained for the next claim. Ordinary continuation after an executed segment does not become a new recovery.

From V199, queued Web input stores the authenticated account ID at enqueue time, and ordinary Web replay carries the conversation workspace. Managed operations still recheck the account, ownership and current requirements. Legacy queue items do not gain an asserted identity from a username; users must resend an authenticated request for managed JSON operations. Persistent Goal workers retain their existing attempt-owner validation when consuming input; this does not introduce an account path without a lease check.

Approval replay restores the persisted runtime identity; approval does not renew an expired attempt lease or override account revocation. Legacy snapshots without an authenticated account ID cannot gain managed JSON access from a display username alone.
