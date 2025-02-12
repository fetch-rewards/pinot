/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.core.query.distinct.raw;

import it.unimi.dsi.fastutil.floats.FloatHeapPriorityQueue;
import it.unimi.dsi.fastutil.floats.FloatPriorityQueue;
import org.apache.pinot.common.request.context.ExpressionContext;
import org.apache.pinot.common.request.context.OrderByExpressionContext;
import org.apache.pinot.core.common.BlockValSet;
import org.apache.pinot.core.operator.blocks.ValueBlock;
import org.apache.pinot.core.query.distinct.DistinctExecutor;
import org.apache.pinot.spi.data.FieldSpec.DataType;
import org.roaringbitmap.RoaringBitmap;


/**
 * {@link DistinctExecutor} for distinct order-by queries with single raw FLOAT column.
 */
public class RawFloatSingleColumnDistinctOrderByExecutor extends BaseRawFloatSingleColumnDistinctExecutor {
  private final FloatPriorityQueue _priorityQueue;

  public RawFloatSingleColumnDistinctOrderByExecutor(ExpressionContext expression, DataType dataType,
      OrderByExpressionContext orderByExpression, int limit, boolean nullHandlingEnabled) {
    super(expression, dataType, limit, nullHandlingEnabled);

    assert orderByExpression.getExpression().equals(expression);
    int comparisonFactor = orderByExpression.isAsc() ? -1 : 1;
    _priorityQueue = new FloatHeapPriorityQueue(Math.min(limit, MAX_INITIAL_CAPACITY),
        (f1, f2) -> Float.compare(f1, f2) * comparisonFactor);
  }

  @Override
  public boolean process(ValueBlock valueBlock) {
    BlockValSet blockValueSet = valueBlock.getBlockValueSet(_expression);
    int numDocs = valueBlock.getNumDocs();
    if (blockValueSet.isSingleValue()) {
      float[] values = blockValueSet.getFloatValuesSV();
      if (_nullHandlingEnabled) {
        RoaringBitmap nullBitmap = blockValueSet.getNullBitmap();
        for (int i = 0; i < numDocs; i++) {
          if (nullBitmap != null && nullBitmap.contains(i)) {
            _hasNull = true;
          } else {
            add(values[i]);
          }
        }
      } else {
        for (int i = 0; i < numDocs; i++) {
          add(values[i]);
        }
      }
    } else {
      float[][] values = blockValueSet.getFloatValuesMV();
      for (int i = 0; i < numDocs; i++) {
        for (float value : values[i]) {
          add(value);
        }
      }
    }
    return false;
  }

  private void add(float value) {
    if (!_valueSet.contains(value)) {
      if (_valueSet.size() < _limit - (_hasNull ? 1 : 0)) {
        _valueSet.add(value);
        _priorityQueue.enqueue(value);
      } else {
        float firstValue = _priorityQueue.firstFloat();
        if (_priorityQueue.comparator().compare(value, firstValue) > 0) {
          _valueSet.remove(firstValue);
          _valueSet.add(value);
          _priorityQueue.dequeueFloat();
          _priorityQueue.enqueue(value);
        }
      }
    }
  }
}
