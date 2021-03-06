package io.wizzie.enricher.integration;

import io.wizzie.bootstrapper.builder.Config;
import io.wizzie.enricher.base.builder.config.ConfigProperties;
import io.wizzie.enricher.builder.Builder;
import io.wizzie.enricher.serializers.JsonDeserializer;
import io.wizzie.enricher.serializers.JsonSerde;
import io.wizzie.enricher.serializers.JsonSerializer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.integration.utils.EmbeddedKafkaCluster;
import org.apache.kafka.streams.integration.utils.IntegrationTestUtils;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

import static io.wizzie.bootstrapper.bootstrappers.impl.KafkaBootstrapper.BOOTSTRAP_TOPICS_CONFIG;
import static org.apache.kafka.streams.StreamsConfig.APPLICATION_ID_CONFIG;
import static org.junit.Assert.assertEquals;

public class KafkaBootstrapperStopIntegrationTest {
    private final static int NUM_BROKERS = 1;

    @ClassRule
    public static EmbeddedKafkaCluster CLUSTER = new EmbeddedKafkaCluster(NUM_BROKERS);

    private static final int REPLICATION_FACTOR = 1;

    private static final String INPUT_STREAM_TOPIC = "stream1";
    private static final String INPUT_TABLE_1_TOPIC = "table1";
    private static final String INPUT_TABLE_2_TOPIC = "table2";
    private static final String BOOTSTRAP_TOPIC = "__enricher_bootstrap";

    private static final String OUTPUT_TOPIC = "output";

    private static Properties producerConfig = new Properties();

    private static Properties consumerConfig = new Properties();

