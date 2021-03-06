/*
 * Copyright 2019 LinkedIn Corp. Licensed under the BSD 2-Clause License (the "License").  See License in the project root for license information.
 */

package com.linkedin.kafka.clients.consumer;

import com.linkedin.kafka.clients.common.ClusterDescriptor;
import com.linkedin.kafka.clients.metadataservice.MetadataServiceClient;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;

import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.mockito.Mockito;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.mockito.Mockito.*;
import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertNotNull;
import static org.testng.AssertJUnit.assertNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * The unit test for federated consumer.
 */
public class LiKafkaFederatedConsumerImplTest {
  private static final Logger LOG = LoggerFactory.getLogger(LiKafkaFederatedConsumerImplTest.class);

  private static final UUID CLIENT_ID = new UUID(0, 0);
  private static final String TOPIC1 = "topic1";
  private static final String TOPIC2 = "topic2";
  private static final String TOPIC3 = "topic3";
  private static final TopicPartition TOPIC_PARTITION1 = new TopicPartition(TOPIC1, 0);
  private static final TopicPartition TOPIC_PARTITION2 = new TopicPartition(TOPIC2, 0);
  private static final TopicPartition TOPIC_PARTITION3 = new TopicPartition(TOPIC3, 0);
  private static final ClusterDescriptor CLUSTER1 = new ClusterDescriptor("cluster1", "url1", "zk1");
  private static final ClusterDescriptor CLUSTER2 = new ClusterDescriptor("cluster2", "url2", "zk2");

  private MetadataServiceClient _mdsClient;
  private LiKafkaFederatedConsumerImpl<byte[], byte[]> _federatedConsumer;

  private class MockConsumerBuilder extends LiKafkaConsumerBuilder<byte[], byte[]> {
    OffsetResetStrategy _offsetResetStrategy = OffsetResetStrategy.EARLIEST;

    public MockConsumerBuilder setOffsetResetStrategy(OffsetResetStrategy offsetResetStrategy) {
      _offsetResetStrategy = offsetResetStrategy;
      return this;
    }

    @Override
    public LiKafkaConsumer<byte[], byte[]> build() {
      return new MockLiKafkaConsumer(_offsetResetStrategy);
    }
  }

  @BeforeMethod
  public void setup() {
    _mdsClient = Mockito.mock(MetadataServiceClient.class);
    when(_mdsClient.registerFederatedClient(anyObject(), anyObject(), anyInt())).thenReturn(CLIENT_ID);

    Map<String, String> consumerConfig = new HashMap<>();
    consumerConfig.put(LiKafkaConsumerConfig.CLUSTER_ENVIRONMENT_CONFIG, "env");
    consumerConfig.put(LiKafkaConsumerConfig.CLUSTER_GROUP_CONFIG, "group");
    consumerConfig.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.ByteArrayDeserializer");
    consumerConfig.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.ByteArrayDeserializer");

    _federatedConsumer = new LiKafkaFederatedConsumerImpl<>(consumerConfig, _mdsClient, new MockConsumerBuilder());
  }

  @Test
  public void testBasicWorkflow() {
    // Set expectations so that topics 1 and 3 are hosted in cluster 1 and topic 2 in cluster 2.
    Collection<TopicPartition> expectedTopicPartitions = Arrays.asList(TOPIC_PARTITION1, TOPIC_PARTITION2,
        TOPIC_PARTITION3);
    Map<TopicPartition, ClusterDescriptor> topicPartitionsToClusterMapToReturn =
        new HashMap<TopicPartition, ClusterDescriptor>() {{
          put(TOPIC_PARTITION1, CLUSTER1);
          put(TOPIC_PARTITION2, CLUSTER2);
          put(TOPIC_PARTITION3, CLUSTER1);
    }};
    when(_mdsClient.getClustersForTopicPartitions(eq(CLIENT_ID), eq(expectedTopicPartitions), anyInt()))
        .thenReturn(topicPartitionsToClusterMapToReturn);

    // Make sure we start with a clean slate
    assertNull("Consumer for cluster 1 should have not been created yet",
        _federatedConsumer.getPerClusterConsumer(CLUSTER1));
    assertNull("Consumer for cluster 2 should have not been created yet",
        _federatedConsumer.getPerClusterConsumer(CLUSTER2));

    // Assign topic partitions from all three topics
    _federatedConsumer.assign(Arrays.asList(TOPIC_PARTITION1, TOPIC_PARTITION2, TOPIC_PARTITION3));

    // Verify consumers for both clusters have been created.
    MockConsumer consumer1 = ((MockLiKafkaConsumer) _federatedConsumer.getPerClusterConsumer(CLUSTER1)).getDelegate();
    MockConsumer consumer2 = ((MockLiKafkaConsumer) _federatedConsumer.getPerClusterConsumer(CLUSTER2)).getDelegate();
    assertNotNull("Consumer for cluster 1 should have been created", consumer1);
    assertNotNull("Consumer for cluster 2 should have been created", consumer2);

    // Verify if assignment() returns all topic partitions.
    assertEquals("assignment() should return all topic partitions",
        new HashSet<TopicPartition>(expectedTopicPartitions), _federatedConsumer.assignment());
  }

  private boolean isError(Future<?> future) {
    try {
      future.get();
      return false;
    } catch (Exception e) {
      return true;
    }
  }
}
