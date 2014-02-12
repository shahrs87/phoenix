/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.coprocessor;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.regionserver.RegionScanner;
import org.apache.hadoop.hbase.util.Bytes;

import org.apache.hadoop.hbase.index.util.ImmutableBytesPtr;
import org.apache.phoenix.cache.GlobalCache;
import org.apache.phoenix.cache.HashCache;
import org.apache.phoenix.cache.TenantCache;
import org.apache.phoenix.expression.Expression;
import org.apache.phoenix.join.HashJoinInfo;
import org.apache.phoenix.join.ScanProjector;
import org.apache.phoenix.join.ScanProjector.ProjectedValueTuple;
import org.apache.phoenix.parse.JoinTableNode.JoinType;
import org.apache.phoenix.schema.IllegalDataException;
import org.apache.phoenix.schema.KeyValueSchema;
import org.apache.phoenix.schema.ValueBitSet;
import org.apache.phoenix.schema.tuple.ResultTuple;
import org.apache.phoenix.schema.tuple.Tuple;
import org.apache.phoenix.util.TupleUtil;

public class HashJoinRegionScanner implements RegionScanner {
    
    private final RegionScanner scanner;
    private final ScanProjector projector;
    private final HashJoinInfo joinInfo;
    private Queue<Tuple> resultQueue;
    private boolean hasMore;
    private HashCache[] hashCaches;
    private List<Tuple>[] tempTuples;
    private ValueBitSet tempDestBitSet;
    private ValueBitSet[] tempSrcBitSet;
    
    @SuppressWarnings("unchecked")
    public HashJoinRegionScanner(RegionScanner scanner, ScanProjector projector, HashJoinInfo joinInfo, ImmutableBytesWritable tenantId, RegionCoprocessorEnvironment env) throws IOException {
        this.scanner = scanner;
        this.projector = projector;
        this.joinInfo = joinInfo;
        this.resultQueue = new LinkedList<Tuple>();
        this.hasMore = true;
        if (joinInfo != null) {
            for (JoinType type : joinInfo.getJoinTypes()) {
                if (type != JoinType.Inner && type != JoinType.Left)
                    throw new IOException("Got join type '" + type + "'. Expect only INNER or LEFT with hash-joins.");
            }
            int count = joinInfo.getJoinIds().length;
            this.tempTuples = new List[count];
            this.hashCaches = new HashCache[count];
            this.tempSrcBitSet = new ValueBitSet[count];
            TenantCache cache = GlobalCache.getTenantCache(env, tenantId);
            for (int i = 0; i < count; i++) {
                ImmutableBytesPtr joinId = joinInfo.getJoinIds()[i];
                HashCache hashCache = (HashCache)cache.getServerCache(joinId);
                if (hashCache == null)
                    throw new IOException("Could not find hash cache for joinId: " + Bytes.toString(joinId.get(), joinId.getOffset(), joinId.getLength()));
                hashCaches[i] = hashCache;
                tempSrcBitSet[i] = ValueBitSet.newInstance(joinInfo.getSchemas()[i]);
            }
            if (this.projector != null) {
                this.tempDestBitSet = ValueBitSet.newInstance(joinInfo.getJoinedSchema());
                this.projector.setValueBitSet(tempDestBitSet);
            }
        }
    }
    
