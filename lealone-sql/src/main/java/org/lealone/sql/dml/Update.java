/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.sql.dml;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import org.lealone.common.exceptions.DbException;
import org.lealone.common.util.StatementBuilder;
import org.lealone.common.util.StringUtils;
import org.lealone.common.util.Utils;
import org.lealone.db.api.ErrorCode;
import org.lealone.db.api.Trigger;
import org.lealone.db.async.AsyncHandler;
import org.lealone.db.async.AsyncResult;
import org.lealone.db.auth.Right;
import org.lealone.db.result.Row;
import org.lealone.db.session.ServerSession;
import org.lealone.db.session.SessionStatus;
import org.lealone.db.table.Column;
import org.lealone.db.table.Table;
import org.lealone.db.value.Value;
import org.lealone.sql.PreparedSQLStatement;
import org.lealone.sql.SQLStatement;
import org.lealone.sql.executor.YieldableBase;
import org.lealone.sql.executor.YieldableLoopUpdateBase;
import org.lealone.sql.expression.Expression;
import org.lealone.sql.expression.Parameter;
import org.lealone.sql.expression.ValueExpression;
import org.lealone.sql.optimizer.PlanItem;
import org.lealone.sql.optimizer.TableFilter;

/**
 * This class represents the statement
 * UPDATE
 * 
 * @author H2 Group
 * @author zhh
 */
public class Update extends ManipulationStatement {

    private TableFilter tableFilter;
    private Expression condition;

    /** The limit expression as specified in the LIMIT clause. */
    private Expression limitExpr;

    private final ArrayList<Column> columns = Utils.newSmallArrayList();
    private final HashMap<Column, Expression> expressionMap = new HashMap<>();

    public Update(ServerSession session) {
        super(session);
    }

    @Override
    public int getType() {
        return SQLStatement.UPDATE;
    }

    @Override
    public boolean isCacheable() {
        return true;
    }

    public void setTableFilter(TableFilter tableFilter) {
        this.tableFilter = tableFilter;
    }

    @Override
    public TableFilter getTableFilter() {
        return tableFilter;
    }

    public void setCondition(Expression condition) {
        this.condition = condition;
    }

    public void setLimit(Expression limit) {
        this.limitExpr = limit;
    }

    /**
     * Add an assignment of the form column = expression.
     *
     * @param column the column
     * @param expression the expression
     */
    public void setAssignment(Column column, Expression expression) {
        if (expressionMap.containsKey(column)) {
            throw DbException.get(ErrorCode.DUPLICATE_COLUMN_NAME_1, column.getName());
        }
        columns.add(column);
        expressionMap.put(column, expression);
        if (expression instanceof Parameter) {
            Parameter p = (Parameter) expression;
            p.setColumn(column);
        }
    }

    @Override
    public int getPriority() {
        if (getCurrentRowNumber() > 0)
            return priority;

        priority = NORM_PRIORITY - 1;
        return priority;
    }

    @Override
    public String getPlanSQL() {
        StatementBuilder buff = new StatementBuilder("UPDATE ");
        buff.append(tableFilter.getPlanSQL(false)).append("\nSET\n    ");
        for (int i = 0, size = columns.size(); i < size; i++) {
            Column c = columns.get(i);
            Expression e = expressionMap.get(c);
            buff.appendExceptFirst(",\n    ");
            buff.append(c.getName()).append(" = ").append(e.getSQL());
        }
        if (condition != null) {
            buff.append("\nWHERE ").append(StringUtils.unEnclose(condition.getSQL()));
        }

        if (limitExpr != null) {
            buff.append("\nLIMIT (").append(StringUtils.unEnclose(limitExpr.getSQL())).append(')');
        }
        return buff.toString();
    }

    @Override
    public PreparedSQLStatement prepare() {
        int size = columns.size();
        HashSet<Column> columnSet = new HashSet<>(size + 1);
        if (condition != null) {
            condition.mapColumns(tableFilter, 0);
            condition = condition.optimize(session);
            condition.createIndexConditions(session, tableFilter);
            condition.getColumns(columnSet);
        }
        for (int i = 0; i < size; i++) {
            Column c = columns.get(i);
            Expression e = expressionMap.get(c);
            e.mapColumns(tableFilter, 0);
            expressionMap.put(c, e.optimize(session));

            columnSet.add(c);
            e.getColumns(columnSet); // 例如f1=f2*2;
        }
        PlanItem item = tableFilter.getBestPlanItem(session, 1);
        tableFilter.setPlanItem(item);
        tableFilter.prepare();
        tableFilter.createColumnIndexes(columnSet);
        return this;
    }

