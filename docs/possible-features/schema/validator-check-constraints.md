# RFC: Validator-Derived CHECK Constraints

## Status

Possible future feature. This is not implemented.

Companion to [Enum Value CHECK Constraints](enum-value-check-constraints.md)
(same mechanism, enum value sets instead of validators) and to the
validators section of [Schema](../../02-schema.md#validators), which
documents today's behavior: validators are application-tier only.

## Motivation

Schema declarations are entkt's source of truth for the physical database —
columns, types, nullability, uniqueness, FKs, and indexes all flow from the
schema class into DDL and migrations. Validators are declared in the same
chain (`string("name").unique().maxLength(255)`) but do **not** flow down:
`.unique()` becomes a database constraint while `.maxLength(255)` becomes
only an inline check in the generated `save()` paths. From the schema
author's seat that split is arbitrary — the natural reading of a constraint
declared on a column is that the *column* enforces it.

The practical gap: nothing defends the declared invariants against writers
that bypass the generated builders — raw `driver.insert/update`, other
services sharing the database, manual psql. For a single-writer app the
generated client is the gate; for anything else the schema's claims are
aspirations, not invariants.

Reference implementations split on this: Django's `max_length` is both the
app validator and `varchar(n)` DDL (the push-down model); Rails and EntGo
keep validators app-side. entkt's schema-first, migration-generating design
couples the schema to the database more tightly than Rails does, which
strengthens the case for push-down here.

## Design

**Dual enforcement, not relocation.** App-tier validation stays exactly as
is — it reports all violations at once, field-named, before a round trip,
with Kotlin semantics. Translatable validators *additionally* emit named
`CHECK` constraints into DDL, so the database enforces the same invariant
against every writer.

### Translation table

| Validator | Constraint | Fidelity |
|---|---|---|
| `notEmpty()` | `CHECK (col <> '')` | exact |
| `min(n)` / `max(n)` / `positive()` / `negative()` / `nonNegative()` | `CHECK (col >= n)` etc. | exact |
| `minLength(n)` / `maxLength(n)` | `CHECK (char_length(col) >= n)` etc. | approximate — see below |
| `match(regex)` | none | **not translated** |

- **Length semantics.** Kotlin's `String.length` counts UTF-16 code units;
  Postgres `char_length()` counts characters. They disagree on
  supplementary-plane characters (emoji): `"🙂".length == 2` in Kotlin,
  `char_length('🙂') == 1` in Postgres. The DB constraint is therefore
  *looser* than the app check for such strings — the app remains the
  precise gate; the CHECK is the backstop. Document this at the DSL; do not
  attempt to replicate code-unit counting in SQL.
- **`match(regex)` is never translated.** Kotlin and Postgres regex dialects
  differ (`\d`, lookaheads, Unicode classes); a `~` CHECK would enforce a
  *different* predicate under the same declaration, which is worse than no
  constraint. App-tier only, stated in the `match` KDoc.

### Storage choice: `text` + `CHECK`, not `varchar(n)`

`maxLength` stays a `CHECK` on a `text` column rather than becoming
`varchar(n)`:

- Loosening a `CHECK` is drop+add of a constraint; loosening `varchar(n)`
  is an `ALTER COLUMN TYPE` (historically table-rewriting or at least
  scan-validating, and differ-visible as a type change).
- One mechanism covers every translatable validator (`varchar` covers only
  max length).
- The column type stays stable across validator churn, so the type-diffing
  half of the migration differ is untouched.

### Constraint naming

Deterministic, diff-stable names:
`ck_<table>_<column>_<validator>` (e.g. `ck_assets_name_maxlength`),
normalized through the existing identifier truncation. The name is the
differ's join key and the reverse-mapping key for error classification.

### Migrations

- The differ compares constraints by name + normalized expression, exactly
  as sketched for enum CHECKs. Expression comparison must canonicalize
  against Postgres's re-rendering (`pg_get_constraintdef`), which
  normalizes whitespace/parentheses/casts — compare canonicalized forms,
  never raw strings.
- Tightening (or adding) a constraint emits `ADD CONSTRAINT ... CHECK`,
  which **validates existing rows and fails loudly** if legacy data
  violates it — the same deliberate-backfill forcing function as the enum
  RFC. `NOT VALID` + `VALIDATE CONSTRAINT` staging is an escape hatch worth
  supporting for large tables.
- Loosening emits drop+add; removing a validator drops the constraint.

### Error classification

A CHECK violation that does slip past the app (out-of-band writer racing a
deploy, or raw driver writes) surfaces as SQLSTATE `23514`.
`classifyException` already maps `23xxx` to `EntError.ConstraintViolation`
with the constraint name; the deterministic naming scheme lets it also
populate `field` by parsing `ck_<table>_<column>_...`.

## Decision points (owner's call)

1. **Default-on vs opt-in.** Default-on matches the "declared on the column
   ⇒ enforced by the column" reading and the project's
   breaking-changes-are-fine posture; every existing project's next diff
   gains ADD CONSTRAINT statements (and fails on legacy violations — argu-
   ably the point). Opt-in (`entkt { validatorChecks = true }` or per-field)
   is gentler but preserves today's surprise.
2. **Length-check fidelity note**: accept the char/code-unit divergence as
   documented looseness (recommended), or skip length checks entirely.
3. **Scope of first cut**: string + numeric validators only (the table
   above); `match` excluded permanently unless a verified-dialect opt-in is
   ever designed.

## Non-Goals

- No native `varchar(n)`/domain types.
- No SQL translation of `match(regex)`.
- No attempt to make the CHECK the *primary* error path — app-tier
  validation remains the user-facing gate; constraints are defense in depth.
