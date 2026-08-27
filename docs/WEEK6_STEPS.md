# Week 6: Logical Plan Optimizer

## What You're Building

A rule-based optimizer that rewrites logical query plans into more efficient forms. By the end, your optimizer takes a naive plan (scan everything, filter late) and produces one that pushes filters down, prunes columns, folds constants, and simplifies expressions — all automatically.

## Why This Matters

A naive planner produces correct but terrible plans:

```
Project [name, age]
  Filter [age > 5 AND age > 3]
    Join [orders.customer_id = customers.id]
      Scan [customers: ALL columns]
      Scan [orders: ALL columns]
```

After optimization:

```
Project [name, age]
  Join [orders.customer_id = customers.id]
    Filter [age > 5]
      Scan [customers: name, age, id]
    Scan [orders: customer_id]
```

Differences: filter pushed below join (processes fewer rows), redundant predicate removed, only needed columns scanned. This can be 10-100x faster on real data.

---

## Step 0: Define the Logical Plan Nodes

Before you can optimize plans, you need to represent them. If you haven't already, define these node types:

**File:** `planner/src/main/java/com/vksql/planner/plan/LogicalPlan.java`

```java
public sealed interface LogicalPlan permits Scan, Filter, Project, Join, Aggregate {
    List<LogicalPlan> children();
    LogicalPlan withChildren(List<LogicalPlan> newChildren);
    List<String> outputColumns();
}
```

Key idea: `withChildren()` lets you build new plans from old ones without mutation. You never modify a plan in-place — you create a new one with the desired changes.

**Node types you need:**
- `Scan` — table name, columns to read
- `Filter` — a predicate expression + one child
- `Project` — list of output expressions + one child
- `Join` — join condition + two children (left, right)
- `Aggregate` — group-by columns + aggregate functions + one child

**Syntax hint — sealed interfaces:**
```java
public sealed interface Shape permits Circle, Rect {
    double area();
}
public record Circle(double radius) implements Shape { ... }
public record Rect(double w, double h) implements Shape { ... }
```

---

## Step 1: Define the Rule Interface

**File:** `planner/src/main/java/com/vksql/planner/optimizer/Rule.java`

```java
public interface Rule {
    /** Does this rule apply to this node? */
    boolean matches(LogicalPlan node);

    /** Return a rewritten node. Called only if matches() returned true. */
    LogicalPlan apply(LogicalPlan node);

    /** Human-readable name for debugging. */
    String name();
}
```

**Why two methods?**
- `matches()` is cheap — quick structural check (is this a Filter on top of a Join?)
- `apply()` does the actual transformation — may be expensive
- Separating them lets the optimizer skip rules fast when they don't apply

**Design principles:**
- A rule looks at ONE node (and possibly its children), never the whole tree
- A rule returns a new node — never mutates the input
- If a rule doesn't apply, it should never be called with `apply()` (the optimizer checks `matches()` first)

---

## Step 2: The Visitor Pattern — How Rules See the Tree

Rules apply to individual nodes, but you need to walk the entire tree to find nodes where rules match. This is the **visitor pattern** (or more precisely, a top-down/bottom-up tree traversal).

**File:** `planner/src/main/java/com/vksql/planner/optimizer/PlanRewriter.java`

```java
public class PlanRewriter {

    /**
     * Walk the tree bottom-up, applying the transform function to each node.
     * Bottom-up means: rewrite children first, then the parent.
     */
    public static LogicalPlan transformUp(LogicalPlan plan, Function<LogicalPlan, LogicalPlan> fn) {
        // 1. Recursively transform all children
        List<LogicalPlan> newChildren = plan.children().stream()
            .map(child -> transformUp(child, fn))
            .toList();

        // 2. Create node with transformed children
        LogicalPlan updated = plan.withChildren(newChildren);

        // 3. Apply the function to this node
        return fn.apply(updated);
    }

    /**
     * Walk the tree top-down: apply the transform to the parent first,
     * then recurse into children.
     */
    public static LogicalPlan transformDown(LogicalPlan plan, Function<LogicalPlan, LogicalPlan> fn) {
        LogicalPlan transformed = fn.apply(plan);
        List<LogicalPlan> newChildren = transformed.children().stream()
            .map(child -> transformDown(child, fn))
            .toList();
        return transformed.withChildren(newChildren);
    }
}
```

