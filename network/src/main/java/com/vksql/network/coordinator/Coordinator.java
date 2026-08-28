package com.vksql.network.coordinator;

import com.vksql.network.worker.Worker;
import com.vksql.storage.format.Schema;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinator: the "brain" of the distributed query engine.
 *
 * Responsibilities:
 * 1. Receive SQL from client
 * 2. Parse + plan the query
 * 3. Split into tasks (one per worker per stage)
 * 4. Assign tasks to workers
 * 5. Collect results
 * 6. Return final result to client
 */
public class Coordinator {

    private final WorkerRegistry workerRegistry;
    private final ConcurrentHashMap<String, QueryState> activeQueries = new ConcurrentHashMap<>();
    private final int port;

    public Coordinator(int port) {
        this.port = port;
        this.workerRegistry = new WorkerRegistry();
    }

    // TODO: Start gRPC server, implement ExecuteQuery RPC
    public void start() {
        // Server server = ServerBuilder.forPort(port)
        //     .addService(new CoordinatorServiceImpl(this))
        //     .build()
        //     .start();
    }

    public WorkerRegistry getWorkerRegistry() {
        return workerRegistry;
    }

    /**
     * Plan a distributed query into task assignments, one per alive worker.
     *
     * Parses a simplified SQL:
     *   SELECT aggFunc(aggCol) FROM table WHERE filterCol op value GROUP BY groupCol
     *
     * Uses the provided schema to resolve column names to indices.
     *
     * @param sql    the query string
     * @param schema the table schema (for column name → index resolution)
     * @return list of task assignments, one per alive worker
     */
    public List<TaskAssignment> planDistributed(String sql, Schema schema) {
        // Parse the SQL into components
        ParsedQuery parsed = parseQuery(sql);

        // Resolve column names to indices
        int groupByColIndex = resolveColumnIndex(schema, parsed.groupByCol);
        int aggColIndex = resolveColumnIndex(schema, parsed.aggCol);

        // Get all alive workers
        List<WorkerRegistry.WorkerInfo> workers = workerRegistry.getAliveWorkers();
        if (workers.isEmpty()) {
            throw new IllegalStateException("No alive workers available to execute query");
        }

        // Create one task per worker — each will scan its own local partition
        return workers.stream()
                .map(w -> new TaskAssignment(
                        w.id,
                        parsed.tableName,
                        parsed.filterColumn,
                        parsed.filterOp,
                        parsed.filterValue,
                        groupByColIndex,
                        aggColIndex,
                        parsed.aggFunction
                ))
                .toList();
    }

    /**
     * Execute a distributed query in-process (no network).
     *
     * Calls each worker's executeTask() directly, collects partial results,
     * and merges them into a final result by summing values for matching group keys.
     *
     * @param tasks   the task assignments from planDistributed()
     * @param workers map of workerId → Worker instance (in-process simulation)
     * @return merged result: groupKey → aggregated value
     */
    public Map<Object, Long> executeDistributed(List<TaskAssignment> tasks, Map<String, Worker> workers) {
        Map<Object, Long> mergedResults = new HashMap<>();

        for (TaskAssignment task : tasks) {
            Worker worker = workers.get(task.workerId());
            if (worker == null) {
                throw new IllegalStateException("Worker not found: " + task.workerId());
            }

            // Execute the task on this worker (in-process, no network)
            Map<Object, Long> partialResult = worker.executeTask(
                    task.tableName(),
                    task.filterColumn(),
                    task.filterOp(),
                    task.filterValue(),
                    task.groupByColIndex(),
                    task.aggColIndex(),
                    task.aggFunction()
            );

            // Merge partial results — sum values for each group key
            for (Map.Entry<Object, Long> entry : partialResult.entrySet()) {
                mergedResults.merge(entry.getKey(), entry.getValue(), Long::sum);
            }
        }

        return mergedResults;
    }

    /**
     * Convenience method: plan + execute in one call.
     */
    public Map<Object, Long> executeQuery(String sql, Schema schema, Map<String, Worker> workers) {
        List<TaskAssignment> tasks = planDistributed(sql, schema);
        return executeDistributed(tasks, workers);
    }

