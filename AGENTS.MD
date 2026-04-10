## Scope discipline
- Prefer the smallest safe change.
- Do not perform opportunistic refactors.
- Do not change unrelated files.
- Ask before adding dependencies, changing architecture, or expanding scope.
- If you think a broader fix is necessary, explain the tradeoff before editing.

## Execution
- For any task larger than a small bugfix, propose a plan first.
- After planning, execute only step 1 unless explicitly told to continue.
- Verify with the narrowest relevant test command.

## Done when
- Requested behavior is implemented.
- Relevant checks pass.
- Diff remains minimal and easy to review.