**When to use which direction:**
- **Bottom-up** (`transformUp`): best for most rules. Children are already optimized when you see the parent.
- **Top-down** (`transformDown`): best for pushdown rules. You rewrite the parent first, then recurse to push things further down.

---

## Step 3: Fixed-Point Iteration

A single pass isn't enough. Applying one rule might enable another rule. Example:

1. Constant folding: `WHERE age > 1 + 2` → `WHERE age > 3`
2. Now filter simplification can see `age > 3 AND age > 5` → `age > 5`
3. Now predicate pushdown can push the simplified filter below the join

You need to apply ALL rules repeatedly until nothing changes:

**File:** `planner/src/main/java/com/vksql/planner/optimizer/Optimizer.java`

```java
public class Optimizer {
    private final List<Rule> rules;
    private static final int MAX_ITERATIONS = 50; // safety limit

    public Optimizer(List<Rule> rules) {
        this.rules = rules;
    }

    public LogicalPlan optimize(LogicalPlan plan) {
        LogicalPlan current = plan;

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            LogicalPlan next = applyAllRules(current);
            if (next.equals(current)) {
                // Fixed point reached — no rule changed anything
                break;
            }
            current = next;
        }
        return current;
    }

    private LogicalPlan applyAllRules(LogicalPlan plan) {
        LogicalPlan result = plan;
        for (Rule rule : rules) {
            result = PlanRewriter.transformUp(result, node -> {
                if (rule.matches(node)) {
                    return rule.apply(node);
                }
                return node;
            });
        }
        return result;
    }
}
```

**Why a max iteration limit?** Some buggy rules can fire endlessly (A → B → A → B...). The limit prevents infinite loops during development. 50 is generous — most plans converge in 3-5 iterations.

**Key insight:** For `.equals()` to work correctly, your plan nodes must implement proper equality (records do this for free!).

---

## Step 4: Predicate Pushdown Rule

The most impactful optimization. Moves filters as close to the data source as possible so fewer rows flow through the plan.

**File:** `planner/src/main/java/com/vksql/planner/optimizer/rules/PredicatePushdownRule.java`

**Pattern:** `Filter(condition, Join(left, right))` → push parts of the condition into left or right.

```java
public class PredicatePushdownRule implements Rule {

    @Override
    public String name() { return "PredicatePushdown"; }

    @Override
    public boolean matches(LogicalPlan node) {
        // Matches: Filter whose child is a Join
        return node instanceof Filter f && f.child() instanceof Join;
    }

    @Override
    public LogicalPlan apply(LogicalPlan node) {
        Filter filter = (Filter) node;
        Join join = (Join) filter.child();

        // Split the filter condition into conjuncts (AND parts)
        List<Expression> conjuncts = splitConjunction(filter.condition());

        List<Expression> leftFilters = new ArrayList<>();
        List<Expression> rightFilters = new ArrayList<>();
        List<Expression> remainingFilters = new ArrayList<>();

        for (Expression expr : conjuncts) {
            Set<String> refs = expr.referencedColumns();
            if (join.left().outputColumns().containsAll(refs)) {
                leftFilters.add(expr);
            } else if (join.right().outputColumns().containsAll(refs)) {
                rightFilters.add(expr);
            } else {
                // References both sides — can't push
                remainingFilters.add(expr);
            }
        }

        // Build new plan
        LogicalPlan newLeft = wrapWithFilter(join.left(), leftFilters);
        LogicalPlan newRight = wrapWithFilter(join.right(), rightFilters);
        LogicalPlan newJoin = new Join(join.condition(), newLeft, newRight);

        return wrapWithFilter(newJoin, remainingFilters);
    }

    private List<Expression> splitConjunction(Expression expr) {
        // a AND b AND c → [a, b, c]
        if (expr instanceof And and) {
            List<Expression> result = new ArrayList<>();
            result.addAll(splitConjunction(and.left()));
            result.addAll(splitConjunction(and.right()));
            return result;
        }
        return List.of(expr);
    }

    private LogicalPlan wrapWithFilter(LogicalPlan plan, List<Expression> conditions) {
        if (conditions.isEmpty()) return plan;
        Expression combined = combineConjunction(conditions);
        return new Filter(combined, plan);
    }

    private Expression combineConjunction(List<Expression> exprs) {
        // [a, b, c] → a AND b AND c
        return exprs.stream().reduce((a, b) -> new And(a, b)).orElseThrow();
    }
}
```

