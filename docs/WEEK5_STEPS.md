# Week 5: SQL Parsing + Logical Plan

## What You're Building

A SQL parser that takes a query string like `SELECT l_returnflag, sum(l_extendedprice) FROM lineitem WHERE l_shipdate <= '1998-09-02' GROUP BY l_returnflag ORDER BY l_returnflag` and turns it into a **logical plan tree** — a graph of relational algebra operators that describes *what* the query does without saying *how* to execute it.

By the end of this week, you can parse TPC-H Q1 and produce a tree like:

```
ProjectNode [l_returnflag, sum_qty, sum_base_price, ...]
  SortNode [l_returnflag ASC]
    AggregateNode [groupBy=l_returnflag, aggs=sum(l_quantity), sum(l_extendedprice), ...]
      FilterNode [l_shipdate <= '1998-09-02']
        ScanNode [lineitem]
```

---

## Why Logical Plans?

SQL is **declarative** — it says what you want, not how to get it. But the engine needs an **imperative** plan:
- Which table to scan
- Which filters to apply (and when)
- Which columns to project
- How to aggregate / sort / limit

The logical plan is the bridge. It's a tree of relational operators that the optimizer (next week) can rewrite to find the fastest execution strategy.

---

## Step 0: Add the Parser Module

Create a new Gradle submodule `parser`:

**`settings.gradle.kts`** — add:
```kotlin
include("parser")
```

**`parser/build.gradle.kts`:**
```kotlin
plugins {
    id("java-library")
    id("antlr")
}

dependencies {
    antlr("org.antlr:antlr4:4.13.1")
    implementation("org.antlr:antlr4-runtime:4.13.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.generateGrammarSource {
    arguments = arguments + listOf("-visitor", "-package", "com.vksql.parser.generated")
    outputDirectory = file("${project.buildDir}/generated-src/antlr/main/com/vksql/parser/generated")
}

sourceSets {
    main {
        java {
            srcDir("${project.buildDir}/generated-src/antlr/main")
        }
    }
}
```

