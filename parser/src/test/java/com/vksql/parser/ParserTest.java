package com.vksql.parser;

import com.vksql.parser.expr.*;
import com.vksql.parser.generated.*;
import com.vksql.parser.plan.*;
import org.antlr.v4.runtime.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ParserTest {

    private RelNode parse(String sql) {
        VkSqlLexer lexer = new VkSqlLexer(CharStreams.fromString(sql));
        VkSqlParser parser = new VkSqlParser(new CommonTokenStream(lexer));
        VkSqlParser.QueryContext tree = parser.query();
        assertEquals(0, parser.getNumberOfSyntaxErrors(), "SQL should parse without errors: " + sql);
        return new SqlToRelConverter().convert(tree);
    }

    @Test
    void simpleSelect() {
        RelNode plan = parse("SELECT price FROM orders");

        // Should be: Project([price], Scan(orders))
        assertInstanceOf(ProjectNode.class, plan);
        ProjectNode project = (ProjectNode) plan;
        assertEquals(1, project.columns().size());
        assertInstanceOf(ColumnRef.class, project.columns().get(0));
        assertEquals("price", ((ColumnRef) project.columns().get(0)).name());

        assertInstanceOf(ScanNode.class, project.input());
        assertEquals("orders", ((ScanNode) project.input()).tableName());
    }

    @Test
    void selectWithWhere() {
        RelNode plan = parse("SELECT price FROM orders WHERE price > 100");

        // Should be: Project([price], Filter(price > 100, Scan(orders)))
        assertInstanceOf(ProjectNode.class, plan);
        ProjectNode project = (ProjectNode) plan;

        assertInstanceOf(FilterNode.class, project.input());
        FilterNode filter = (FilterNode) project.input();

        assertInstanceOf(ComparisonExpr.class, filter.condition());
        ComparisonExpr cmp = (ComparisonExpr) filter.condition();
        assertEquals(">", cmp.operator());
        assertEquals("price", ((ColumnRef) cmp.left()).name());
        assertEquals(100L, ((IntLiteral) cmp.right()).value());

        assertInstanceOf(ScanNode.class, filter.input());
        assertEquals("orders", ((ScanNode) filter.input()).tableName());
    }

    @Test
    void selectWithGroupBy() {
        RelNode plan = parse("SELECT nation, sum(price) FROM orders GROUP BY nation");

        // Should be: Project → Aggregate → Scan
        assertInstanceOf(ProjectNode.class, plan);
        ProjectNode project = (ProjectNode) plan;

        assertInstanceOf(AggregateNode.class, project.input());
        AggregateNode agg = (AggregateNode) project.input();
        assertEquals(1, agg.groupBy().size());
        assertEquals("nation", ((ColumnRef) agg.groupBy().get(0)).name());
        assertEquals(1, agg.aggregates().size());
        assertInstanceOf(FunctionCall.class, agg.aggregates().get(0));
        assertEquals("sum", ((FunctionCall) agg.aggregates().get(0)).name());

        assertInstanceOf(ScanNode.class, agg.input());
    }

    @Test
    void selectWithOrderByAndLimit() {
        RelNode plan = parse("SELECT price FROM orders ORDER BY price DESC LIMIT 10");

        // Should be: Limit(10, Sort([price DESC], Project([price], Scan(orders))))
        assertInstanceOf(LimitNode.class, plan);
        LimitNode limit = (LimitNode) plan;
        assertEquals(10, limit.limit());

        assertInstanceOf(SortNode.class, limit.input());
        SortNode sort = (SortNode) limit.input();
        assertEquals(1, sort.keys().size());
        assertFalse(sort.keys().get(0).ascending());

        assertInstanceOf(ProjectNode.class, sort.input());
    }

    @Test
    void selectWithJoin() {
        RelNode plan = parse("SELECT name FROM orders JOIN customers ON id = cust_id");

        assertInstanceOf(ProjectNode.class, plan);
        ProjectNode project = (ProjectNode) plan;

        assertInstanceOf(JoinNode.class, project.input());
        JoinNode join = (JoinNode) project.input();
        assertInstanceOf(ScanNode.class, join.left());
        assertInstanceOf(ScanNode.class, join.right());
        assertEquals("orders", ((ScanNode) join.left()).tableName());
        assertEquals("customers", ((ScanNode) join.right()).tableName());
    }
}
