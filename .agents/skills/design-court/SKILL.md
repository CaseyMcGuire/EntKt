---
name: design-court
description: "Run a read-only EntKT API design court with three parallel custom agents: a Kotlin ergonomics reviewer, an ORM prior-art researcher, and an adversarial compatibility reviewer. Use only when the user explicitly invokes $design-court or explicitly asks for this three-reviewer design-court workflow to compare API designs without editing files."
---

# Design Court

Evaluate one bounded API question through three independent reviews, then
synthesize exactly two credible designs. Keep the entire court read-only.

## Establish the case

1. Read the applicable `AGENTS.md` instructions.
2. State the API question, desired behavior, constraints, and known alternatives.
3. Inspect only the relevant declarations, generated surfaces, representative
   call sites, tests, and documentation.
4. Prepare one neutral case packet for every reviewer. Include evidence and
   constraints, but do not anchor reviewers on a preferred conclusion.

Ask one concise clarifying question only when a missing choice would materially
change the court's scope. Otherwise, state reasonable assumptions and proceed.

Do not edit, create, delete, generate, or format repository files. Do not run
build, test, or code-generation commands that may write to the working tree.
Read-only web and documentation research is allowed.

## Convene the court

Spawn these three project-scoped custom agents concurrently:

- `kotlin_ergonomics_reviewer`
- `orm_prior_art_researcher`
- `adversarial_compatibility_reviewer`

Give each agent the same case packet plus its role-specific assignment. Do not
show one reviewer another reviewer's conclusions during the initial pass.

Require every reviewer to return:

1. Findings
2. Evidence
3. Design requirements
4. Risks and counterexamples
5. Open questions

Require file-and-symbol citations for repository claims and direct links for
external prior art. Require reviewers to label facts, inferences, and
preferences distinctly.

Wait for all three reviewers. Retry one failed reviewer once. If a required
reviewer remains unavailable, report that the court is incomplete instead of
silently substituting the parent agent's opinion.

## Synthesize the judgment

Return the following sections:

1. **Case** — Restate the question, constraints, and assumptions.
2. **Court record** — Summarize each review without erasing material differences.
3. **Consensus and disputes** — Separate agreement from unresolved disagreement.
4. **Design A** — Include a Kotlin call-site sketch, precise semantics, generated
   surface, validation and error behavior, prior-art basis, strengths, costs,
   and failure modes.
5. **Design B** — Cover the same dimensions as Design A.
6. **Recommendation** — Choose a design when evidence supports one; otherwise
   identify the decision criterion that should decide between them.
7. **Open questions** — List only questions that could change the decision.

Make both designs viable; do not include a strawman merely to create contrast.
Preserve EntKT's Kotlin-first design and principle of least surprise. Treat ORM
precedent as comparative evidence, not authority. Treat breaking changes as
acceptable in this greenfield project while still examining semantic,
generated-API, and future-extension compatibility.

Do not implement either design during the court. If implementation is also
requested, finish the court first and leave implementation for a separate
follow-up task after the user chooses a design.
