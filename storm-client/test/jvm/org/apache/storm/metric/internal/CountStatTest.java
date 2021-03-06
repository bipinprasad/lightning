/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.  The ASF licenses this file to you under the Apache License, Version
 * 2.0 (the "License"); you may not use this file except in compliance with the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */

package org.apache.storm.metric.internal;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit test for CountStat
 */
public class CountStatTest {
    final long TEN_MIN = 10 * 60 * 1000;
    final long THIRTY_SEC = 30 * 1000;
    final long THREE_HOUR = 3 * 60 * 60 * 1000;
    final long ONE_DAY = 24 * 60 * 60 * 1000;

    @Test
    public void testBasic() {
        long time = 0L;
        CountStat count = new CountStat(10, time);
        while (time < TEN_MIN) {
            //For this part of the test we interleave the different rotation types.
            count.incBy(50);
            time += THIRTY_SEC / 2;
            count.rotateSched(time);
            count.incBy(50);
            time += THIRTY_SEC / 2;
        }

        long val = 100 * TEN_MIN / THIRTY_SEC;
        Map<String, Long> expected = new HashMap<>();
        expected.put("600", val);
        expected.put("10800", val);
        expected.put("86400", val);
        expected.put(":all-time", val);
        assertEquals(expected, count.getTimeCounts(time));

        while (time < THREE_HOUR) {
            count.incBy(100);
            time += THIRTY_SEC;
        }

        val = 100 * THREE_HOUR / THIRTY_SEC;
        expected = new HashMap<>();
        expected.put("600", 100 * TEN_MIN / THIRTY_SEC);
        expected.put("10800", val);
        expected.put("86400", val);
        expected.put(":all-time", val);
        assertEquals(expected, count.getTimeCounts(time));

        while (time < ONE_DAY) {
            count.incBy(100);
            time += THIRTY_SEC;
        }

        val = 100 * ONE_DAY / THIRTY_SEC;
        expected = new HashMap<>();
        expected.put("600", 100 * TEN_MIN / THIRTY_SEC);
        expected.put("10800", 100 * THREE_HOUR / THIRTY_SEC);
        expected.put("86400", val);
        expected.put(":all-time", val);
        assertEquals(expected, count.getTimeCounts(time));
    }
}
