// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Inc.

package com.starrocks.sql.optimizer.rule.transformation;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.starrocks.analysis.JoinOperator;
import com.starrocks.sql.analyzer.SemanticException;
import com.starrocks.sql.optimizer.JoinHelper;
import com.starrocks.sql.optimizer.OptExpression;
import com.starrocks.sql.optimizer.Utils;
import com.starrocks.sql.optimizer.base.ColumnRefSet;
import com.starrocks.sql.optimizer.operator.OperatorType;
import com.starrocks.sql.optimizer.operator.logical.LogicalFilterOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalJoinOperator;
import com.starrocks.sql.optimizer.operator.pattern.Pattern;
import com.starrocks.sql.optimizer.operator.scalar.BinaryPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.IsNullPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.sql.optimizer.rewrite.ScalarEquivalenceExtractor;
import com.starrocks.sql.optimizer.rewrite.ScalarRangePredicateExtractor;
import com.starrocks.sql.optimizer.rule.RuleType;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public abstract class PushDownJoinPredicateBase extends TransformationRule {
    protected PushDownJoinPredicateBase(RuleType type, Pattern pattern) {
        super(type, pattern);
    }

    public static OptExpression pushDownPredicate(OptExpression root, List<ScalarOperator> leftPushDown,
                                                  List<ScalarOperator> rightPushDown) {
        if (leftPushDown != null && !leftPushDown.isEmpty()) {
            Set<ScalarOperator> set = Sets.newLinkedHashSet(leftPushDown);
            OptExpression newLeft = new OptExpression(new LogicalFilterOperator(Utils.compoundAnd(set)));
            newLeft.getInputs().add(root.getInputs().get(0));
            root.getInputs().set(0, newLeft);
        }

        if (rightPushDown != null && !rightPushDown.isEmpty()) {
            Set<ScalarOperator> set = Sets.newLinkedHashSet(rightPushDown);
            OptExpression newRight = new OptExpression(new LogicalFilterOperator(Utils.compoundAnd(set)));
            newRight.getInputs().add(root.getInputs().get(1));
            root.getInputs().set(1, newRight);
        }

        return root;
    }

    public static OptExpression pushDownOnPredicate(OptExpression input, ScalarOperator conjunct) {
        LogicalJoinOperator join = (LogicalJoinOperator) input.getOp();

        List<ScalarOperator> conjunctList = Utils.extractConjuncts(conjunct);
        List<BinaryPredicateOperator> eqConjuncts = JoinHelper.getEqualsPredicate(
                input.getInputs().get(0).getOutputColumns(),
                input.getInputs().get(1).getOutputColumns(),
                conjunctList);
        conjunctList.removeAll(eqConjuncts);

        List<ScalarOperator> leftPushDown = Lists.newArrayList();
        List<ScalarOperator> rightPushDown = Lists.newArrayList();
        ColumnRefSet leftColumns = input.getInputs().get(0).getOutputColumns();
        ColumnRefSet rightColumns = input.getInputs().get(1).getOutputColumns();
        if (join.getJoinType().isInnerJoin() || join.getJoinType().isCrossJoin()) {
            for (ScalarOperator predicate : conjunctList) {
                ColumnRefSet usedColumns = predicate.getUsedColumns();
                if (usedColumns.isEmpty()) {
                    leftPushDown.add(predicate);
                    rightPushDown.add(predicate);
                } else if (leftColumns.containsAll(usedColumns)) {
                    leftPushDown.add(predicate);
                } else if (rightColumns.containsAll(usedColumns)) {
                    rightPushDown.add(predicate);
                }
            }
        } else if (join.getJoinType().isOuterJoin()) {
            for (ScalarOperator predicate : conjunctList) {
                ColumnRefSet usedColumns = predicate.getUsedColumns();
                if (leftColumns.containsAll(usedColumns) && join.getJoinType().isRightOuterJoin()) {
                    leftPushDown.add(predicate);
                } else if (rightColumns.containsAll(usedColumns) && join.getJoinType().isLeftOuterJoin()) {
                    rightPushDown.add(predicate);
                }
            }
        } else if (join.getJoinType().isSemiJoin() || join.getJoinType().isAntiJoin()) {
            for (ScalarOperator predicate : conjunctList) {
                ColumnRefSet usedColumns = predicate.getUsedColumns();
                if (leftColumns.containsAll(usedColumns)) {
                    if (join.getJoinType().isLeftAntiJoin()) {
                        continue;
                    }
                    leftPushDown.add(predicate);
                } else if (rightColumns.containsAll(usedColumns)) {
                    if (join.getJoinType().isRightAntiJoin()) {
                        continue;
                    }
                    rightPushDown.add(predicate);
                }
            }
        }
        conjunctList.removeAll(leftPushDown);
        conjunctList.removeAll(rightPushDown);

        ScalarOperator joinPredicate = Utils.compoundAnd(Lists.newArrayList(eqConjuncts));
        ScalarOperator postJoinPredicate = Utils.compoundAnd(conjunctList);

        OptExpression root;
        if (joinPredicate == null) {
            if (join.getJoinType().isInnerJoin() || join.getJoinType().isCrossJoin()) {
                LogicalJoinOperator crossJoin = new LogicalJoinOperator.Builder().withOperator(join)
                        .setJoinType(JoinOperator.CROSS_JOIN)
                        .setOnPredicate(null)
                        .setPredicate(Utils.compoundAnd(postJoinPredicate, join.getPredicate()))
                        .build();
                root = OptExpression.create(crossJoin, input.getInputs());
            } else {
                throw new SemanticException("No equal on predicate in " + join.getJoinType() + " is not supported");
            }
        } else {
            LogicalJoinOperator newJoin;
            if (join.getJoinType().isInnerJoin() || join.getJoinType().isCrossJoin()) {
                newJoin = new LogicalJoinOperator.Builder().withOperator(join)
                        .setJoinType(JoinOperator.INNER_JOIN)
                        .setOnPredicate(Utils.compoundAnd(joinPredicate, postJoinPredicate))
                        .build();
            } else {
                newJoin = new LogicalJoinOperator.Builder().withOperator(join)
                        .setJoinType(join.getJoinType())
                        .setOnPredicate(Utils.compoundAnd(joinPredicate, postJoinPredicate))
                        .build();
            }
            root = OptExpression.create(newJoin, input.getInputs());
        }

        if (!join.hasDeriveIsNotNullPredicate()) {
            deriveIsNotNullPredicate(eqConjuncts, root, leftPushDown, rightPushDown);
        }
        return pushDownPredicate(root, leftPushDown, rightPushDown);
    }

    public static ScalarOperator equivalenceDerive(ScalarOperator predicate, boolean returnInputPredicate) {
        ScalarEquivalenceExtractor scalarEquivalenceExtractor = new ScalarEquivalenceExtractor();

        Set<ColumnRefOperator> allColumnRefs = Sets.newLinkedHashSet();
        allColumnRefs.addAll(Utils.extractColumnRef(predicate));

        Set<ScalarOperator> allPredicate = Sets.newLinkedHashSet();
        List<ScalarOperator> inputPredicates = Utils.extractConjuncts(predicate);
        allPredicate.addAll(inputPredicates);

        scalarEquivalenceExtractor.union(inputPredicates);

        for (ColumnRefOperator ref : allColumnRefs) {
            for (ScalarOperator so : scalarEquivalenceExtractor.getEquivalentScalar(ref)) {
                // only use one column predicate
                if (Utils.countColumnRef(so) > 1) {
                    continue;
                }

                if (OperatorType.BINARY.equals(so.getOpType())) {
                    BinaryPredicateOperator bpo = (BinaryPredicateOperator) so;

                    // avoid repeat predicate, like a = b, b = a
                    if (!allPredicate.contains(bpo) && !allPredicate.contains(bpo.commutative())) {
                        allPredicate.add(bpo);
                    }
                    continue;
                }

                allPredicate.add(so);
            }
        }

        if (!returnInputPredicate) {
            inputPredicates.forEach(allPredicate::remove);
        }
        return Utils.compoundAnd(Lists.newArrayList(allPredicate));
    }

    public static ScalarOperator rangePredicateDerive(ScalarOperator predicate) {
        ScalarRangePredicateExtractor scalarRangePredicateExtractor = new ScalarRangePredicateExtractor();
        return scalarRangePredicateExtractor.rewriteAll(Utils.compoundAnd(
                Utils.extractConjuncts(predicate).stream().map(scalarRangePredicateExtractor::rewriteAll)
                        .collect(Collectors.toList())));
    }

    private static void deriveIsNotNullPredicate(List<BinaryPredicateOperator> onEQPredicates, OptExpression join,
                                                 List<ScalarOperator> leftPushDown,
                                                 List<ScalarOperator> rightPushDown) {
        List<ScalarOperator> leftEQ = Lists.newArrayList();
        List<ScalarOperator> rightEQ = Lists.newArrayList();

        for (BinaryPredicateOperator bpo : onEQPredicates) {
            if (!bpo.getBinaryType().isEqual()) {
                continue;
            }

            if (join.getChildOutputColumns(0).containsAll(bpo.getChild(0).getUsedColumns())) {
                leftEQ.add(bpo.getChild(0));
                rightEQ.add(bpo.getChild(1));
            } else if (join.getChildOutputColumns(0).containsAll(bpo.getChild(1).getUsedColumns())) {
                leftEQ.add(bpo.getChild(1));
                rightEQ.add(bpo.getChild(0));
            }
        }

        LogicalJoinOperator joinOp = ((LogicalJoinOperator) join.getOp());
        JoinOperator joinType = joinOp.getJoinType();
        if ((joinType.isInnerJoin() || joinType.isRightSemiJoin()) && leftPushDown.isEmpty()) {
            leftEQ.stream().map(c -> new IsNullPredicateOperator(true, c.clone())).forEach(leftPushDown::add);
        }
        if ((joinType.isInnerJoin() || joinType.isLeftSemiJoin()) && rightPushDown.isEmpty()) {
            rightEQ.stream().map(c -> new IsNullPredicateOperator(true, c.clone())).forEach(rightPushDown::add);
        }
        joinOp.setHasDeriveIsNotNullPredicate(true);
    }
}
