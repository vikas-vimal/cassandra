/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.distributed.test;

import java.time.Duration;

import org.junit.Test;

import org.apache.cassandra.distributed.Cluster;
import org.apache.cassandra.distributed.api.ConsistencyLevel;
import org.apache.cassandra.distributed.api.Feature;
import org.apache.cassandra.schema.Schema;
import org.awaitility.Awaitility;

import static org.junit.Assert.assertTrue;

public class SchemaTest extends TestBaseImpl
{
    @Test
    public void readRepair() throws Throwable
    {
        try (Cluster cluster = init(Cluster.build(2).start()))
        {
            cluster.schemaChange("CREATE TABLE " + KEYSPACE + ".tbl (pk int, ck int, v1 int, v2 int,  primary key (pk, ck))");
            String name = "aaa";
            cluster.get(1).schemaChangeInternal("ALTER TABLE " + KEYSPACE + ".tbl ADD " + name + " list<int>");
            cluster.get(1).executeInternal("INSERT INTO " + KEYSPACE + ".tbl (pk, ck, v1, v2) values (?,1,1,1)", 1);
            selectSilent(cluster, name);

            cluster.get(2).flush(KEYSPACE);
            cluster.get(2).schemaChangeInternal("ALTER TABLE " + KEYSPACE + ".tbl ADD " + name + " list<int>");
            cluster.get(2).shutdown().get();
            cluster.get(2).startup();
            cluster.get(2).forceCompact(KEYSPACE, "tbl");
        }
    }

    @Test
    public void readRepairWithCompaction() throws Throwable
    {
        try (Cluster cluster = init(Cluster.build(2).start()))
        {
            cluster.schemaChange("CREATE TABLE " + KEYSPACE + ".tbl (pk int, ck int, v1 int, v2 int,  primary key (pk, ck))");
            String name = "v10";
            cluster.get(1).schemaChangeInternal("ALTER TABLE " + KEYSPACE + ".tbl ADD " + name + " list<int>");
            cluster.get(1).executeInternal("INSERT INTO " + KEYSPACE + ".tbl (pk, ck, v1, v2) values (?,1,1,1)", 1);
            selectSilent(cluster, name);
            cluster.get(2).flush(KEYSPACE);
            cluster.get(2).schemaChangeInternal("ALTER TABLE " + KEYSPACE + ".tbl ADD " + name + " list<int>");
            cluster.get(2).executeInternal("INSERT INTO " + KEYSPACE + ".tbl (pk, ck, v1, v2, " + name + ") values (?,1,1,1,[1])", 1);
            cluster.get(2).flush(KEYSPACE);
            cluster.get(2).forceCompact(KEYSPACE, "tbl");
            cluster.get(2).shutdown().get();
            cluster.get(2).startup();
            cluster.get(2).forceCompact(KEYSPACE, "tbl");
        }
    }

    private void selectSilent(Cluster cluster, String name)
    {
        try
        {
            cluster.coordinator(1).execute(withKeyspace("SELECT * FROM %s.tbl WHERE pk = ?"), ConsistencyLevel.ALL, 1);
        }
        catch (Exception e)
        {
            boolean causeIsUnknownColumn = false;
            Throwable cause = e;
            while (cause != null)
            {
                if (cause.getMessage() != null && cause.getMessage().contains("Unknown column "+name+" during deserialization"))
                    causeIsUnknownColumn = true;
                cause = cause.getCause();
            }
            assertTrue(causeIsUnknownColumn);
        }
    }

    @Test
    public void schemaReset() throws Throwable
    {
        try (Cluster cluster = init(Cluster.build(2).withConfig(cfg -> cfg.with(Feature.GOSSIP, Feature.NETWORK)).start()))
        {
            cluster.schemaChange("CREATE TABLE " + KEYSPACE + ".tbl (pk INT PRIMARY KEY, v TEXT)");

            assertTrue(cluster.get(1).callOnInstance(() -> Schema.instance.getTableMetadata(KEYSPACE, "tbl") != null));
            assertTrue(cluster.get(2).callOnInstance(() -> Schema.instance.getTableMetadata(KEYSPACE, "tbl") != null));

            cluster.get(2).shutdown().get();

            // when schema is removed and there is no other node to fetch it from, node 1 should be left with clean schema
            cluster.get(1).runOnInstance(() -> Schema.instance.resetLocalSchema());
            assertTrue(cluster.get(1).callOnInstance(() -> Schema.instance.getTableMetadata(KEYSPACE, "tbl") == null));

            // when the other node is started, schema should be back in sync
            cluster.get(2).startup();
            Awaitility.waitAtMost(Duration.ofMinutes(1))
                      .pollDelay(Duration.ofSeconds(1))
                      .until(() -> cluster.get(1).callOnInstance(() -> Schema.instance.getTableMetadata(KEYSPACE, "tbl") != null));

            // when schema is removed and there is a node to fetch it from, node 1 should immediatelly restore the schema
            cluster.get(1).runOnInstance(() -> Schema.instance.resetLocalSchema());
            assertTrue(cluster.get(1).callOnInstance(() -> Schema.instance.getTableMetadata(KEYSPACE, "tbl") != null));
        }
    }

}
