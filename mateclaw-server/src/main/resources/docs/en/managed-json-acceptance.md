# Managed JSON acceptance for Goals

Expand JSON acceptance requirements in the Goal panel. The conversation owner or an administrator explicitly saves up to eight requirements, each mapping an artifact slot to 1–16 top-level fields. Fields must exist and be non-null; false, zero and empty strings are allowed. This is a presence check, not a quality judgment. Opt-in is durable: requirements can be revised using their current revision, but required mode cannot be disabled. Stale revisions produce a conflict.

The current stage provides user configuration and independent managed version storage. Binding verification is available; the successful strong-completion path is still pending. Selected goals temporarily reject completion without falling back to textual claims. Unselected goals retain existing behavior.

## Managed version API

Prefix: `/api/v1/goals/{goalId}/json-acceptance`. An enabled account with conversation-owner or administrator permission is required. Preserve IDs, revisions and generations as strings in clients.

- `GET /`: read required mode and requirements.
- `PUT /requirements/{criterionKey}`: send `expectedRevision`, `artifactSlot` and `requiredFields`; use revision `0` for a new requirement.
- `GET /artifacts`: list required slots and current versions; an empty slot has generation `0`.
- `POST /artifacts/{slot}`: send `expectedGeneration` and `jsonContent` (a string containing the original JSON body) to append a version and atomically advance the slot.
- `GET /artifacts/versions/{artifactId}`: read metadata and exact content of a version belonging to this goal, including historical versions. Read access does not imply current acceptance eligibility.

Publication requires an active or paused goal and a slot referenced by a current requirement. Content must be a strict JSON object: duplicate keys, trailing documents, nesting beyond 32 levels and UTF-8 content over 1 MiB are rejected. Each goal can retain at most 32 versions; the limit rejects new publication instead of overwriting history. Each version expires after 24 hours. Republishing identical bytes still creates a new version. Reload after a generation conflict rather than automatically overwriting another publication.

Managed bodies live independently in the database. Ordinary workspace files, cache paths and hashes in text are not substitutes. No publication API edits historical bodies; bodies and pointers commit together. SHA-256 identifies content and supports integrity checks; it does not isolate an attacker with database credentials or host privileges. The database and service host are trusted foundations of this limited protocol. MySQL and Kingbase/PostgreSQL migrations have not yet been exercised against external database instances; H2 service integration tests do not establish that coverage.

## Agent publication

`getManagedGoalJsonSlots` returns current user requirements, slots and generations. `publishManagedGoalJson` accepts `artifactSlot`, a string `expectedGeneration` and `jsonContent`. Tools cannot configure requirements or supply goal IDs, accounts or owner fences. Interactive sessions require the authenticated account's internal ID. Scheduled persistent-goal execution must match the current continuation, attempt, owner token and live leases. Both paths recheck the conversation, workspace, agent and enabled account. The default delegation deny list includes both tools; the service still independently validates identity.

Publication and scheduler settlement serialize through the goal lock, rejecting late writes by former owners. Ending a lease does not mutate previously published versions. Anonymous sessions and cron runs without a bound goal attempt are outside this publication protocol. Missing identity is rejected instead of trusting a display username.


## Binding checks

After publication, call `POST /checks/{criterionKey}` with `expectedRequirementRevision`, `artifactId` and `expectedGeneration`, or use the agent tool `checkManagedGoalJson` with the same fields. Tool revisions and generations are strings. The server checks the specified current slot version using its own fields recipe; it never accepts a caller-provided PASS. `acceptanceEligible=true` applies to that requirement at the time of checking, not to whole-goal completion.

`GET /checks` reads each requirement's current eligibility. Requirement edits, goal-definition edits, a new slot version, expiry or failed body integrity checks invalidate previous bindings. Recheck the current inputs. Binding and goal-version updates share a transaction; rollback cannot leave a passing credential. Historical diagnostic APIs retain `acceptanceEligible=false`; only managed checks create bindings. Integration with the shared completion entry point is still pending.
