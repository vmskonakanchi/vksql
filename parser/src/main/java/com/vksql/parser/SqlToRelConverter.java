package com.vksql.parser;

import com.vksql.parser.expr.*;
import com.vksql.parser.generated.VkSqlBaseVisitor;
import com.vksql.parser.generated.VkSqlParser;
import com.vksql.parser.plan.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks ANTLR's parse tree and builds our plan nodes.
 *
 * SQL evaluation order:
 * FROM → WHERE → GROUP BY → SELECT → ORDER BY → LIMIT
 *
 * So we build the plan bottom-up:
 * Scan → Filter → Aggregate → Project → Sort → Limit
 */
public class SqlToRelConverter extends VkSqlBaseVisitor<Object> {

    /**
     * Entry point: converts a parsed query into a RelNode plan tree.
     */
    public RelNode convert(VkSqlParser.QueryContext ctx) {
        return convertSelect(ctx.selectStatement());
    }

    private RelNode convertSelect(VkSqlParser.SelectStatementContext ctx) {
        // 1. FROM → Scan or Join
        RelNode plan = (RelNode) visit(ctx.tableRef());

        // 2. WHERE → Filter
        if (ctx.whereExpr != null) {
            Expr condition = (Expr) visit(ctx.whereExpr);
            plan = new FilterNode(condition, plan);
        }

        // 3. GROUP BY → Aggregate
        if (ctx.groupByList() != null) {
            List<Expr> groupBy = new ArrayList<>();
            for (var exprCtx : ctx.groupByList().expr()) {
                groupBy.add((Expr) visit(exprCtx));
            }
            // Aggregates come from the SELECT list (functions like sum, count)
            List<Expr> aggregates = extractAggregates(ctx.selectList());
            plan = new AggregateNode(groupBy, aggregates, plan);
        }

        // 4. SELECT → Project
        List<Expr> columns = convertSelectList(ctx.selectList());
        plan = new ProjectNode(columns, plan);

        // 5. ORDER BY → Sort
        if (ctx.orderByList() != null) {
            List<SortNode.SortKey> keys = new ArrayList<>();
            for (var item : ctx.orderByList().orderByItem()) {
                Expr expr = (Expr) visit(item.expr());
                boolean asc = item.DESC() == null; // default is ascending
                keys.add(new SortNode.SortKey(expr, asc));
            }
            plan = new SortNode(keys, plan);
        }

        // 6. LIMIT → Limit
        if (ctx.limitValue != null) {
            plan = new LimitNode(Integer.parseInt(ctx.limitValue.getText()), plan);
        }

        return plan;
    }

    // ===== TABLE REFERENCES =====

    @Override
    public Object visitSimpleTable(VkSqlParser.SimpleTableContext ctx) {
        return new ScanNode(ctx.tableName.getText());
    }

    @Override
    public Object visitJoinTable(VkSqlParser.JoinTableContext ctx) {
        RelNode left = (RelNode) visit(ctx.left);
        RelNode right = (RelNode) visit(ctx.right);
        Expr condition = (Expr) visit(ctx.onExpr);
        return new JoinNode(left, right, condition);
    }

    // ===== EXPRESSIONS =====

    @Override
    public Object visitColumnRef(VkSqlParser.ColumnRefContext ctx) {
        return new ColumnRef(ctx.IDENTIFIER().getText());
    }

    @Override
    public Object visitIntLiteral(VkSqlParser.IntLiteralContext ctx) {
        return new IntLiteral(Long.parseLong(ctx.INTEGER().getText()));
    }

    @Override
    public Object visitDecimalLiteral(VkSqlParser.DecimalLiteralContext ctx) {
        return new DecimalLiteral(Double.parseDouble(ctx.DECIMAL().getText()));
    }

    @Override
    public Object visitStringLiteral(VkSqlParser.StringLiteralContext ctx) {
        String text = ctx.STRING_LITERAL().getText();
        // Remove surrounding quotes: 'hello' → hello
        return new StringLiteral(text.substring(1, text.length() - 1));
    }

    @Override
    public Object visitComparison(VkSqlParser.ComparisonContext ctx) {
        Expr left = (Expr) visit(ctx.left);
        Expr right = (Expr) visit(ctx.right);
        String op = ctx.op.getText();
        return new ComparisonExpr(left, op, right);
    }

    @Override
    public Object visitMulDiv(VkSqlParser.MulDivContext ctx) {
        Expr left = (Expr) visit(ctx.left);
        Expr right = (Expr) visit(ctx.right);
        return new ArithmeticExpr(left, ctx.op.getText(), right);
    }

    @Override
    public Object visitAddSub(VkSqlParser.AddSubContext ctx) {
        Expr left = (Expr) visit(ctx.left);
        Expr right = (Expr) visit(ctx.right);
        return new ArithmeticExpr(left, ctx.op.getText(), right);
    }

    @Override
    public Object visitAndExpr(VkSqlParser.AndExprContext ctx) {
        Expr left = (Expr) visit(ctx.left);
        Expr right = (Expr) visit(ctx.right);
        return new AndExpr(left, right);
    }

    @Override
    public Object visitOrExpr(VkSqlParser.OrExprContext ctx) {
        Expr left = (Expr) visit(ctx.left);
        Expr right = (Expr) visit(ctx.right);
        return new OrExpr(left, right);
    }

    @Override
    public Object visitNotExpr(VkSqlParser.NotExprContext ctx) {
        Expr input = (Expr) visit(ctx.expr());
        return new NotExpr(input);
    }

    @Override
    public Object visitFunctionCall(VkSqlParser.FunctionCallContext ctx) {
        String name = ctx.functionName.getText();
        List<Expr> args = new ArrayList<>();
        for (var exprCtx : ctx.expr()) {
            args.add((Expr) visit(exprCtx));
        }
        return new FunctionCall(name, args);
    }

    @Override
    public Object visitParenExpr(VkSqlParser.ParenExprContext ctx) {
        return visit(ctx.expr());
    }

    // ===== HELPERS =====

    private List<Expr> convertSelectList(VkSqlParser.SelectListContext ctx) {
        List<Expr> columns = new ArrayList<>();
        for (var item : ctx.selectItem()) {
            if (item instanceof VkSqlParser.SelectAllContext) {
                columns.add(new ColumnRef("*"));
            } else if (item instanceof VkSqlParser.SelectExprContext exprItem) {
                columns.add((Expr) visit(exprItem.expr()));
            }
        }
        return columns;
    }

    private List<Expr> extractAggregates(VkSqlParser.SelectListContext ctx) {
        List<Expr> aggregates = new ArrayList<>();
        for (var item : ctx.selectItem()) {
            if (item instanceof VkSqlParser.SelectExprContext exprItem) {
                Expr expr = (Expr) visit(exprItem.expr());
                if (expr instanceof FunctionCall) {
                    aggregates.add(expr);
                }
            }
        }
        return aggregates;
    }
}
