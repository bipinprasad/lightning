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

package org.apache.storm.messaging.netty;

import java.util.ArrayList;
import java.util.List;
import org.apache.storm.messaging.TaskMessage;
import org.apache.storm.serialization.KryoValuesDeserializer;
import org.apache.storm.shade.io.netty.buffer.ByteBuf;
import org.apache.storm.shade.io.netty.channel.ChannelHandlerContext;
import org.apache.storm.shade.io.netty.handler.codec.ByteToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MessageDecoder extends ByteToMessageDecoder {

    private static final Logger LOG = LoggerFactory.getLogger(MessageDecoder.class);
    private final KryoValuesDeserializer deser;

    public MessageDecoder(KryoValuesDeserializer deser) {
        this.deser = deser;
    }

    /*
     * Each ControlMessage is encoded as:
     *  code (<0) ... short(2)
     * Each TaskMessage is encoded as:
     *  task (>=0) ... short(2)
     *  len ... int(4)
     *  payload ... byte[]     *
     */
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf buf, List<Object> out) throws Exception {
        // Make sure that we have received at least a short 
        long available = buf.readableBytes();
        if (available < 2) {
            //need more data
            return;
        }

        List<Object> ret = new ArrayList<>();

        // Use while loop, try to decode as more messages as possible in single call
        while (available >= 2) {

            // Mark the current buffer position before reading task/len field
            // because the whole frame might not be in the buffer yet.
            // We will reset the buffer position to the marked position if
            // there's not enough bytes in the buffer.
            buf.markReaderIndex();

            // read the short field
            short code = buf.readShort();
            available -= 2;

            // case 1: Control message
            ControlMessage controlMessage = ControlMessage.mkMessage(code);
            if (controlMessage != null) {

                if (controlMessage == ControlMessage.EOB_MESSAGE) {
                    continue;
                } else {
                    out.add(controlMessage);
                    return;
                }
            }

            //case 2: SaslTokenMessageRequest
            if (code == SaslMessageToken.IDENTIFIER) {
                // Make sure that we have received at least an integer (length)
                if (buf.readableBytes() < 4) {
                    //need more data
                    buf.resetReaderIndex();
                    return;
                }

                // Read the length field.
                int length = buf.readInt();
                if (length <= 0) {
                    out.add(new SaslMessageToken(null));
                    return;
                }

                // Make sure if there's enough bytes in the buffer.
                if (buf.readableBytes() < length) {
                    // The whole bytes were not received yet - return null.
                    buf.resetReaderIndex();
                    return;
                }

                // There's enough bytes in the buffer. Read it.  
                byte[] bytes = new byte[length];
                buf.readBytes(bytes);
                // Successfully decoded a frame.
                // Return a SaslTokenMessageRequest object
                out.add(new SaslMessageToken(bytes));
                return;
            }

            // case 3: BackPressureStatus
            if (code == BackPressureStatus.IDENTIFIER) {
                available = buf.readableBytes();
                if (available < 4) {
                    //Need  more data
                    buf.resetReaderIndex();
                    return;
                }
                int dataLen = buf.readInt();
                if (available < 4 + dataLen) {
                    // need more data
                    buf.resetReaderIndex();
                    return;
                }
                byte[] bytes = new byte[dataLen];
                buf.readBytes(bytes);
                out.add(BackPressureStatus.read(bytes, deser));
                return;
            }

            // case 4: task Message

            // Make sure that we have received at least an integer (length)
            if (available < 4) {
                // need more data
                buf.resetReaderIndex();
                break;
            }

            // Read the length field.
            int length = buf.readInt();

            available -= 4;

            if (length <= 0) {
                ret.add(new TaskMessage(code, null));
                break;
            }

            // Make sure if there's enough bytes in the buffer.
            if (available < length) {
                // The whole bytes were not received yet - return null.
                buf.resetReaderIndex();
                break;
            }
            available -= length;

            // There's enough bytes in the buffer. Read it.
            byte[] bytes = new byte[length];
            buf.readBytes(bytes);

            // Successfully decoded a frame.
            // Return a TaskMessage object
            ret.add(new TaskMessage(code, bytes));
        }

        if (!ret.isEmpty()) {
            out.add(ret);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.error("Exception thrown while decoding messages in channel {}; exception: ", ctx.channel(), cause);
        ctx.close();
    }
}