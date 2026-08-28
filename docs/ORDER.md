# vkSQL — Implementation Order (Revised)

## Philosophy: Get to a working query engine FIRST, then optimize.

The goal is to execute a SQL query end-to-end as fast as possible.
Encoding, compression, and advanced optimizations come after you have something that works.

---

## Phase 1: Storage (DONE)
- ✅ Week 1: Columnar writer, reader, footer, stats
- ✅ Week 2: STRING columns, null bitmaps, ColumnReader

## Phase 2: Query Engine (V1 — get it working)
- Week 3: SQL Parsing + Logical Plan (was Week 5) → `WEEK5_STEPS.md`
- Week 4: Physical Plan + Volcano Execution (was Week 7) → `WEEK7_STEPS.md`
- Week 5: Hash Join + Hash Aggregate (was Week 9) → `WEEK9_STEPS.md`

**Milestone: You can run `SELECT sum(price) FROM orders WHERE date > '2024-01' GROUP BY nation` end-to-end.**

## Phase 3: Make it fast
- Week 6: Vectorized Execution (was Week 8) → `WEEK8_STEPS.md`
- Week 7: Logical Plan Optimizer (was Week 6) → `WEEK6_STEPS.md`
- Week 8: Predicate Pushdown + Benchmarks (was Week 4) → `WEEK4_STEPS.md`
- Week 9: Encoding + Compression (was Week 3) → `WEEK3_STEPS.md`

**Milestone: Same queries but 5-10x faster. Benchmarks to prove it.**

## Phase 4: Distributed
- Weeks 10-14: Partitioning, shuffle, coordinator, fault tolerance → `WEEK10_14_STEPS.md`

## Phase 5: AI/ML Extensions
- Weeks 15-18: Vector search, HNSW, feature serving, ONNX → `WEEK15_18_STEPS.md`

---

## Why this order?

1. You get a **working SQL engine** by Week 5 — you can demo it, talk about it in interviews
2. Optimization is more meaningful when you can benchmark before/after
3. Compression/encoding are features you add to an existing system, not prerequisites
4. Motivation: seeing `SELECT * FROM orders WHERE price > 100` return results > reading about delta encoding
