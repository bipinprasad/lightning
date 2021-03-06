/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.  The ASF licenses this file to you under the Apache License, Version
 * 2.0 (the "License"); you may not use this file except in compliance with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */

package org.apache.storm.serialization;

import org.apache.storm.generated.GlobalStreamId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;


public class GzipBridgeThriftSerializationDelegateTest {
    SerializationDelegate testDelegate;

    @BeforeEach
    public void setUp() throws Exception {
        testDelegate = new GzipBridgeThriftSerializationDelegate();
    }

    @Test
    public void testDeserialize_readingFromGzip() {
        GlobalStreamId id = new GlobalStreamId("first", "second");

        byte[] serialized = new GzipThriftSerializationDelegate().serialize(id);

        GlobalStreamId id2 = testDelegate.deserialize(serialized, GlobalStreamId.class);

        assertEquals(id2.get_componentId(), id.get_componentId());
        assertEquals(id2.get_streamId(), id.get_streamId());
    }

    @Test
    public void testDeserialize_readingFromGzipBridge() {
        GlobalStreamId id = new GlobalStreamId("first", "second");

        byte[] serialized = new GzipBridgeThriftSerializationDelegate().serialize(id);

        GlobalStreamId id2 = testDelegate.deserialize(serialized, GlobalStreamId.class);

        assertEquals(id2.get_componentId(), id.get_componentId());
        assertEquals(id2.get_streamId(), id.get_streamId());
    }

    @Test
    public void testDeserialize_readingFromDefault() {
        GlobalStreamId id = new GlobalStreamId("A", "B");

        byte[] serialized = new ThriftSerializationDelegate().serialize(id);

        GlobalStreamId id2 = testDelegate.deserialize(serialized, GlobalStreamId.class);

        assertEquals(id2.get_componentId(), id.get_componentId());
        assertEquals(id2.get_streamId(), id.get_streamId());
    }
}