**What `referencedColumns()` does:** Returns the set of column names an expression touches. `age > 5` → `{"age"}`. `a + b > c` → `{"a", "b", "c"}`.

**Edge case:** If a predicate references columns from BOTH sides of a join (e.g., `left.a + right.b > 10`), it CANNOT be pushed down. Leave it above the join.

---

## Step 5: Projection Pushdown Rule

Only read the columns you actually need. If a query does `SELECT name FROM customers`, don't read all 50 columns from disk.

**File:** `planner/src/main/java/com/vksql/planner/optimizer/rules/ProjectionPushdownRule.java`

**Pattern:** Push required columns down through the tree until you reach a `Scan`, then reduce the scan's column list.

```java
public class ProjectionPushdownRule implements Rule {

    @Override
    public String name() { return "ProjectionPushdown"; }

    @Override
    public boolean matches(LogicalPlan node) {
        // Matches: Project whose child is a Scan reading more columns than needed
        return node instanceof Project p && p.child() instanceof Scan scan
            && !scan.columns().equals(requiredColumns(p));
    }

    @Override
    public LogicalPlan apply(LogicalPlan node) {
        Project project = (Project) node;
        Set<String> needed = requiredColumns(project);
        return pushColumnsDown(project, needed);
    }

    private LogicalPlan pushColumnsDown(LogicalPlan node, Set<String> neededAbove) {
        if (node instanceof Scan scan) {
            // Narrow the scan to only needed columns
            List<String> narrowed = scan.columns().stream()
                .filter(neededAbove::contains)
                .toList();
            return new Scan(scan.tableName(), narrowed);
        }

        if (node instanceof Filter filter) {
            // Filter needs its own referenced columns too
            Set<String> needed = new HashSet<>(neededAbove);
            needed.addAll(filter.condition().referencedColumns());
            LogicalPlan newChild = pushColumnsDown(filter.child(), needed);
            return new Filter(filter.condition(), newChild);
        }

        if (node instanceof Join join) {
            // Join condition references columns from both sides
            Set<String> needed = new HashSet<>(neededAbove);
            needed.addAll(join.condition().referencedColumns());
            // Split needed into left/right based on which side produces them
            Set<String> leftNeeded = intersect(needed, join.left().outputColumns());
            Set<String> rightNeeded = intersect(needed, join.right().outputColumns());
            return new Join(join.condition(),
                pushColumnsDown(join.left(), leftNeeded),
                pushColumnsDown(join.right(), rightNeeded));
        }

        // For Project: union of what's above and what the expressions reference
        if (node instanceof Project project) {
            Set<String> needed = new HashSet<>();
            for (Expression expr : project.expressions()) {
                needed.addAll(expr.referencedColumns());
            }
            return new Project(project.expressions(),
                pushColumnsDown(project.child(), needed));
        }

        return node;
    }
}
```

**Think about:** This rule is different — it's recursive by itself (not relying on the tree walker). Some rules work better as a single top-down pass over the tree rather than the match-one-node-at-a-time pattern. That's fine. Your `Rule` interface is flexible enough.

---

## Step 6: Filter Simplification Rule

Removes redundant predicates in AND/OR expressions.

**File:** `planner/src/main/java/com/vksql/planner/optimizer/rules/FilterSimplificationRule.java`