    @Override
    public int update() {
        YieldableUpdate yieldable = new YieldableUpdate(this, null);
        return syncExecute(yieldable);
    }

    @Override
    public YieldableBase<Integer> createYieldableUpdate(AsyncHandler<AsyncResult<Integer>> asyncHandler) {
        if (!isLocal() && getSession().isShardingMode())
            return super.createYieldableUpdate(asyncHandler);
        else
            return new YieldableUpdate(this, asyncHandler);
    }

    private static class YieldableUpdate extends YieldableLoopUpdateBase {

        final Update statement;
        final TableFilter tableFilter;
        final Table table;
        final int limitRows; // 如果是0，表示不更新任何记录；如果小于0，表示没有限制
        final Column[] columns;
        final int columnCount;
        boolean hasNext;
        Row oldRow;

        public YieldableUpdate(Update statement, AsyncHandler<AsyncResult<Integer>> asyncHandler) {
            super(statement, asyncHandler);
            this.statement = statement;
            tableFilter = statement.tableFilter;
            table = tableFilter.getTable();
            limitRows = getLimitRows(statement.limitExpr, session);
            columns = table.getColumns();
            columnCount = columns.length;
        }

        @Override
        protected boolean startInternal() {
            if (!table.trySharedLock(session))
                return true;
            session.getUser().checkRight(table, Right.UPDATE);
            tableFilter.startQuery(session);
            tableFilter.reset();
            table.fire(session, Trigger.UPDATE, true);
            statement.setCurrentRowNumber(0);
            if (limitRows == 0)
                hasNext = false;
            else
                hasNext = tableFilter.next(); // 提前next，当发生行锁时可以直接用tableFilter的当前值重试
            return false;
        }

        @Override
        protected void stopInternal() {
            table.fire(session, Trigger.UPDATE, false);
        }

        @Override
        protected void executeLoopUpdate() {
            if (oldRow != null) {
                // 如果oldRow已经删除了那么移到下一行
                if (tableFilter.rebuildSearchRow(session, oldRow) == null)
                    hasNext = tableFilter.next();
                oldRow = null;
            }
            while (hasNext && pendingException == null) {
                if (yieldIfNeeded(++loopCount)) {
                    return;
                }
                if (statement.condition == null || statement.condition.getBooleanValue(session)) {
                    Row oldRow = tableFilter.get();
                    int savepointId = session.getTransaction().getSavepointId();
                    if (!table.tryLockRow(session, oldRow, true, statement.columns)) {
                        this.oldRow = oldRow;
                        session.setStatus(SessionStatus.WAITING);
                        return;
                    }
                    session.setCurrentLockedRow(oldRow, savepointId);
                    Row newRow = createNewRow(oldRow);
                    table.validateConvertUpdateSequence(session, newRow);
                    boolean done = false;
                    if (table.fireRow()) {
                        done = table.fireBeforeRow(session, oldRow, newRow);
                    }
                    if (!done) {
                        pendingOperationCount.incrementAndGet();
                        updateRow(oldRow, newRow);
                        if (limitRows > 0 && updateCount.get() >= limitRows) {
                            onLoopEnd();
                            return;
                        }
                    }
                }
                hasNext = tableFilter.next();
            }
            onLoopEnd();
        }

        private Row createNewRow(Row oldRow) {
            Row newRow = table.getTemplateRow();
            newRow.setKey(oldRow.getKey()); // 复用原来的行号
            for (int i = 0; i < columnCount; i++) {
                Expression newExpr = statement.expressionMap.get(columns[i]);
                Value newValue;
                if (newExpr == null) {
                    newValue = oldRow.getValue(i);
                } else if (newExpr == ValueExpression.getDefault()) {
                    Column column = table.getColumn(i);
                    newValue = table.getDefaultValue(session, column);
                } else {
                    Column column = table.getColumn(i);
                    newValue = column.convert(newExpr.getValue(session));
                }
                newRow.setValue(i, newValue);
            }
            return newRow;
        }

        private void updateRow(Row oldRow, Row newRow) {
            table.updateRow(session, oldRow, newRow, statement.columns).onComplete(ar -> {
                if (ar.isSucceeded() && table.fireRow()) {
                    table.fireAfterRow(session, oldRow, newRow, false);
                }
                onComplete(ar);
            });
        }

        @Override
        public void back() {
            oldRow = session.getCurrentLockedRow();
            loopEnd = false;
            hasNext = true;
        }
    }
}
