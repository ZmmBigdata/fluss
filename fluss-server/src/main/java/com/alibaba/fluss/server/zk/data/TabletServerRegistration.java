/*
 * Copyright (c) 2024 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.fluss.server.zk.data;

import java.util.Objects;

/**
 * The register information of tablet server stored in {@link ZkData.ServerIdZNode}.
 *
 * @see TabletServerRegistrationJsonSerde for json serialization and deserialization.
 */
public class TabletServerRegistration {
    private final String host;
    private final int port;
    private final long registerTimestamp;

    public TabletServerRegistration(String host, int port, long registerTimestamp) {
        this.host = host;
        this.port = port;
        this.registerTimestamp = registerTimestamp;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public long getRegisterTimestamp() {
        return registerTimestamp;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TabletServerRegistration that = (TabletServerRegistration) o;
        return port == that.port
                && registerTimestamp == that.registerTimestamp
                && Objects.equals(host, that.host);
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, port, registerTimestamp);
    }

    @Override
    public String toString() {
        return "TabletServerRegistration{"
                + "host='"
                + host
                + '\''
                + ", port="
                + port
                + ", registerTimestamp="
                + registerTimestamp
                + '}';
    }
}