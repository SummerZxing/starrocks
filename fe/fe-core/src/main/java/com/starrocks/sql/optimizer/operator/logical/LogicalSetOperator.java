// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Inc.
package com.starrocks.sql.optimizer.operator.logical;

import com.starrocks.sql.optimizer.ExpressionContext;
import com.starrocks.sql.optimizer.base.ColumnRefSet;
import com.starrocks.sql.optimizer.operator.Operator;
import com.starrocks.sql.optimizer.operator.OperatorType;
import com.starrocks.sql.optimizer.operator.Projection;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public abstract class LogicalSetOperator extends LogicalOperator {
    protected List<ColumnRefOperator> outputColumnRefOp;
    protected List<List<ColumnRefOperator>> childOutputColumns;

    public LogicalSetOperator(OperatorType type, List<ColumnRefOperator> result,
                              List<List<ColumnRefOperator>> childOutputColumns,
                              long limit,
                              Projection projection) {
        super(type, limit, null, projection);
        this.outputColumnRefOp = result;
        this.childOutputColumns = childOutputColumns;
    }

    public List<ColumnRefOperator> getOutputColumnRefOp() {
        return outputColumnRefOp;
    }

    public List<List<ColumnRefOperator>> getChildOutputColumns() {
        return childOutputColumns;
    }

    @Override
    public ColumnRefSet getOutputColumns(ExpressionContext expressionContext) {
        if (projection != null) {
            return new ColumnRefSet(new ArrayList<>(projection.getColumnRefMap().keySet()));
        } else {
            return new ColumnRefSet(outputColumnRefOp);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        LogicalSetOperator that = (LogicalSetOperator) o;
        return Objects.equals(outputColumnRefOp, that.outputColumnRefOp) && opType == that.opType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), outputColumnRefOp);
    }

    public abstract static class Builder<O extends LogicalSetOperator, B extends LogicalSetOperator.Builder>
            extends Operator.Builder<O, B> {
        protected List<ColumnRefOperator> outputColumnRefOp;
        protected List<List<ColumnRefOperator>> childOutputColumns;

        @Override
        public B withOperator(O setOperator) {
            super.withOperator(setOperator);
            this.outputColumnRefOp = setOperator.outputColumnRefOp;
            this.childOutputColumns = setOperator.childOutputColumns;
            return (B) this;
        }

        public B setOutputColumnRefOp(
                List<ColumnRefOperator> outputColumnRefOp) {
            this.outputColumnRefOp = outputColumnRefOp;
            return (B) this;
        }

        public B setChildOutputColumns(
                List<List<ColumnRefOperator>> childOutputColumns) {
            this.childOutputColumns = childOutputColumns;
            return (B) this;
        }
    }
}