    private void processResults(List<KeyValue> result, boolean hasLimit) throws IOException {
        if (result.isEmpty())
            return;
        
        Tuple tuple = new ResultTuple(new Result(result));
        if (joinInfo == null) {
            resultQueue.offer(projector.projectResults(tuple));
            return;
        }
        
        if (hasLimit)
            throw new UnsupportedOperationException("Cannot support join operations in scans with limit");

        int count = joinInfo.getJoinIds().length;
        boolean cont = true;
        for (int i = 0; i < count; i++) {
            if (!(joinInfo.earlyEvaluation()[i]))
                continue;
            ImmutableBytesPtr key = TupleUtil.getConcatenatedValue(tuple, joinInfo.getJoinExpressions()[i]);
            tempTuples[i] = hashCaches[i].get(key);
            JoinType type = joinInfo.getJoinTypes()[i];
            if (type == JoinType.Inner && tempTuples[i] == null) {
                cont = false;
                break;
            }
        }
        if (cont) {
            if (projector == null) {
                int dup = 1;
                for (int i = 0; i < count; i++) {
                    dup *= (tempTuples[i] == null ? 1 : tempTuples[i].size());
                }
                for (int i = 0; i < dup; i++) {
                    resultQueue.offer(tuple);
                }
            } else {
                KeyValueSchema schema = joinInfo.getJoinedSchema();
                resultQueue.offer(projector.projectResults(tuple));
                for (int i = 0; i < count; i++) {
                    boolean earlyEvaluation = joinInfo.earlyEvaluation()[i];
                    if (earlyEvaluation && tempTuples[i] == null)
                        continue;
                    int j = resultQueue.size();
                    while (j-- > 0) {
                        Tuple lhs = resultQueue.poll();
                        if (!earlyEvaluation) {
                            ImmutableBytesPtr key = TupleUtil.getConcatenatedValue(lhs, joinInfo.getJoinExpressions()[i]);
                            tempTuples[i] = hashCaches[i].get(key);                        	
                            if (tempTuples[i] == null) {
                                if (joinInfo.getJoinTypes()[i] != JoinType.Inner) {
                                    resultQueue.offer(lhs);
                                }
                                continue;
                            }
                        }
                        for (Tuple t : tempTuples[i]) {
                            Tuple joined = tempSrcBitSet[i] == ValueBitSet.EMPTY_VALUE_BITSET ?
                                    lhs : ScanProjector.mergeProjectedValue(
                                            (ProjectedValueTuple) lhs, schema, tempDestBitSet,
                                            t, joinInfo.getSchemas()[i], tempSrcBitSet[i], 
                                            joinInfo.getFieldPositions()[i]);
                            resultQueue.offer(joined);
                        }
                    }
                }
            }
            // apply post-join filter
            Expression postFilter = joinInfo.getPostJoinFilterExpression();
            if (postFilter != null) {
                for (Iterator<Tuple> iter = resultQueue.iterator(); iter.hasNext();) {
                    Tuple t = iter.next();
                    ImmutableBytesWritable tempPtr = new ImmutableBytesWritable();
                    try {
                        if (!postFilter.evaluate(t, tempPtr)) {
                            iter.remove();
                            continue;
                        }
                    } catch (IllegalDataException e) {
                        iter.remove();
                        continue;
                    }
                    Boolean b = (Boolean)postFilter.getDataType().toObject(tempPtr);
                    if (!b.booleanValue()) {
                        iter.remove();
                    }
                }
            }
        }
    }
    
    private boolean shouldAdvance() {
        if (!resultQueue.isEmpty())
            return false;
        
        return hasMore;
    }
    
    private boolean nextInQueue(List<KeyValue> results) {
        if (resultQueue.isEmpty())
            return false;
        
        Tuple tuple = resultQueue.poll();
        for (int i = 0; i < tuple.size(); i++) {
            results.add(tuple.getValue(i));
        }
        return resultQueue.isEmpty() ? hasMore : true;
    }

    @Override
    public long getMvccReadPoint() {
        return scanner.getMvccReadPoint();
    }

    @Override
    public HRegionInfo getRegionInfo() {
        return scanner.getRegionInfo();
    }

    @Override
    public boolean isFilterDone() {
        return scanner.isFilterDone() && resultQueue.isEmpty();
    }

    @Override
    public boolean nextRaw(List<KeyValue> result, String metric) throws IOException {
        while (shouldAdvance()) {
            hasMore = scanner.nextRaw(result, metric);
            processResults(result, false);
            result.clear();
        }
        
        return nextInQueue(result);
    }

    @Override
    public boolean nextRaw(List<KeyValue> result, int limit, String metric)
            throws IOException {
        while (shouldAdvance()) {
            hasMore = scanner.nextRaw(result, limit, metric);
            processResults(result, true);
            result.clear();
        }
        
        return nextInQueue(result);
    }

    @Override
    public boolean reseek(byte[] row) throws IOException {
        return scanner.reseek(row);
    }

    @Override
    public void close() throws IOException {
        scanner.close();
    }

    @Override
    public boolean next(List<KeyValue> result) throws IOException {
        while (shouldAdvance()) {
            hasMore = scanner.next(result);
            processResults(result, false);
            result.clear();
        }
        
        return nextInQueue(result);
    }

    @Override
    public boolean next(List<KeyValue> result, String metric) throws IOException {
        while (shouldAdvance()) {
            hasMore = scanner.next(result, metric);
            processResults(result, false);
            result.clear();
        }
        
        return nextInQueue(result);
    }

    @Override
    public boolean next(List<KeyValue> result, int limit) throws IOException {
        while (shouldAdvance()) {
            hasMore = scanner.next(result, limit);
            processResults(result, true);
            result.clear();
        }
        
        return nextInQueue(result);
    }

    @Override
    public boolean next(List<KeyValue> result, int limit, String metric)
            throws IOException {
        while (shouldAdvance()) {
            hasMore = scanner.next(result, limit, metric);
            processResults(result, true);
            result.clear();
        }
        
        return nextInQueue(result);
    }

}

