package com.vksql.parser.plan;

/**
 * Base interface for all plan nodes.
 * Every node in the query plan implements this.
 */
public sealed interface RelNode permits ScanNode, FilterNode, ProjectNode, AggregateNode, SortNode, LimitNode, JoinNode {
}