**Simplifications to implement:**
- `x > 5 AND x > 3` → `x > 5` (the stricter bound subsumes the weaker)
- `x > 5 AND x > 5` → `x > 5` (duplicate removal)
- `x > 5 AND TRUE` → `x > 5`
- `x > 5 AND FALSE` → `FALSE` (short-circuit)
- `x > 5 OR TRUE` → `TRUE`
- `x > 5 OR FALSE` → `x > 5`

```java
public class FilterSimplificationRule implements Rule {

    @Override
    public String name() { return "FilterSimplification"; }

    @Override
    public boolean matches(LogicalPlan node) {
        return node instanceof Filter f && canSimplify(f.condition());
    }

    @Override
    public LogicalPlan apply(LogicalPlan node) {
        Filter filter = (Filter) node;
        Expression simplified = simplify(filter.condition());

        // If simplified to TRUE, remove the filter entirely
        if (simplified instanceof Literal lit && lit.value().equals(Boolean.TRUE)) {
            return filter.child();
        }

        return new Filter(simplified, filter.child());
    }

    private Expression simplify(Expression expr) {
        if (expr instanceof And and) {
            Expression left = simplify(and.left());
            Expression right = simplify(and.right());

            // TRUE AND x → x
            if (isTrue(left)) return right;
            if (isTrue(right)) return left;

            // FALSE AND x → FALSE
            if (isFalse(left) || isFalse(right)) return Literal.FALSE;

            // x > 5 AND x > 3 → x > 5 (same column, both > comparisons)
            Expression subsumed = trySubsumeRanges(left, right);
            if (subsumed != null) return subsumed;

            return new And(left, right);
        }

        if (expr instanceof Or or) {
            Expression left = simplify(or.left());
            Expression right = simplify(or.right());

            if (isTrue(left) || isTrue(right)) return Literal.TRUE;
            if (isFalse(left)) return right;
            if (isFalse(right)) return left;

            return new Or(left, right);
        }

        return expr;
    }

    /**
     * If both are comparisons on the same column with the same operator,
     * keep the stricter one.
     * x > 5 AND x > 3 → x > 5  (5 is stricter for >)
     * x < 5 AND x < 3 → x < 3  (3 is stricter for <)
     */
    private Expression trySubsumeRanges(Expression left, Expression right) {
        if (!(left instanceof Comparison cLeft)) return null;
        if (!(right instanceof Comparison cRight)) return null;
        if (!cLeft.column().equals(cRight.column())) return null;
        if (cLeft.op() != cRight.op()) return null;
        if (!(cLeft.value() instanceof Literal litLeft)) return null;
        if (!(cRight.value() instanceof Literal litRight)) return null;

        // Both are "col OP literal" with same col and op
        Comparable<?> valLeft = (Comparable) litLeft.value();
        Comparable<?> valRight = (Comparable) litRight.value();

        return switch (cLeft.op()) {
            case GT, GTE -> compareValues(valLeft, valRight) >= 0 ? left : right;
            case LT, LTE -> compareValues(valLeft, valRight) <= 0 ? left : right;
            default -> null;
        };
    }
}
```

**Key insight:** Range subsumption only works when both predicates reference the same column with the same operator and compare against literals. Don't try to simplify `a > b AND a > 5` — that's too complex.

---

## Step 7: Constant Folding Rule

Evaluate constant expressions at plan time rather than at execution time.

**File:** `planner/src/main/java/com/vksql/planner/optimizer/rules/ConstantFoldingRule.java`

**What it does:**
- `1 + 2` → `3`
- `'hello' || ' world'` → `'hello world'` (string concatenation)
- `10 * 2 > 15` → `TRUE`
- `CAST(3.14 AS INT)` → `3`

This rule operates on **expressions**, not on plan nodes. So it's slightly different — it walks expressions inside Filter/Project nodes.