    @BeforeClass
    public static void startKafkaCluster() throws Exception {
        CLUSTER.createTopic(INPUT_STREAM_TOPIC, 2, REPLICATION_FACTOR);
        CLUSTER.createTopic(INPUT_TABLE_1_TOPIC, 2, REPLICATION_FACTOR);
        CLUSTER.createTopic(INPUT_TABLE_2_TOPIC, 2, REPLICATION_FACTOR);
        CLUSTER.createTopic(OUTPUT_TOPIC, 2, REPLICATION_FACTOR);

        CLUSTER.createTopic(BOOTSTRAP_TOPIC, 1, REPLICATION_FACTOR);


        producerConfig.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, CLUSTER.bootstrapServers());
        producerConfig.put(ProducerConfig.ACKS_CONFIG, "all");
        producerConfig.put(ProducerConfig.RETRIES_CONFIG, 0);
        producerConfig.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, Serdes.String().serializer().getClass());
        producerConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);


        consumerConfig.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, CLUSTER.bootstrapServers());
        consumerConfig.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer");
        consumerConfig.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerConfig.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        consumerConfig.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    }

    @Test (expected = java.lang.AssertionError.class)
    public void kafkaBootstrapperShouldWork() throws Exception {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        File file = new File(classLoader.getResource("kafka-bootstrapper-integration-test-1.json").getFile());

        Map<String, Object> streamsConfiguration = new HashMap<>();

        String appId = UUID.randomUUID().toString();
        streamsConfiguration.put(APPLICATION_ID_CONFIG, appId);
        streamsConfiguration.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, CLUSTER.bootstrapServers());
        streamsConfiguration.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        streamsConfiguration.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, JsonSerde.class.getName());
        streamsConfiguration.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        streamsConfiguration.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, 1);

        Config configuration = new Config(streamsConfiguration);
        configuration.put(ConfigProperties.BOOTSTRAPPER_CLASSNAME, "io.wizzie.bootstrapper.bootstrappers.impl.KafkaBootstrapper");
        configuration.put(BOOTSTRAP_TOPICS_CONFIG, Arrays.asList(BOOTSTRAP_TOPIC));

        String jsonConfig = getFileContent(file);

        KeyValue<String, String> jsonConfigKv = new KeyValue<>(appId, jsonConfig);

        producerConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        IntegrationTestUtils.produceKeyValuesSynchronously(BOOTSTRAP_TOPIC, Collections.singletonList(jsonConfigKv), producerConfig, CLUSTER.time);

        consumerConfig.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        List<KeyValue<String, String>> receivedMessagesFromConfig = IntegrationTestUtils.waitUntilMinValuesRecordsReceived(consumerConfig, BOOTSTRAP_TOPIC, 1);

        assertEquals(Collections.singletonList(jsonConfig), receivedMessagesFromConfig);

        Builder builder = new Builder(configuration);

        Map<String, Object> message1 = new HashMap<>();
        message1.put("a", 1);
        message1.put("b", 2);

        KeyValue<String, Map<String, Object>> kvStream1 = new KeyValue<>("KEY_A", message1);

        Map<String, Object> message2 = new HashMap<>();
        message2.put("c", 3);

        KeyValue<String, Map<String, Object>> kvStream2 = new KeyValue<>("KEY_A", message2);

        Map<String, Object> message3 = new HashMap<>();
        message3.put("d", 4);

        KeyValue<String, Map<String, Object>> kvStream3 = new KeyValue<>("KEY_A", message3);

        producerConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        IntegrationTestUtils.produceKeyValuesSynchronously(INPUT_TABLE_1_TOPIC, Arrays.asList(kvStream2), producerConfig, CLUSTER.time);

        IntegrationTestUtils.produceKeyValuesSynchronously(INPUT_TABLE_2_TOPIC, Arrays.asList(kvStream3), producerConfig, CLUSTER.time);

        IntegrationTestUtils.produceKeyValuesSynchronously(INPUT_STREAM_TOPIC, Arrays.asList(kvStream1), producerConfig, CLUSTER.time);

        Map<String, Object> expectedData = new HashMap<>();
        expectedData.put("a", 1);
        expectedData.put("b", 2);
        expectedData.put("c", 3);
        expectedData.put("d", 4);

        KeyValue<String, Map<String, Object>> expectedDataKv = new KeyValue<>("KEY_A", expectedData);

        consumerConfig.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);

        List<KeyValue<String, Map>> receivedMessagesFromOutput = IntegrationTestUtils.waitUntilMinKeyValueRecordsReceived(consumerConfig, OUTPUT_TOPIC, 1);

        assertEquals(Collections.singletonList(expectedDataKv), receivedMessagesFromOutput);

        //Send null to stop processing
        jsonConfigKv = new KeyValue<>(appId, null);

        producerConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        consumerConfig.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        IntegrationTestUtils.produceKeyValuesSynchronously(BOOTSTRAP_TOPIC, Collections.singletonList(jsonConfigKv), producerConfig, CLUSTER.time);

        boolean close = false;
        //Wait for close
        for (int i = 0; i < 10; i++) {
            if (builder.streams == null) {
                close = true;
                break;
            } else {
                Thread.sleep(1000);
            }
        }

        if (!close) {
            throw new RuntimeException("Should close.");
        }

        producerConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        IntegrationTestUtils.produceKeyValuesSynchronously(INPUT_TABLE_1_TOPIC, Arrays.asList(kvStream2), producerConfig, CLUSTER.time);
        consumerConfig.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        IntegrationTestUtils.waitUntilMinKeyValueRecordsReceived(consumerConfig, OUTPUT_TOPIC, 1);
    }

    @Test
    public void kafkaBootstrapperShouldWorkAfterClose() throws Exception {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        File file = new File(classLoader.getResource("kafka-bootstrapper-integration-test-1.json").getFile());

        Map<String, Object> streamsConfiguration = new HashMap<>();

        String appId = UUID.randomUUID().toString();
        streamsConfiguration.put(APPLICATION_ID_CONFIG, appId);
        streamsConfiguration.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, CLUSTER.bootstrapServers());
        streamsConfiguration.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        streamsConfiguration.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, JsonSerde.class.getName());
        streamsConfiguration.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        streamsConfiguration.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, 1);

        Config configuration = new Config(streamsConfiguration);
        configuration.put(ConfigProperties.BOOTSTRAPPER_CLASSNAME, "io.wizzie.bootstrapper.bootstrappers.impl.KafkaBootstrapper");
        configuration.put(BOOTSTRAP_TOPICS_CONFIG, Arrays.asList(BOOTSTRAP_TOPIC));

        String jsonConfig = getFileContent(file);

        KeyValue<String, String> jsonConfigKv = new KeyValue<>(appId, jsonConfig);

        producerConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        IntegrationTestUtils.produceKeyValuesSynchronously(BOOTSTRAP_TOPIC, Collections.singletonList(jsonConfigKv), producerConfig, CLUSTER.time);

        consumerConfig.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        List<KeyValue<String, String>> receivedMessagesFromConfig = IntegrationTestUtils.waitUntilMinValuesRecordsReceived(consumerConfig, BOOTSTRAP_TOPIC, 1);

        assertEquals(Collections.singletonList(jsonConfig), receivedMessagesFromConfig);

        Builder builder = new Builder(configuration);

        Map<String, Object> message1 = new HashMap<>();
        message1.put("a", 1);
        message1.put("b", 2);

        KeyValue<String, Map<String, Object>> kvStream1 = new KeyValue<>("KEY_A", message1);

        Map<String, Object> message2 = new HashMap<>();
        message2.put("c", 3);

        KeyValue<String, Map<String, Object>> kvStream2 = new KeyValue<>("KEY_A", message2);

        Map<String, Object> message3 = new HashMap<>();
        message3.put("d", 4);

        KeyValue<String, Map<String, Object>> kvStream3 = new KeyValue<>("KEY_A", message3);

        producerConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        IntegrationTestUtils.produceKeyValuesSynchronously(INPUT_TABLE_1_TOPIC, Arrays.asList(kvStream2), producerConfig, CLUSTER.time);

        IntegrationTestUtils.produceKeyValuesSynchronously(INPUT_TABLE_2_TOPIC, Arrays.asList(kvStream3), producerConfig, CLUSTER.time);

        IntegrationTestUtils.produceKeyValuesSynchronously(INPUT_STREAM_TOPIC, Arrays.asList(kvStream1), producerConfig, CLUSTER.time);

        Map<String, Object> expectedData = new HashMap<>();
        expectedData.put("a", 1);
        expectedData.put("b", 2);
        expectedData.put("c", 3);
        expectedData.put("d", 4);

        KeyValue<String, Map<String, Object>> expectedDataKv = new KeyValue<>("KEY_A", expectedData);

        consumerConfig.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);

        List<KeyValue<String, Map>> receivedMessagesFromOutput = IntegrationTestUtils.waitUntilMinKeyValueRecordsReceived(consumerConfig, OUTPUT_TOPIC, 1);

        assertEquals(Collections.singletonList(expectedDataKv), receivedMessagesFromOutput);

        //Send null to stop processing
        jsonConfigKv = new KeyValue<>(appId, null);

        producerConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        consumerConfig.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        IntegrationTestUtils.produceKeyValuesSynchronously(BOOTSTRAP_TOPIC, Collections.singletonList(jsonConfigKv), producerConfig, CLUSTER.time);

        boolean close = false;
        //Wait for close
        for (int i = 0; i < 10; i++) {
            if (builder.streams == null) {
                close = true;
                break;
            } else {
                Thread.sleep(1000);
            }
        }

        if (!close) {
            throw new RuntimeException("Should close.");
        }

        jsonConfigKv = new KeyValue<>(appId, jsonConfig);

        producerConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        IntegrationTestUtils.produceKeyValuesSynchronously(BOOTSTRAP_TOPIC, Collections.singletonList(jsonConfigKv), producerConfig, CLUSTER.time);

        producerConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        IntegrationTestUtils.produceKeyValuesSynchronously(INPUT_TABLE_1_TOPIC, Arrays.asList(kvStream2), producerConfig, CLUSTER.time);

        IntegrationTestUtils.produceKeyValuesSynchronously(INPUT_TABLE_2_TOPIC, Arrays.asList(kvStream3), producerConfig, CLUSTER.time);

        IntegrationTestUtils.produceKeyValuesSynchronously(INPUT_STREAM_TOPIC, Arrays.asList(kvStream1), producerConfig, CLUSTER.time);

        expectedData = new HashMap<>();
        expectedData.put("a", 1);
        expectedData.put("b", 2);
        expectedData.put("c", 3);
        expectedData.put("d", 4);

        expectedDataKv = new KeyValue<>("KEY_A", expectedData);

        consumerConfig.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);

        receivedMessagesFromOutput = IntegrationTestUtils.waitUntilMinKeyValueRecordsReceived(consumerConfig, OUTPUT_TOPIC, 1);

        assertEquals(Collections.singletonList(expectedDataKv), receivedMessagesFromOutput);
    }

    public static String getFileContent(File file) throws IOException {
        BufferedReader bufferedReader = new BufferedReader(new FileReader(file));

        StringBuilder stringBuffer = new StringBuilder();

        String line;

        while ((line = bufferedReader.readLine()) != null) {

            stringBuffer.append(line).append("\n");
        }

        return stringBuffer.toString();
    }

}
