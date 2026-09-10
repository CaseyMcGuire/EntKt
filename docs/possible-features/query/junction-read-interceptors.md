# RFC: Junction Interceptors — Traversal And Edge Predicates

## Status

Partially implemented. Phase 1 (eager M2M junction discovery) shipped on
2026-08-20. Query-chain traversal and edge-predicate junction coverage remain
open, along with a separate question about mutation diff reads.

## Implemented Baseline

Direct junction queries and eager M2M discovery run the junction entity's
interceptors for both `throughEntity` and `throughLink` edges. The eager pass
uses `ReadOperation.EAGER_JUNCTION` before junction I/O and target interception.
It runs once per configured step even for an empty parent set; driver reads
remain data-gated.

Junction predicates narrow the associations contributed to target loading.
Discovery does not apply limits or junction LOAD privacy. The former explain
family recorded junction annotations separately; that public family is no
longer generated. Future diagnostics should retain separate junction
attribution. The runtime implementation lives in
[JunctionRelationshipReader](../../../runtime/src/main/kotlin/entkt/runtime/query/execution/JunctionRelationshipReader.kt);
[EagerJunctionInterceptorIntegrationTest](../../../integration-tests/src/test/kotlin/entkt/integrationtest/EagerJunctionInterceptorIntegrationTest.kt)
covers the delivered behavior.

## Remaining Problem

A junction interceptor such as tenant scoping or `ExcludeDeleted` narrows
direct reads and eager loads, but M2M traversal and edge predicates can still
use associations that the junction interceptor would exclude.

For example, a soft-deleted membership can stop contributing to `loadUsers()`
yet still satisfy a corresponding `has {}` condition. The remaining work is
to make those relational paths honor junction interception consistently.

## Open Phases

### Query-Chain Traversal

`queryX()` traversal uses `HasM2MEdgeFromShape`, which lowers to a junction
subquery carrying join columns. Its current predicate shape has no slot for
junction-side predicates. Extending coverage requires a predicate-model
extension and corresponding driver lowering.

### Edge Predicates

`has {}` and edge `exists()` use `HasEdge` / `HasEdgeWith`. Their M2M lowering
also lacks a junction predicate slot. Junction narrowing must be applied inside
the relationship subquery, before deciding whether an association exists.

These are edge predicate expressions, not the removed root query aggregate
terminals.

### Edge-Mutation Diff Reads

Link-table add/remove operations read current junction rows without
interceptors. Link junctions are payload-free by validation, so they cannot
have a soft-delete field. Whether tenant-style junction interceptors should
scope mutation diff reads remains an open write-path decision; read coverage
alone must not silently change mutation semantics.

## Constraints For The Remaining Design

- Preserve the distinction between interceptor scoping and LOAD authorization.
  Adding junction LOAD privacy would require a separate design.
- Keep each junction's predicates and diagnostic attribution separate from the
  target entity's predicates and annotations.
- Define operation/context and rejection precedence explicitly for traversal
  and predicate interception; do not silently reuse eager-only semantics.
- Retain source query bounds and ordering, target narrowing, null-endpoint
  handling, and empty-input behavior.
- Preserve the delivered eager discovery contract, including junction failure
  before target callbacks and no limit-based loss of associations.

## Test Requirements For Remaining Work

- Junction tenant/soft-delete predicates narrow M2M traversal and edge
  existence predicates consistently with direct reads and eager loading.
- Both junction kinds and nullable junction endpoints retain their contracts.
- Junction rejection occurs before affected storage execution with correct
  operation and path attribution.
- Explain reflects junction predicates and keeps their annotations separate.
- Repeated or nested relationship expressions have deterministic callback
  counts and ordering.
- Mutation diff semantics remain unchanged unless separately designed.

## Related Features

- [Eager Loading: Native M2M Windows And Chunking](set-based-eager-graph-loader.md)
- [Read-Path Interceptors](../../04-queries.md#read-path-interceptors)
- Soft Delete
