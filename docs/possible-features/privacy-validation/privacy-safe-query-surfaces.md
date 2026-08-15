# RFC: Privacy-Safe Query Surfaces

## Status

Rejected 2026-08-14. This narrowed query hierarchy will not be implemented.

## Decision

`EntPrivacyReadClient` keeps the same generated repository and query surface as
the other read clients. Its materializing terminals remain viewer-scoped:
`findById`, `firstOrNull`, `all`, traversals, and eager loads evaluate LOAD
privacy before returning entities.

Raw terminals remain available and have one explicit meaning in every read
posture:

- `rawCount`, `rawExists`, and raw aggregates inspect storage
- they run read interceptors under the operation's captured privacy context
- they do not materialize entities or evaluate LOAD privacy
- ordinary execution failures are returned as `ReadResult.Failed`

The previous runtime rejection on privacy-rule clients is removed.

## Rationale

Privacy rules are trusted server-side authorization code. A raw existence,
count, or aggregate can be a legitimate control-plane input such as ACL or
membership state without disclosing an entity to application code. Raw reads
also let a LOAD rule consult storage facts without recursively materializing an
entity whose LOAD policy would re-enter the rule graph.

The `raw` prefix already identifies the important semantic boundary. A second,
recursively narrowed query hierarchy would add generated API complexity while
removing a capability trusted rule authors may intentionally need. If entity
visibility must participate in a decision, the rule must choose a materializing
terminal instead.

`EntPrivacyReadClient` and `EntValidationReadClient` remain separate types
because their materializing reads still have different viewer semantics. Their
shared raw terminals do not erase that distinction.

## Consequences

Rule authors are responsible for deciding whether a storage-level fact is an
appropriate authorization input. A raw result proves only that matching
storage exists; it does not prove the viewer could load the matching entities.
EntKt documents and tests that distinction rather than trying to infer intent
from the calling context.
