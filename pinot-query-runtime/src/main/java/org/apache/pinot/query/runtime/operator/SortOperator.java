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
package org.apache.pinot.query.runtime.operator;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.PriorityQueue;
import javax.annotation.Nullable;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.pinot.common.datablock.DataBlock;
import org.apache.pinot.common.utils.DataSchema;
import org.apache.pinot.core.query.selection.SelectionOperatorUtils;
import org.apache.pinot.query.planner.logical.RexExpression;
import org.apache.pinot.query.runtime.blocks.TransferableBlock;
import org.apache.pinot.query.runtime.blocks.TransferableBlockUtils;
import org.apache.pinot.query.runtime.operator.utils.SortUtils;
import org.apache.pinot.query.runtime.plan.OpChainExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class SortOperator extends MultiStageOperator {
  private static final String EXPLAIN_NAME = "SORT";
  private static final Logger LOGGER = LoggerFactory.getLogger(SortOperator.class);

  private final MultiStageOperator _upstreamOperator;
  private final int _fetch;
  private final int _offset;
  private final DataSchema _dataSchema;
  private final PriorityQueue<Object[]> _priorityQueue;
  private final ArrayList<Object[]> _rows;
  private final int _numRowsToKeep;

  private boolean _readyToConstruct;
  private boolean _isSortedBlockConstructed;
  private TransferableBlock _upstreamErrorBlock;

  public SortOperator(OpChainExecutionContext context, MultiStageOperator upstreamOperator,
      List<RexExpression> collationKeys, List<RelFieldCollation.Direction> collationDirections, int fetch, int offset,
      DataSchema dataSchema, boolean isInputSorted) {
    this(context, upstreamOperator, collationKeys, collationDirections, fetch, offset, dataSchema, isInputSorted,
        SelectionOperatorUtils.MAX_ROW_HOLDER_INITIAL_CAPACITY);
  }

  @VisibleForTesting
  SortOperator(OpChainExecutionContext context, MultiStageOperator upstreamOperator, List<RexExpression> collationKeys,
      List<RelFieldCollation.Direction> collationDirections, int fetch, int offset, DataSchema dataSchema,
      boolean isInputSorted, int defaultHolderCapacity) {
    super(context);
    _upstreamOperator = upstreamOperator;
    _fetch = fetch;
    _offset = Math.max(offset, 0);
    _dataSchema = dataSchema;
    _upstreamErrorBlock = null;
    _isSortedBlockConstructed = false;
    _numRowsToKeep = _fetch > 0 ? _fetch + _offset : defaultHolderCapacity;
    // Under the following circumstances, the SortOperator is a simple selection with row trim on limit & offset:
    // - There are no collationKeys
    // - 'isInputSorted' is set to true indicating that the data was already sorted
    if (collationKeys.isEmpty() || isInputSorted) {
      _priorityQueue = null;
      _rows = new ArrayList<>();
    } else {
      _priorityQueue = new PriorityQueue<>(_numRowsToKeep,
          new SortUtils.SortComparator(collationKeys, collationDirections, dataSchema, false));
      _rows = null;
    }
  }

  @Override
  public List<MultiStageOperator> getChildOperators() {
    return ImmutableList.of(_upstreamOperator);
  }

  @Override
  public void cancel(Throwable e) {
  }

  @Nullable
  @Override
  public String toExplainString() {
    return EXPLAIN_NAME;
  }

  @Override
  protected TransferableBlock getNextBlock() {
    try {
      consumeInputBlocks();
      return produceSortedBlock();
    } catch (Exception e) {
      return TransferableBlockUtils.getErrorTransferableBlock(e);
    }
  }

  private TransferableBlock produceSortedBlock() {
    if (_upstreamErrorBlock != null) {
      return _upstreamErrorBlock;
    } else if (!_readyToConstruct) {
      return TransferableBlockUtils.getNoOpTransferableBlock();
    }

    if (!_isSortedBlockConstructed) {
      _isSortedBlockConstructed = true;
      if (_priorityQueue == null) {
        if (_rows.size() > _offset) {
          List<Object[]> row = _rows.subList(_offset, _rows.size());
          return new TransferableBlock(row, _dataSchema, DataBlock.Type.ROW);
        } else {
          return TransferableBlockUtils.getEndOfStreamTransferableBlock();
        }
      } else {
        LinkedList<Object[]> rows = new LinkedList<>();
        while (_priorityQueue.size() > _offset) {
          Object[] row = _priorityQueue.poll();
          rows.addFirst(row);
        }
        if (rows.size() == 0) {
          return TransferableBlockUtils.getEndOfStreamTransferableBlock();
        } else {
          return new TransferableBlock(rows, _dataSchema, DataBlock.Type.ROW);
        }
      }
    } else {
      return TransferableBlockUtils.getEndOfStreamTransferableBlock();
    }
  }

  private void consumeInputBlocks() {
    if (!_isSortedBlockConstructed) {
      TransferableBlock block = _upstreamOperator.nextBlock();
      while (!block.isNoOpBlock()) {
        // setting upstream error block
        if (block.isErrorBlock()) {
          _upstreamErrorBlock = block;
          return;
        } else if (TransferableBlockUtils.isEndOfStream(block)) {
          _readyToConstruct = true;
          return;
        }

        List<Object[]> container = block.getContainer();
        if (_priorityQueue == null) {
          // TODO: when push-down properly, we shouldn't get more than _numRowsToKeep
          if (_rows.size() <= _numRowsToKeep) {
            if (_rows.size() + container.size() <= _numRowsToKeep) {
              _rows.addAll(container);
            } else {
              _rows.addAll(container.subList(0, _numRowsToKeep - _rows.size()));
            }
          }
        } else {
          for (Object[] row : container) {
            SelectionOperatorUtils.addToPriorityQueue(row, _priorityQueue, _numRowsToKeep);
          }
        }
        block = _upstreamOperator.nextBlock();
      }
    }
  }
}
