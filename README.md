# vkSQL

A distributed analytical query engine built from scratch in Java 21.

Columnar storage, vectorized execution, distributed query planning — designed to run TPC-H queries across multiple nodes.

## Architecture

```
SQL → Parser → Logical Plan → Optimizer → Physical Plan → Vectorized Executor
                                                              ↓
                                              Columnar Storage (custom format)
```

## Modules

- **storage** — Custom columnar file format with row groups, pages, min/max stats, and predicate pushdown
- **parser** — SQL parsing and AST generation (coming soon)
- **planner** — Logical/physical plan optimization (coming soon)
- **execution** — Vectorized batch execution engine (coming soon)
- **network** — gRPC shuffle for distributed execution (coming soon)

## Build & Test

```bash
./gradlew build
./gradlew :storage:test
```

## Status

- [x] Phase 1: Columnar storage engine (writer, reader, footer, stats)
- [ ] Phase 2: Query execution engine
- [ ] Phase 3: Distributed execution
- [ ] Phase 4: AI/ML extensions (vector search, HNSW)