```java
public class ConstantFoldingRule implements Rule {

    @Override
    public String name() { return "ConstantFolding"; }

    @Override
    public boolean matches(LogicalPlan node) {
        // Matches any node that contains a foldable expression
        if (node instanceof Filter f) return hasConstantSubExpr(f.condition());
        if (node instanceof Project p) return p.expressions().stream()
            .anyMatch(this::hasConstantSubExpr);
        return false;
    }

    @Override
    public LogicalPlan apply(LogicalPlan node) {
        if (node instanceof Filter f) {
            return new Filter(fold(f.condition()), f.child());
        }
        if (node instanceof Project p) {
            List<Expression> folded = p.expressions().stream()
                .map(this::fold)
                .toList();
            return new Project(folded, p.child());
        }
        return node;
    }

    private Expression fold(Expression expr) {
        if (expr instanceof BinaryArith arith) {
            Expression left = fold(arith.left());
            Expression right = fold(arith.right());

            if (left instanceof Literal litL && right instanceof Literal litR) {
                // Both sides are constants — evaluate now
                Object result = evaluate(arith.op(), litL.value(), litR.value());
                return new Literal(result);
            }
            return new BinaryArith(arith.op(), left, right);
        }

        if (expr instanceof Comparison cmp) {
            Expression left = fold(cmp.left());
            Expression right = fold(cmp.right());

            if (left instanceof Literal litL && right instanceof Literal litR) {
                boolean result = evaluateComparison(cmp.op(), litL.value(), litR.value());
                return new Literal(result);
            }
            return new Comparison(cmp.op(), left, right);
        }

        // Recurse into AND/OR
        if (expr instanceof And and) {
            return new And(fold(and.left()), fold(and.right()));
        }
        if (expr instanceof Or or) {
            return new Or(fold(or.left()), fold(or.right()));
        }

        return expr; // Column refs, literals — already folded
    }

    private Object evaluate(ArithOp op, Object left, Object right) {
        // Assuming numeric types for now
        if (left instanceof Integer l && right instanceof Integer r) {
            return switch (op) {
                case ADD -> l + r;
                case SUB -> l - r;
                case MUL -> l * r;
                case DIV -> r != 0 ? l / r : null; // handle div-by-zero
            };
        }
        // Handle long, double similarly...
        throw new UnsupportedOperationException("Cannot fold: " + left + " " + op + " " + right);
    }

    private boolean hasConstantSubExpr(Expression expr) {
        if (expr instanceof BinaryArith arith) {
            return (isConstant(arith.left()) && isConstant(arith.right()))
                || hasConstantSubExpr(arith.left())
                || hasConstantSubExpr(arith.right());
        }
        // ... check recursively
        return false;
    }

    private boolean isConstant(Expression expr) {
        return expr instanceof Literal;
    }
}
```

**Careful with:** Division by zero. If you fold `1 / 0`, you'd get an exception at optimization time. Either skip folding when divisor is 0, or fold to a special `Error` literal.

---

## Step 8: Expression Model (if you don't have one yet)

The rules above reference an Expression tree. Here's the minimal set you need:

**File:** `planner/src/main/java/com/vksql/planner/expr/Expression.java`

```java
public sealed interface Expression permits
    Literal, ColumnRef, BinaryArith, Comparison, And, Or, Not {

    /** Column names this expression depends on. */
    Set<String> referencedColumns();
}
```

**Core expression types:**
```java
public record Literal(Object value) implements Expression {
    public static final Literal TRUE = new Literal(Boolean.TRUE);
    public static final Literal FALSE = new Literal(Boolean.FALSE);

    @Override public Set<String> referencedColumns() { return Set.of(); }
}

public record ColumnRef(String name) implements Expression {
    @Override public Set<String> referencedColumns() { return Set.of(name); }
}

public record BinaryArith(ArithOp op, Expression left, Expression right) implements Expression {
    @Override public Set<String> referencedColumns() {
        var result = new HashSet<>(left.referencedColumns());
        result.addAll(right.referencedColumns());
        return result;
    }
}

public record Comparison(CompareOp op, Expression left, Expression right) implements Expression {
    @Override public Set<String> referencedColumns() {
        var result = new HashSet<>(left.referencedColumns());
        result.addAll(right.referencedColumns());
        return result;
    }
}

public record And(Expression left, Expression right) implements Expression { ... }
public record Or(Expression left, Expression right) implements Expression { ... }
```

