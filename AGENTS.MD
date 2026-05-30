## Project principles
- Apply the principle of least surprise: behavior, naming, and generated APIs should be clear, consistent, and explicit.
- Avoid overloaded terms. If a term already has a project meaning, choose a distinct name or document the distinction.
- When behavior could reasonably surprise a user, prefer an explicit API, validation error, or documentation over implicit behavior.

## Reference implementations
- Use established ORM/entity systems as reference implementations for inspiration when designing schema APIs, generated surfaces, query behavior, privacy, migrations, eager loading, and documentation.
- Treat these references as comparative input, not authority. Preserve this project's Kotlin-first design, local conventions, and least-surprise behavior.
- References:
  - Expo Entity: https://github.com/expo/entity
  - Ent Framework: https://docs.ent-framework.net/
  - EntGo: https://entgo.io/
  - Ent.dev: https://ent.dev/
  - Rails Active Record: https://guides.rubyonrails.org/active_record_basics.html
  - Django Models: https://docs.djangoproject.com/en/stable/topics/db/models/
  - JetBrains Exposed: https://www.jetbrains.com/help/exposed/about.html

## Scope discipline
- Prefer the smallest safe change.
- Do not perform opportunistic refactors.
- Do not change unrelated files.
- Ask before adding dependencies, changing architecture, or expanding scope.
- If you think a broader fix is necessary, explain the tradeoff before editing.
- Don't worry about breaking changes. This is a greenfield project that isn't yet used in production. 

## Execution
- For any task larger than a small bugfix, propose a plan first.
- After planning, execute only step 1 unless explicitly told to continue.
- Verify with the narrowest relevant test command.

## Done when
- Requested behavior is implemented.
- Relevant checks pass.
- Diff remains minimal and easy to review.
