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
package org.apache.pinot.core.function.scalar;

import java.math.BigDecimal;
import org.apache.pinot.core.common.ObjectSerDeUtils;
import org.testng.Assert;
import org.testng.annotations.Test;


public class SketchFunctionsTest {

  private double thetaEstimate(byte[] bytes) {
    return ObjectSerDeUtils.DATA_SKETCH_SER_DE.deserialize(bytes).getEstimate();
  }

  byte[] _bytes = {1, 2, 3};

  Object[] _inputs = {
      "string", 1, 1L, 1.0f, 1.0d, BigDecimal.valueOf(1), _bytes
  };

  @Test
  public void testThetaSketchCreation() {
    for (Object i : _inputs) {
      Assert.assertEquals(thetaEstimate(SketchFunctions.toThetaSketch(i)), 1.0);
      Assert.assertEquals(thetaEstimate(SketchFunctions.toThetaSketch(i, 1024)), 1.0);
    }
    Assert.assertEquals(thetaEstimate(SketchFunctions.toThetaSketch(null)), 0.0);
    Assert.assertEquals(thetaEstimate(SketchFunctions.toThetaSketch(null, 1024)), 0.0);
  }

  private long hllEstimate(byte[] bytes) {
    return ObjectSerDeUtils.HYPER_LOG_LOG_SER_DE.deserialize(bytes).cardinality();
  }

  @Test
  public void hllCreation() {
    for (Object i : _inputs) {
      Assert.assertEquals(hllEstimate(SketchFunctions.toHLL(i)), 1);
      Assert.assertEquals(hllEstimate(SketchFunctions.toHLL(i, 8)), 1);
    }
    Assert.assertEquals(hllEstimate(SketchFunctions.toHLL(null)), 0);
    Assert.assertEquals(hllEstimate(SketchFunctions.toHLL(null, 8)), 0);
  }
}
