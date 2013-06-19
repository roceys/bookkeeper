/**
 *
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
 *
 */
package org.apache.bookkeeper.middleware;

import org.jboss.netty.buffer.ChannelBuffer;

public class Responses {

    public static interface Response {
        public int getErrorCode();

        public void setErrorCode(int errCode);

        public String getAttribute(String attr);

        public void setAttribute(String attr, String value);
    }

    public static interface LedgerResponse extends Response {
        public long getLedgerId();

        public void setLedgerId(long ledgerId);

        public long getEntryId();

        public void setEntryId(long entryId);
    }

    public static interface ReadResponse extends LedgerResponse {
        public boolean hasData();

        public ChannelBuffer getDataAsChannelBuffer();

        public void setData(ChannelBuffer data);
    }

    public static interface AddResponse extends LedgerResponse {
    }
}
