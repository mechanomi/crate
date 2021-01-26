/*
 * This file is part of a module with proprietary Enterprise Features.
 *
 * Licensed to Crate.io Inc. ("Crate.io") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 *
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 *
 * To use this file, Crate.io must have given you permission to enable and
 * use such Enterprise Features and you must have a valid Enterprise or
 * Subscription Agreement with Crate.io.  If you enable or use the Enterprise
 * Features, you represent and warrant that you have a valid Enterprise or
 * Subscription Agreement with Crate.io.  Your use of the Enterprise Features
 * if governed by the terms and conditions of your Enterprise or Subscription
 * Agreement with Crate.io.
 */

package io.crate.beans;

import com.carrotsearch.randomizedtesting.RandomizedRunner;
import com.carrotsearch.randomizedtesting.annotations.ThreadLeakScope;
import io.crate.common.collections.Tuple;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodeRole;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.IndexRoutingTable;
import org.elasticsearch.cluster.routing.RoutingTable;
import org.elasticsearch.cluster.routing.ShardRoutingState;
import org.elasticsearch.cluster.routing.TestShardRouting;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.shard.IndexShardState;
import org.elasticsearch.index.shard.ShardId;

import org.hamcrest.Matcher;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;


import java.util.Map;
import java.util.UUID;

import static io.crate.testing.MoreMatchers.withFeature;
import static org.elasticsearch.test.ESTestCase.buildNewFakeTransportAddress;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

@RunWith(RandomizedRunner.class)
@ThreadLeakScope(ThreadLeakScope.Scope.NONE)
public class NodeInfoTest {

    ClusterState.Builder clusterState;

    @Before
    public void setup() {
        var tableName = "test";
        var indexRoutingTableBuilder = IndexRoutingTable
            .builder(new Index(tableName, UUID.randomUUID().toString()))
            .addShard(TestShardRouting.newShardRouting(tableName,
                                                       1,
                                                       "node_1",
                                                       true,
                                                       ShardRoutingState.STARTED))
            .addShard(TestShardRouting.newShardRouting(tableName,
                                                       2,
                                                       "node_1",
                                                       false,
                                                       ShardRoutingState.STARTED))
            .addShard(TestShardRouting.newShardRouting(tableName,
                                                       3,
                                                       "node_1",
                                                       false,
                                                       ShardRoutingState.STARTED))
            .addShard(TestShardRouting.newShardRouting(tableName,
                                                       4,
                                                       null,
                                                       false,
                                                       ShardRoutingState.UNASSIGNED));

        var routingTable = RoutingTable.builder().add(indexRoutingTableBuilder).build();
        var meta = IndexMetadata.builder(tableName).numberOfShards(1).numberOfReplicas(2);
        this.clusterState = ClusterState.builder(new ClusterName("crate")).version(1L).routingTable(routingTable)
            .metadata(Metadata.builder().put(meta));
    }

    @Test
    public void test_local_node_is_master_all_shards_locally() {
        var nodes = DiscoveryNodes
            .builder()
            .add(discoveryNode("node_1"))
            .masterNodeId("node_1")
            .localNodeId("node_1")
            .build();

        var nodeInfo = new NodeInfo(() -> clusterState.nodes(nodes).build(), this::shardStateAndSizeProvider);

        assertThat(nodeInfo.getNodeId(), is("node_1"));
        assertThat(nodeInfo.getNodeName(), is("node_1"));

        assertThat(nodeInfo.getClusterStateVersion(), is(1L));
        ShardStats shardStats = nodeInfo.getShardStats();
        assertThat(shardStats.getPrimaries(), is(1));
        assertThat(shardStats.getTotal(), is(3));
        assertThat(shardStats.getReplicas(), is(2));
        // Unassigned shards are counted on the master node
        assertThat(shardStats.getUnassigned(), is(1));

        assertThat(nodeInfo.getShardInfo(),
                   containsInAnyOrder(
                       isShardInfo(1, "test", "", "STARTED", "STARTED", 100),
                       isShardInfo(2, "test", "", "STARTED", "STARTED", 100),
                       isShardInfo(3, "test", "", "STARTED", "STARTED", 100)
                   )
        );
    }

    @Test
    public void test_local_node_is_data_node_no_shards_locally() {
        var nodes = DiscoveryNodes
            .builder()
            .add(discoveryNode("node_1"))
            .add(discoveryNode("node_2"))
            .masterNodeId("node_1")
            .localNodeId("node_2")
            .build();

        var nodeInfo = new NodeInfo(() -> clusterState.nodes(nodes).build(), this::shardStateAndSizeProvider);

        assertThat(nodeInfo.getNodeId(), is("node_2"));
        assertThat(nodeInfo.getNodeName(), is("node_2"));
        var shardStats = nodeInfo.getShardStats();
        assertThat(shardStats.getPrimaries(), is(0));
        assertThat(shardStats.getTotal(), is(0));
        assertThat(shardStats.getReplicas(), is(0));
        assertThat(shardStats.getUnassigned(), is(0));
    }