---

## Step 9: Testing — Before and After Plans

**File:** `planner/src/test/java/com/vksql/planner/optimizer/OptimizerTest.java`

The best way to test optimizations: show the plan before and after, and assert the structure changed as expected.

```java
@Test
void predicatePushdownMovesFilterBelowJoin() {
    // Before:
    // Filter(age > 25)
    //   Join(customers.id = orders.cust_id)
    //     Scan(customers: [id, name, age])
    //     Scan(orders: [cust_id, amount])
    LogicalPlan before = new Filter(
        new Comparison(GT, new ColumnRef("age"), new Literal(25)),
        new Join(
            new Comparison(EQ, new ColumnRef("id"), new ColumnRef("cust_id")),
            new Scan("customers", List.of("id", "name", "age")),
            new Scan("orders", List.of("cust_id", "amount"))
        )
    );

    LogicalPlan after = optimizer.optimize(before);

    // After: filter should be pushed into the left child of the join
    // Join(customers.id = orders.cust_id)
    //   Filter(age > 25)
    //     Scan(customers: [id, name, age])
    //   Scan(orders: [cust_id, amount])
    assertThat(after).isInstanceOf(Join.class);
    Join join = (Join) after;
    assertThat(join.left()).isInstanceOf(Filter.class);
    Filter pushed = (Filter) join.left();
    assertThat(pushed.condition()).isEqualTo(
        new Comparison(GT, new ColumnRef("age"), new Literal(25))
    );
}

@Test
void constantFolding() {
    // Filter(age > 1 + 2) → Filter(age > 3)
    LogicalPlan before = new Filter(
        new Comparison(GT, new ColumnRef("age"), new BinaryArith(ADD, new Literal(1), new Literal(2))),
        new Scan("users", List.of("age"))
    );

    LogicalPlan after = optimizer.optimize(before);

    Filter filter = (Filter) after;
    Comparison cmp = (Comparison) filter.condition();
    assertThat(cmp.right()).isEqualTo(new Literal(3));
}

@Test
void filterSimplification() {
    // Filter(age > 5 AND age > 3) → Filter(age > 5)
    LogicalPlan before = new Filter(
        new And(
            new Comparison(GT, new ColumnRef("age"), new Literal(5)),
            new Comparison(GT, new ColumnRef("age"), new Literal(3))
        ),
        new Scan("users", List.of("age"))
    );

    LogicalPlan after = optimizer.optimize(before);

    Filter filter = (Filter) after;
    assertThat(filter.condition()).isEqualTo(
        new Comparison(GT, new ColumnRef("age"), new Literal(5))
    );
}

@Test
void allRulesWorkTogether() {
    // Filter(age > 1 + 2 AND age > 3)
    //   Join(...)
    //     Scan(customers: ALL)
    //     Scan(orders: ALL)
    //
    // After optimization:
    // - Constant fold: 1 + 2 → 3
    // - Simplify: age > 3 AND age > 3 → age > 3
    // - Push filter below join
    // - Narrow scan columns
}
```

**Plan printer helper (invaluable for debugging):**

```java
public class PlanPrinter {
    public static String print(LogicalPlan plan) {
        return print(plan, 0);
    }

    private static String print(LogicalPlan plan, int indent) {
        String prefix = "  ".repeat(indent);
        StringBuilder sb = new StringBuilder();
        sb.append(prefix).append(formatNode(plan)).append("\n");
        for (LogicalPlan child : plan.children()) {
            sb.append(print(child, indent + 1));
        }
        return sb.toString();
    }

    private static String formatNode(LogicalPlan node) {
        return switch (node) {
            case Scan s -> "Scan[%s: %s]".formatted(s.tableName(), s.columns());
            case Filter f -> "Filter[%s]".formatted(f.condition());
            case Project p -> "Project[%s]".formatted(p.expressions());
            case Join j -> "Join[%s]".formatted(j.condition());
            case Aggregate a -> "Aggregate[groupBy=%s]".formatted(a.groupByColumns());
        };
    }
}
```