    /**
     * Resolve a column name to its index in the schema.
     */
    private int resolveColumnIndex(Schema schema, String columnName) {
        for (int i = 0; i < schema.columnCount(); i++) {
            if (schema.column(i).name().equalsIgnoreCase(columnName)) {
                return i;
            }
        }
        throw new IllegalArgumentException("Column not found in schema: " + columnName);
    }

    /**
     * Parse a simplified SQL query.
     *
     * Expected format:
     *   SELECT sum(price) FROM orders WHERE price > 250 GROUP BY nation
     *
     * This is a basic parser for proof-of-concept. A full SQL parser will replace it later.
     */
    ParsedQuery parseQuery(String sql) {
        String normalized = sql.trim().toLowerCase();

        // Extract aggregate function and column: "sum(price)"
        int selectIdx = normalized.indexOf("select") + 6;
        int fromIdx = normalized.indexOf("from");
        if (selectIdx < 6 || fromIdx < 0) {
            throw new IllegalArgumentException("Invalid SQL: missing SELECT or FROM: " + sql);
        }
        String selectClause = sql.substring(selectIdx, fromIdx).trim();

        String aggFunction;
        String aggCol;
        int parenOpen = selectClause.indexOf('(');
        int parenClose = selectClause.indexOf(')');
        if (parenOpen >= 0 && parenClose > parenOpen) {
            aggFunction = selectClause.substring(0, parenOpen).trim().toLowerCase();
            aggCol = selectClause.substring(parenOpen + 1, parenClose).trim().toLowerCase();
        } else {
            throw new IllegalArgumentException("Cannot parse aggregate from SELECT clause: " + selectClause);
        }

        // Extract table name: "FROM orders WHERE ..."
        String afterFrom = sql.substring(fromIdx + 4).trim();
        String tableName;
        int whereIdx = afterFrom.toLowerCase().indexOf("where");
        if (whereIdx >= 0) {
            tableName = afterFrom.substring(0, whereIdx).trim().toLowerCase();
        } else {
            int groupIdx = afterFrom.toLowerCase().indexOf("group");
            tableName = (groupIdx >= 0 ? afterFrom.substring(0, groupIdx) : afterFrom).trim().toLowerCase();
        }

        // Extract WHERE clause: "price > 250"
        String filterColumn = null;
        String filterOp = null;
        long filterValue = 0;
        if (whereIdx >= 0) {
            String afterWhere = afterFrom.substring(whereIdx + 5).trim();
            int groupByInWhere = afterWhere.toLowerCase().indexOf("group");
            String whereClause = groupByInWhere >= 0
                    ? afterWhere.substring(0, groupByInWhere).trim()
                    : afterWhere.trim();

            // Parse "column op value" — try multi-char operators first
            String[] operators = {">=", "<=", "!=", ">", "<", "="};
            for (String op : operators) {
                int opIdx = whereClause.indexOf(op);
                if (opIdx >= 0) {
                    filterColumn = whereClause.substring(0, opIdx).trim().toLowerCase();
                    filterOp = op;
                    filterValue = Long.parseLong(whereClause.substring(opIdx + op.length()).trim());
                    break;
                }
            }
            if (filterColumn == null) {
                throw new IllegalArgumentException("Cannot parse WHERE clause: " + whereClause);
            }
        }

        // Extract GROUP BY column: "GROUP BY nation"
        String groupByCol = null;
        int groupByIdx = normalized.indexOf("group by");
        if (groupByIdx >= 0) {
            groupByCol = sql.substring(groupByIdx + 8).trim().toLowerCase();
            // Remove trailing whitespace or semicolons
            groupByCol = groupByCol.replaceAll("[;\\s]+$", "");
        }

        if (groupByCol == null) {
            throw new IllegalArgumentException("Missing GROUP BY clause in: " + sql);
        }

        return new ParsedQuery(tableName, filterColumn, filterOp, filterValue, groupByCol, aggCol, aggFunction);
    }

    /**
     * Internal representation of a parsed query.
     */
    record ParsedQuery(
            String tableName,
            String filterColumn,
            String filterOp,
            long filterValue,
            String groupByCol,
            String aggCol,
            String aggFunction
    ) {}
}