Key points:
- The `antlr` plugin integrates grammar compilation into the Gradle build
- `-visitor` flag generates the Visitor pattern classes (you'll use these to walk the tree)
- `-package` puts generated code in a package so it doesn't collide with your code

---

## Step 1: Write the ANTLR4 Grammar

**File:** `parser/src/main/antlr/com/vksql/parser/generated/VkSql.g4`

This is the grammar that defines what SQL you can parse. Start minimal — only what TPC-H Q1 needs.

```antlr
grammar VkSql;

// === Parser Rules ===

statement
    : query EOF
    ;

query
    : SELECT selectList
      FROM tableRef
      (WHERE whereExpr)?
      (GROUP BY groupByList)?
      (ORDER BY orderByList)?
      (LIMIT limitVal=INTEGER_LITERAL)?
    ;

selectList
    : selectItem (',' selectItem)*
    ;

selectItem
    : expression (AS? alias=IDENTIFIER)?
    ;

tableRef
    : tableName=IDENTIFIER (AS? alias=IDENTIFIER)?       # simpleTable
    | tableRef JOIN tableRef ON expression               # joinTable
    ;

whereExpr
    : expression
    ;

groupByList
    : expression (',' expression)*
    ;

orderByList
    : orderByItem (',' orderByItem)*
    ;

orderByItem
    : expression (ASC | DESC)?
    ;

// === Expressions ===

expression
    : expression op=(STAR | SLASH) expression            # mulDiv
    | expression op=(PLUS | MINUS) expression            # addSub
    | expression op=(EQ | NEQ | LT | LTE | GT | GTE) expression  # comparison
    | expression AND expression                          # andExpr
    | expression OR expression                           # orExpr
    | NOT expression                                     # notExpr
    | functionName=IDENTIFIER '(' (expression (',' expression)*)? ')'  # functionCall
    | functionName=IDENTIFIER '(' STAR ')'               # countStar
    | tableName=IDENTIFIER '.' columnName=IDENTIFIER     # qualifiedColumn
    | IDENTIFIER                                         # columnRef
    | INTEGER_LITERAL                                    # intLiteral
    | DECIMAL_LITERAL                                    # decimalLiteral
    | STRING_LITERAL                                     # stringLiteral
    | '(' expression ')'                                 # parenExpr
    ;

// === Lexer Rules ===

SELECT  : S E L E C T;
FROM    : F R O M;
WHERE   : W H E R E;
GROUP   : G R O U P;
BY      : B Y;
ORDER   : O R D E R;
LIMIT   : L I M I T;
AS      : A S;
JOIN    : J O I N;
ON      : O N;
AND     : A N D;
OR      : O R;
NOT     : N O T;
ASC     : A S C;
DESC    : D E S C;

STAR    : '*';
SLASH   : '/';
PLUS    : '+';
MINUS   : '-';
EQ      : '=';
NEQ     : '!=' | '<>';
LT      : '<';
LTE     : '<=';
GT      : '>';
GTE     : '>=';

INTEGER_LITERAL  : [0-9]+;
DECIMAL_LITERAL  : [0-9]+ '.' [0-9]+;
STRING_LITERAL   : '\'' (~'\'')* '\'';
IDENTIFIER       : [a-zA-Z_][a-zA-Z_0-9]*;

WS      : [ \t\r\n]+ -> skip;

// Case-insensitive keyword fragments
fragment A : [aA]; fragment B : [bB]; fragment C : [cC]; fragment D : [dD];
fragment E : [eE]; fragment F : [fF]; fragment G : [gG]; fragment H : [hH];
fragment I : [iI]; fragment J : [jJ]; fragment K : [kK]; fragment L : [lL];
fragment M : [mM]; fragment N : [nN]; fragment O : [oO]; fragment P : [pP];
fragment Q : [qQ]; fragment R : [rR]; fragment S : [sS]; fragment T : [tT];
fragment U : [uU]; fragment V : [vV]; fragment W : [wW]; fragment X : [xX];
fragment Y : [yY]; fragment Z : [zZ];
```

**Concepts:**
- **Parser rules** (lowercase) define structure: `query`, `expression`, etc.
- **Lexer rules** (UPPERCASE) define tokens: `SELECT`, `IDENTIFIER`, etc.
- **Alternatives** (labeled with `#`) generate separate visitor methods — one per alternative.
- **Fragment rules** are helpers for case-insensitive keywords.

---

## Step 2: Generate the Lexer/Parser

Run:
```bash
./gradlew :parser:generateGrammarSource
```

This produces (in `build/generated-src/antlr/main/com/vksql/parser/generated/`):
- `VkSqlLexer.java` — tokenizer
- `VkSqlParser.java` — parser with context classes
- `VkSqlVisitor.java` — visitor interface
- `VkSqlBaseVisitor.java` — default visitor (returns null for everything)

**Don't edit generated files.** They're regenerated on every build.

**Quick sanity check — parsing a string:**
```java
var lexer = new VkSqlLexer(CharStreams.fromString("SELECT x FROM t"));
var tokens = new CommonTokenStream(lexer);
var parser = new VkSqlParser(tokens);
var tree = parser.statement();
System.out.println(tree.toStringTree(parser));
```

This prints the LISP-style parse tree. If it works, your grammar is correct.

---

## Step 3: Define Your AST Nodes

The ANTLR parse tree is noisy — full of parentheses, commas, keywords. You want a **clean AST** that represents the SQL semantically.

**Package:** `com.vksql.parser.ast`

**File:** `SqlNode.java` — sealed interface, the root of all AST nodes:
```java
public sealed interface SqlNode permits SelectStatement, SelectItem, TableRef, 
    Expression, OrderByItem {}
```

**File:** `Expression.java` — sealed interface for expressions:
```java
public sealed interface Expression extends SqlNode permits 
    ColumnRef, QualifiedColumnRef, Literal, BinaryExpr, UnaryExpr, FunctionCall {}

public record ColumnRef(String name) implements Expression {}
public record QualifiedColumnRef(String table, String column) implements Expression {}
public record Literal(Object value, LiteralType type) implements Expression {}
public record BinaryExpr(Expression left, BinaryOp op, Expression right) implements Expression {}
public record UnaryExpr(UnaryOp op, Expression operand) implements Expression {}
public record FunctionCall(String name, List<Expression> args) implements Expression {}
```

**File:** `SelectStatement.java`:
```java
public record SelectStatement(
    List<SelectItem> selectItems,
    TableRef from,
    Expression where,         // nullable
    List<Expression> groupBy, // nullable or empty
    List<OrderByItem> orderBy,// nullable or empty
    Integer limit             // nullable
) implements SqlNode {}
```

**File:** `SelectItem.java`:
```java
public record SelectItem(Expression expression, String alias) implements SqlNode {}
```

**File:** `TableRef.java`:
```java
public sealed interface TableRef extends SqlNode permits SimpleTableRef, JoinTableRef {}
public record SimpleTableRef(String tableName, String alias) implements TableRef {}
public record JoinTableRef(TableRef left, TableRef right, Expression condition) implements TableRef {}
```

**File:** `OrderByItem.java`:
```java
public record OrderByItem(Expression expression, boolean descending) implements SqlNode {}
```

**Enums:**
```java
public enum BinaryOp { ADD, SUB, MUL, DIV, EQ, NEQ, LT, LTE, GT, GTE, AND, OR }
public enum UnaryOp { NOT, NEGATE }
public enum LiteralType { INTEGER, DECIMAL, STRING }
```

---

## Step 4: Walk the Parse Tree to Build AST

**File:** `com.vksql.parser.AstBuilder.java`

Extend `VkSqlBaseVisitor<SqlNode>` and override each visitor method to build AST nodes:

```java
public class AstBuilder extends VkSqlBaseVisitor<SqlNode> {

    @Override
    public SqlNode visitQuery(VkSqlParser.QueryContext ctx) {
        List<SelectItem> selectItems = ctx.selectList().selectItem().stream()
            .map(si -> (SelectItem) visit(si))
            .toList();
        
        TableRef from = (TableRef) visit(ctx.tableRef());
        
        Expression where = ctx.whereExpr() != null 
            ? (Expression) visit(ctx.whereExpr().expression()) 
            : null;
        
        List<Expression> groupBy = ctx.groupByList() != null
            ? ctx.groupByList().expression().stream()
                .map(e -> (Expression) visit(e))
                .toList()
            : List.of();
        
        List<OrderByItem> orderBy = ctx.orderByList() != null
            ? ctx.orderByList().orderByItem().stream()
                .map(o -> (OrderByItem) visit(o))
                .toList()
            : List.of();
        
        Integer limit = ctx.limitVal != null
            ? Integer.parseInt(ctx.limitVal.getText())
            : null;
        
        return new SelectStatement(selectItems, from, where, groupBy, orderBy, limit);
    }

    @Override
    public SqlNode visitComparison(VkSqlParser.ComparisonContext ctx) {
        Expression left = (Expression) visit(ctx.expression(0));
        Expression right = (Expression) visit(ctx.expression(1));
        BinaryOp op = switch (ctx.op.getType()) {
            case VkSqlParser.EQ -> BinaryOp.EQ;
            case VkSqlParser.LTE -> BinaryOp.LTE;
            case VkSqlParser.LT -> BinaryOp.LT;
            case VkSqlParser.GT -> BinaryOp.GT;
            case VkSqlParser.GTE -> BinaryOp.GTE;
            case VkSqlParser.NEQ -> BinaryOp.NEQ;
            default -> throw new IllegalStateException("Unknown op: " + ctx.op.getText());
        };
        return new BinaryExpr(left, op, right);
    }

    @Override
    public SqlNode visitFunctionCall(VkSqlParser.FunctionCallContext ctx) {
        String name = ctx.functionName.getText().toLowerCase();
        List<Expression> args = ctx.expression().stream()
            .map(e -> (Expression) visit(e))
            .toList();
        return new FunctionCall(name, args);
    }

    @Override
    public SqlNode visitColumnRef(VkSqlParser.ColumnRefContext ctx) {
        return new ColumnRef(ctx.IDENTIFIER().getText());
    }

    @Override
    public SqlNode visitStringLiteral(VkSqlParser.StringLiteralContext ctx) {
        String raw = ctx.STRING_LITERAL().getText();
        String value = raw.substring(1, raw.length() - 1); // strip quotes
        return new Literal(value, LiteralType.STRING);
    }

    // ... implement visitIntLiteral, visitDecimalLiteral, visitMulDiv, visitAddSub,
    //     visitAndExpr, visitOrExpr, visitNotExpr, visitSelectItem, visitSimpleTable,
    //     visitJoinTable, visitOrderByItem, visitQualifiedColumn, visitCountStar, visitParenExpr
}
```

**Key concept:** Each `visit*` method returns a `SqlNode`. You cast it to the specific type you expect. If ANTLR gives you a context for an alternative (like `#comparison`), you get a dedicated visitor method for it.

---

## Step 5: The SqlParser Facade

**File:** `com.vksql.parser.SqlParser.java`

A simple entry point that hides the ANTLR machinery:

```java
public class SqlParser {
    
    public SelectStatement parse(String sql) {
        var lexer = new VkSqlLexer(CharStreams.fromString(sql));
        var tokens = new CommonTokenStream(lexer);
        var parser = new VkSqlParser(tokens);
        
        // Fail fast on syntax errors
        parser.removeErrorListeners();
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?,?> r, Object symbol, int line, int col, 
                                    String msg, RecognitionException e) {
                throw new ParseException("Syntax error at line " + line + ":" + col + " — " + msg);
            }
        });
        
        var tree = parser.statement();
        var builder = new AstBuilder();
        return (SelectStatement) builder.visit(tree);
    }
}
```

Now: `new SqlParser().parse("SELECT x FROM t")` → clean `SelectStatement` object.

---

## Step 6: Define the RelNode Hierarchy (Logical Plan)

**Package:** `com.vksql.parser.plan`

These are your relational algebra nodes. Each node has zero or more children (inputs) and describes one logical operation.

**File:** `RelNode.java` — sealed interface:
```java
public sealed interface RelNode permits 
    ScanNode, FilterNode, ProjectNode, JoinNode, AggregateNode, SortNode, LimitNode {}
```

**File:** `ScanNode.java`:
```java
public record ScanNode(String tableName) implements RelNode {}
```
The leaf node. Reads all rows from a table.

**File:** `FilterNode.java`:
```java
public record FilterNode(RelNode input, Expression condition) implements RelNode {}
```
Applies a predicate. Passes through rows where `condition` is true.

**File:** `ProjectNode.java`:
```java
public record ProjectNode(RelNode input, List<SelectItem> projections) implements RelNode {}
```
Selects/computes output columns.

**File:** `JoinNode.java`:
```java
public record JoinNode(RelNode left, RelNode right, Expression condition, JoinType joinType) implements RelNode {}

public enum JoinType { INNER, LEFT, RIGHT, FULL }
```
Combines rows from two inputs.

**File:** `AggregateNode.java`:
```java
public record AggregateNode(
    RelNode input,
    List<Expression> groupByKeys,
    List<AggCall> aggregations
) implements RelNode {}

public record AggCall(String functionName, Expression argument, String alias) {}
```
Groups rows and computes aggregates.

**File:** `SortNode.java`:
```java
public record SortNode(RelNode input, List<OrderByItem> sortKeys) implements RelNode {}
```
Orders rows by given keys.

**File:** `LimitNode.java`:
```java
public record LimitNode(RelNode input, int limit) implements RelNode {}
```
Truncates output to N rows.

---

## Step 7: Implement SqlToRelConverter

**File:** `com.vksql.parser.plan.SqlToRelConverter.java`

This converts the AST (`SelectStatement`) into a tree of `RelNode` objects. The conversion follows a fixed order — bottom-up:

```
SQL clause:   FROM → WHERE → GROUP BY → SELECT → ORDER BY → LIMIT
Rel tree:     Scan → Filter → Aggregate → Project → Sort → Limit
```

This ordering matches relational algebra's evaluation semantics.

```java
public class SqlToRelConverter {
    
    public RelNode convert(SelectStatement stmt) {
        // 1. Start with the FROM clause → ScanNode (or JoinNode)
        RelNode node = convertTableRef(stmt.from());
        
        // 2. WHERE → FilterNode
        if (stmt.where() != null) {
            node = new FilterNode(node, stmt.where());
        }
        
        // 3. GROUP BY → AggregateNode
        if (!stmt.groupBy().isEmpty()) {
            node = convertAggregate(node, stmt);
        }
        
        // 4. SELECT → ProjectNode
        node = new ProjectNode(node, stmt.selectItems());
        
        // 5. ORDER BY → SortNode
        if (!stmt.orderBy().isEmpty()) {
            node = new SortNode(node, stmt.orderBy());
        }
        
        // 6. LIMIT → LimitNode
        if (stmt.limit() != null) {
            node = new LimitNode(node, stmt.limit());
        }
        
        return node;
    }
    
    private RelNode convertTableRef(TableRef ref) {
        return switch (ref) {
            case SimpleTableRef s -> new ScanNode(s.tableName());
            case JoinTableRef j -> new JoinNode(
                convertTableRef(j.left()),
                convertTableRef(j.right()),
                j.condition(),
                JoinType.INNER
            );
        };
    }
    
    private RelNode convertAggregate(RelNode input, SelectStatement stmt) {
        // Extract aggregate function calls from SELECT items
        List<AggCall> aggCalls = new ArrayList<>();
        for (SelectItem item : stmt.selectItems()) {
            if (item.expression() instanceof FunctionCall fc) {
                Expression arg = fc.args().isEmpty() ? null : fc.args().get(0);
                aggCalls.add(new AggCall(fc.name(), arg, item.alias()));
            }
        }
        return new AggregateNode(input, stmt.groupBy(), aggCalls);
    }
}
```

**Key decision:** The aggregate extraction is simplified here — it only looks at top-level function calls in SELECT. A production engine would recursively walk expressions to find nested aggregates. Good enough for TPC-H Q1.

---

## Step 8: Pretty-Print the Logical Plan

**File:** `com.vksql.parser.plan.PlanPrinter.java`

A utility that prints the plan tree with indentation:

```java
public class PlanPrinter {
    
    public static String print(RelNode node) {
        var sb = new StringBuilder();
        print(node, sb, 0);
        return sb.toString();
    }
    
    private static void print(RelNode node, StringBuilder sb, int indent) {
        sb.append("  ".repeat(indent));
        
        switch (node) {
            case ScanNode s -> sb.append("Scan [").append(s.tableName()).append("]\n");
            
            case FilterNode f -> {
                sb.append("Filter [").append(exprToString(f.condition())).append("]\n");
                print(f.input(), sb, indent + 1);
            }
            
            case ProjectNode p -> {
                sb.append("Project [");
                sb.append(p.projections().stream()
                    .map(si -> exprToString(si.expression()) + 
                         (si.alias() != null ? " AS " + si.alias() : ""))
                    .collect(Collectors.joining(", ")));
                sb.append("]\n");
                print(p.input(), sb, indent + 1);
            }
            
            case AggregateNode a -> {
                sb.append("Aggregate [groupBy=");
                sb.append(a.groupByKeys().stream().map(PlanPrinter::exprToString)
                    .collect(Collectors.joining(", ")));
                sb.append(", aggs=");
                sb.append(a.aggregations().stream()
                    .map(ac -> ac.functionName() + "(" + exprToString(ac.argument()) + ")")
                    .collect(Collectors.joining(", ")));
                sb.append("]\n");
                print(a.input(), sb, indent + 1);
            }
            
            case SortNode s -> {
                sb.append("Sort [");
                sb.append(s.sortKeys().stream()
                    .map(o -> exprToString(o.expression()) + (o.descending() ? " DESC" : " ASC"))
                    .collect(Collectors.joining(", ")));
                sb.append("]\n");
                print(s.input(), sb, indent + 1);
            }
            
            case LimitNode l -> {
                sb.append("Limit [").append(l.limit()).append("]\n");
                print(l.input(), sb, indent + 1);
            }
            
            case JoinNode j -> {
                sb.append("Join [").append(j.joinType()).append(", on=")
                    .append(exprToString(j.condition())).append("]\n");
                print(j.left(), sb, indent + 1);
                print(j.right(), sb, indent + 1);
            }
        }
    }
    
    private static String exprToString(Expression expr) {
        if (expr == null) return "*";
        return switch (expr) {
            case ColumnRef c -> c.name();
            case QualifiedColumnRef q -> q.table() + "." + q.column();
            case Literal l -> l.value().toString();
            case BinaryExpr b -> exprToString(b.left()) + " " + b.op() + " " + exprToString(b.right());
            case UnaryExpr u -> u.op() + "(" + exprToString(u.operand()) + ")";
            case FunctionCall f -> f.name() + "(" + 
                f.args().stream().map(PlanPrinter::exprToString).collect(Collectors.joining(", ")) + ")";
        };
    }
}
```

Usage:
```java
var plan = new SqlToRelConverter().convert(ast);
System.out.println(PlanPrinter.print(plan));
```

---

## Step 9: Test with TPC-H Q1

**File:** `parser/src/test/java/com/vksql/parser/TpchQ1Test.java`

TPC-H Q1 (simplified — same semantics, just easier to parse without date functions):

```java
@Test
void parseTpchQ1() {
    String sql = """
        SELECT
            l_returnflag,
            l_linestatus,
            sum(l_quantity) AS sum_qty,
            sum(l_extendedprice) AS sum_base_price,
            sum(l_extendedprice * (1 - l_discount)) AS sum_disc_price,
            count(*) AS count_order
        FROM
            lineitem
        WHERE
            l_shipdate <= '1998-09-02'
        GROUP BY
            l_returnflag, l_linestatus
        ORDER BY
            l_returnflag, l_linestatus
        """;
    
    var parser = new SqlParser();
    SelectStatement ast = parser.parse(sql);
    
    // Verify AST structure
    assertNotNull(ast);
    assertEquals(6, ast.selectItems().size());
    assertEquals("lineitem", ((SimpleTableRef) ast.from()).tableName());
    assertNotNull(ast.where());
    assertEquals(2, ast.groupBy().size());
    assertEquals(2, ast.orderBy().size());
    assertNull(ast.limit());
    
    // Convert to logical plan
    var converter = new SqlToRelConverter();
    RelNode plan = converter.convert(ast);
    
    // Verify plan structure (outermost to innermost)
    assertInstanceOf(SortNode.class, plan);
    var sort = (SortNode) plan;
    
    assertInstanceOf(ProjectNode.class, sort.input());
    var project = (ProjectNode) sort.input();
    
    assertInstanceOf(AggregateNode.class, project.input());
    var agg = (AggregateNode) project.input();
    assertEquals(2, agg.groupByKeys().size());
    
    assertInstanceOf(FilterNode.class, agg.input());
    var filter = (FilterNode) agg.input();
    
    assertInstanceOf(ScanNode.class, filter.input());
    var scan = (ScanNode) filter.input();
    assertEquals("lineitem", scan.tableName());
    
    // Pretty-print for visual verification
    System.out.println(PlanPrinter.print(plan));
}
```

Expected output:
```
Sort [l_returnflag ASC, l_linestatus ASC]
  Project [l_returnflag, l_linestatus, sum(l_quantity) AS sum_qty, ...]
    Aggregate [groupBy=l_returnflag, l_linestatus, aggs=sum(l_quantity), sum(l_extendedprice), ...]
      Filter [l_shipdate LTE 1998-09-02]
        Scan [lineitem]
```

---

## Order of Implementation

1. Set up `parser` module with ANTLR plugin in `build.gradle.kts`
2. Write the ANTLR grammar `VkSql.g4`
3. Run `./gradlew :parser:generateGrammarSource` — verify it compiles
4. Define AST node types (records + sealed interfaces)
5. Implement `AstBuilder` — walk parse tree, produce AST
6. Write `SqlParser` facade — wire ANTLR machinery + error handling
7. **Test checkpoint:** parse `SELECT x FROM t` → verify AST
8. Define `RelNode` hierarchy (7 node types)
9. Implement `SqlToRelConverter` — AST → logical plan
10. Implement `PlanPrinter` — visual verification
11. **Final test:** TPC-H Q1 end-to-end

---

## Concepts You'll Learn

| Concept | Where You'll Hit It |
|---------|-------------------|
| ANTLR4 grammars | Defining lexer/parser rules, handling precedence |
| Visitor pattern | Walking the parse tree without modifying generated code |
| Sealed interfaces | Type-safe node hierarchies with exhaustive pattern matching |
| Relational algebra | Scan, Filter, Project, Join, Aggregate, Sort, Limit |
| Tree transformation | AST → RelNode tree (one IR to another) |
| Operator ordering | SQL clauses don't execute in written order (FROM first, not SELECT) |

---

## Common Mistakes

1. **Operator precedence in the grammar.** ANTLR resolves ambiguity by rule order — alternatives listed first have higher precedence. Put `mulDiv` before `addSub`, and `comparison` before `andExpr`. If you get `1 + 2 * 3 = 7` instead of `9`, your precedence is wrong.

2. **Forgetting `-visitor` flag.** Without it, ANTLR only generates Listener classes (callback-based). You want Visitor (return values). Double-check your `build.gradle.kts` arguments.

3. **Casting `visit()` results.** The base visitor returns `Object` (or your generic type). You MUST cast: `(Expression) visit(ctx.expression())`. If you forget, you get `SqlNode` where you need `Expression` and the compiler yells.

4. **Null children in the parse tree.** Optional clauses (WHERE, GROUP BY, LIMIT) may be null. Always null-check before visiting: `ctx.whereExpr() != null`.

5. **String literal quotes.** ANTLR gives you the full token text including quotes: `'1998-09-02'`. You need to strip them: `text.substring(1, text.length() - 1)`.

6. **Aggregate detection.** A naive converter might put every SELECT item in the ProjectNode. But `sum(l_quantity)` is an aggregate — it belongs in the AggregateNode. You need to separate aggregate function calls from plain column references.

7. **SQL evaluation order ≠ written order.** The query writes `SELECT ... FROM ... WHERE ...` but evaluation is `FROM → WHERE → GROUP BY → HAVING → SELECT → ORDER BY → LIMIT`. Your converter must build the tree bottom-up in evaluation order.

8. **Generated source not on classpath.** If your IDE can't find `VkSqlParser`, make sure `build/generated-src/antlr/main` is registered as a source directory. The `sourceSets` block in build.gradle.kts handles this.

---

## Debugging Tips

**Grammar won't compile:**
```bash
./gradlew :parser:generateGrammarSource --info
```
Look for ANTLR error messages — usually conflicting rules or missing semicolons.

**Parse tree looks wrong:**
```java
System.out.println(tree.toStringTree(parser));
```
This dumps the raw LISP tree. Compare with what you expected.

**Visitor returns null:**
You forgot to override a visitor method. `VkSqlBaseVisitor` returns null by default. If you see NPE, check which alternative isn't handled.

**ANTLR ambiguity warning:**
```
warning: rule 'expression' contains a closure with at least one alternative that can match an empty string
```
Usually means you have an optional piece inside a repetition. Check your grammar for `(expression)?` inside a `(... )*`.

---

## When You're Done

- ✅ `./gradlew :parser:generateGrammarSource` succeeds
- ✅ Can parse `SELECT x FROM t WHERE y > 5 GROUP BY x ORDER BY x LIMIT 10`
- ✅ AST correctly represents all SQL clauses as typed records
- ✅ `SqlToRelConverter` produces: Limit → Sort → Project → Aggregate → Filter → Scan
- ✅ `PlanPrinter` output is human-readable
- ✅ TPC-H Q1 parses and converts end-to-end
- ✅ All tests pass: `./gradlew :parser:test`

**Next week:** Logical plan optimizer — predicate pushdown, projection pruning, join reordering.