Use this in tests:
```java
System.out.println("BEFORE:");
System.out.println(PlanPrinter.print(before));
System.out.println("AFTER:");
System.out.println(PlanPrinter.print(after));
```

---

## Order of Implementation

1. **Expression model** — `Literal`, `ColumnRef`, `Comparison`, `BinaryArith`, `And`, `Or`
2. **Logical plan nodes** — `Scan`, `Filter`, `Project`, `Join` (with `children()`, `withChildren()`, `outputColumns()`)
3. **PlanPrinter** — you'll need this immediately for debugging
4. **Rule interface** — `matches()`, `apply()`, `name()`
5. **PlanRewriter** — `transformUp` / `transformDown`
6. **Optimizer** — fixed-point loop
7. **ConstantFoldingRule** — simplest rule, good first test
8. **FilterSimplificationRule** — second simplest
9. **PredicatePushdownRule** — the big one, most impactful
10. **ProjectionPushdownRule** — needs `referencedColumns()` working correctly
11. **Integration tests** — chain all rules together, verify convergence

---

## Concepts You'll Learn

| Concept | Where You'll Hit It |
|---------|-------------------|
| Visitor pattern | Tree traversal in PlanRewriter |
| Fixed-point iteration | Optimizer loop — apply until stable |
| Immutable trees | `withChildren()` — never mutate, always rebuild |
| Expression rewriting | Constant folding, simplification |
| Predicate analysis | `referencedColumns()`, conjunction splitting |
| Rule-based systems | Declarative optimization — add rules without changing the engine |

---

## Common Mistakes

1. **Mutating plan nodes.** Never do `node.setChild(newChild)`. Plans must be immutable. Use `withChildren()` to create a new node. If you mutate, the fixed-point check (`equals()`) breaks.

2. **Forgetting to include join-condition columns in projection pushdown.** If you push projections below a join but forget the join key columns, the join has nothing to join on. Always include `join.condition().referencedColumns()` in the needed set.

3. **Pushing predicates that reference both sides of a join.** `WHERE left.a + right.b > 10` touches columns from both join inputs. It CANNOT be pushed to either side. Classify predicates carefully.

4. **Infinite loops in fixed-point.** If rule A transforms X→Y and rule B transforms Y→X, you loop forever. The max-iteration limit catches this, but fix the root cause: ensure your rules are monotonically "improving" (e.g., always pushing down, never pulling up).

5. **Not recursing into sub-expressions during constant folding.** `(1 + 2) * 3` requires folding `1 + 2` first (inner), THEN folding `3 * 3` (outer). Always fold children before the parent (bottom-up on expressions).

6. **Wrong equality semantics.** If your plan nodes don't implement `equals()` correctly, the fixed-point check (`next.equals(current)`) never returns true and you hit MAX_ITERATIONS every time. Java records give you correct equality for free — use them.

7. **Over-aggressive projection pushdown.** If a column is referenced in a filter above, you must keep it visible through all intermediate nodes. Track needed columns by walking UP from the scan, not just looking at the immediate parent.

---

## When You're Done

- ✅ `ConstantFoldingRule` folds `1 + 2` to `3` inside filter/project expressions
- ✅ `FilterSimplificationRule` removes redundant range predicates and short-circuits TRUE/FALSE
- ✅ `PredicatePushdownRule` moves filters below joins (correct side only)
- ✅ `ProjectionPushdownRule` narrows scans to only needed columns
- ✅ Fixed-point iteration converges (doesn't hit MAX_ITERATIONS on your test cases)
- ✅ Rules compose — running all 4 on a complex plan produces the expected optimized plan
- ✅ `PlanPrinter` shows readable before/after plans in test output
- ✅ All tests pass: `./gradlew :planner:test`

**Next week:** Physical plan generation — convert logical plans into executable operators with cost-based join ordering.