    @Test
    public void test_local_node_is_master_node_no_shards_locally() {
        var nodes = DiscoveryNodes
            .builder()
            .add(discoveryNode("node_1"))
            .add(discoveryNode("node_2"))
            .masterNodeId("node_2")
            .localNodeId("node_2")
            .build();

        var nodeInfo = new NodeInfo(() -> clusterState.nodes(nodes).build(), this::shardStateAndSizeProvider);

        assertThat(nodeInfo.getNodeId(), is("node_2"));
        assertThat(nodeInfo.getNodeName(), is("node_2"));
        var shardStats = nodeInfo.getShardStats();
        assertThat(shardStats.getPrimaries(), is(0));
        assertThat(shardStats.getTotal(), is(0));
        assertThat(shardStats.getReplicas(), is(0));
        // Unassigned shards are only counted on the master node
        assertThat(shardStats.getUnassigned(), is(1));

        assertThat(nodeInfo.getShardInfo().isEmpty(), is(true));
    }

    @Test
    public void test_local_node_is_data_node_all_shards_locally() {
        var nodes = DiscoveryNodes
            .builder()
            .add(discoveryNode("node_1"))
            .add(discoveryNode("node_2"))
            .masterNodeId("node_2")
            .localNodeId("node_1")
            .build();

        var nodeInfo = new NodeInfo(() -> clusterState.nodes(nodes).build(), this::shardStateAndSizeProvider);
        var shardStats = nodeInfo.getShardStats();
        assertThat(shardStats.getPrimaries(), is(1));
        assertThat(shardStats.getTotal(), is(3));
        assertThat(shardStats.getReplicas(), is(2));
        // Unassigned shards are not counted on a data node
        assertThat(shardStats.getUnassigned(), is(0));

        assertThat(nodeInfo.getShardInfo(),
                   containsInAnyOrder(
                       isShardInfo(1, "test", "", "STARTED", "STARTED", 100),
                       isShardInfo(2, "test", "", "STARTED", "STARTED", 100),
                       isShardInfo(3, "test", "", "STARTED", "STARTED", 100)
                   )
        );
    }

    @Test
    public void test_partitioned_tables() {
        var tableName = ".partitioned.test.p1";
        var indexRoutingTableBuilder = IndexRoutingTable
            .builder(new Index(tableName, UUID.randomUUID().toString()))
            .addShard(TestShardRouting.newShardRouting(tableName,
                                                       1,
                                                       "node_1",
                                                       true,
                                                       ShardRoutingState.STARTED))
            .addShard(TestShardRouting.newShardRouting(tableName,
                                                       2,
                                                       "node_1",
                                                       false,
                                                       ShardRoutingState.STARTED))
            .addShard(TestShardRouting.newShardRouting(tableName,
                                                       3,
                                                       "node_1",
                                                       false,
                                                       ShardRoutingState.STARTED));


        var routingTable = RoutingTable.builder().add(indexRoutingTableBuilder).build();
        var meta = IndexMetadata.builder(tableName).numberOfShards(1).numberOfReplicas(2);
        var cs = ClusterState.builder(new ClusterName("crate")).version(1L).routingTable(routingTable)
            .metadata(Metadata.builder().put(meta));

        var nodes = DiscoveryNodes
            .builder()
            .add(discoveryNode("node_1"))
            .localNodeId("node_1")
            .masterNodeId("node_1")
            .build();

        var nodeInfo = new NodeInfo(() -> cs.nodes(nodes).build(), this::shardStateAndSizeProvider);
        var shardStats = nodeInfo.getShardStats();
        assertThat(shardStats.getPrimaries(), is(1));
        assertThat(shardStats.getTotal(), is(3));
        assertThat(shardStats.getReplicas(), is(2));

        assertThat(nodeInfo.getShardInfo(),
                   containsInAnyOrder(
                       isShardInfo(1, "test", "p1", "STARTED", "STARTED", 100),
                       isShardInfo(2, "test", "p1", "STARTED", "STARTED", 100),
                       isShardInfo(3, "test", "p1", "STARTED", "STARTED", 100)
                   )
        );

    }

    Tuple<IndexShardState, Long> shardStateAndSizeProvider(ShardId shardId) {
        return new Tuple<>(IndexShardState.STARTED, 100L);
    }

    DiscoveryNode discoveryNode(String id) {
        return new DiscoveryNode(id,
                                 id,
                                 buildNewFakeTransportAddress(),
                                 Map.of(),
                                 DiscoveryNodeRole.ROLES,
                                 Version.CURRENT);
    }

    Matcher<ShardInfo> isShardInfo(int shardId, String table, String partitionIdent, String routingState, String state, long size) {
        return allOf(
            instanceOf(ShardInfo.class),
            withFeature(x -> x.shardId, "", is(shardId)),
            withFeature(x -> x.table, "", is(table)),
            withFeature(x -> x.routingState, "", is(routingState)),
            withFeature(x -> x.state, "", is(state)),
            withFeature(x -> x.partitionIdent, "", is(partitionIdent)),
            withFeature(x -> x.size, "", is(size))
        );
    }
}
