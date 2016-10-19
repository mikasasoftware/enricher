package rb.ks.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.integration.utils.EmbeddedSingleNodeKafkaCluster;
import org.apache.kafka.streams.integration.utils.IntegrationTestUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import rb.ks.builder.Builder;
import rb.ks.builder.config.Config;
import rb.ks.serializers.JsonDeserializer;
import rb.ks.serializers.JsonSerde;
import rb.ks.serializers.JsonSerializer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

import static org.apache.kafka.streams.StreamsConfig.APPLICATION_ID_CONFIG;
import static org.junit.Assert.assertEquals;
import static rb.ks.builder.config.Config.ConfigProperties.BOOTSTRAPER_CLASSNAME;
import static rb.ks.enrichment.utils.Constants.__CLIENT_MAC;
import static rb.ks.enrichment.utils.Constants.__CLIENT_MAC_VENDOR;

public class MacVendorEnrichIntegrationTest {

    @ClassRule
    public static final EmbeddedSingleNodeKafkaCluster CLUSTER = new EmbeddedSingleNodeKafkaCluster();

    private static final int REPLICATION_FACTOR = 1;

    private static final String INPUT_STREAM_TOPIC = "input";

    private static final String BOOTSTRAP_TOPIC = "__enricher_bootstrap";

    private static final String OUTPUT_TOPIC = "output";

    private static Properties producerConfig = new Properties();

    private static Properties consumerConfig = new Properties();

    private static ObjectMapper mapper;

    @BeforeClass
    public static void startKafkaCluster() throws Exception {
        CLUSTER.createTopic(INPUT_STREAM_TOPIC, 2, REPLICATION_FACTOR);
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

        mapper = new ObjectMapper();
    }

    @Test
    public void macVendorEnrichShouldWork() throws Exception {
        Map<String, Object> streamsConfiguration = new HashMap<>();

        String appId = UUID.randomUUID().toString();
        streamsConfiguration.put(APPLICATION_ID_CONFIG, appId);
        streamsConfiguration.put(StreamsConfig.ZOOKEEPER_CONNECT_CONFIG, CLUSTER.zKConnectString());
        streamsConfiguration.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, CLUSTER.bootstrapServers());
        streamsConfiguration.put(StreamsConfig.KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        streamsConfiguration.put(StreamsConfig.VALUE_SERDE_CLASS_CONFIG, JsonSerde.class.getName());
        streamsConfiguration.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        streamsConfiguration.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, 1);

        Config configuration = new Config(streamsConfiguration);
        configuration.put(BOOTSTRAPER_CLASSNAME, "rb.ks.builder.bootstrap.KafkaBootstraper");

        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

        Map<String, Object> macVendorProperties = new HashMap<>();
        macVendorProperties.put("oui.file.path", classLoader.getSystemResource("mac_vendors").getPath());


        Map<String, Object> geoIpEnricher = new HashMap<>();
        geoIpEnricher.put("name", "macVendor");
        geoIpEnricher.put("className", "rb.ks.enrichment.MacVendorEnrich");
        geoIpEnricher.put("properties", macVendorProperties);

        Map<String, Object> queries = new HashMap<>();
        queries.put("query1", "SELECT * FROM STREAM input ENRICH WITH macVendor INSERT INTO TABLE output");

        Map<String, Object> jsonMap = new HashMap<>();
        jsonMap.put("enrichers", Collections.singletonList(geoIpEnricher));
        jsonMap.put("queries", queries);

        KeyValue<String, String> jsonConfigKv = new KeyValue<>(appId, mapper.writeValueAsString(jsonMap));

        producerConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        IntegrationTestUtils.produceKeyValuesSynchronously(BOOTSTRAP_TOPIC, Collections.singletonList(jsonConfigKv), producerConfig);

        Builder builder = new Builder(configuration);

        Map<String, Object> message1 = new HashMap<>();
        message1.put(__CLIENT_MAC, "00:1C:B3:09:85:15");

        KeyValue<String, Map<String, Object>> kvStream1 = new KeyValue<>("KEY_A", message1);

        producerConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        IntegrationTestUtils.produceKeyValuesSynchronously(INPUT_STREAM_TOPIC, Arrays.asList(kvStream1), producerConfig);

        List<KeyValue<String, Map>> receivedMessagesFromOutput = IntegrationTestUtils.waitUntilMinKeyValueRecordsReceived(consumerConfig, OUTPUT_TOPIC, 1);

        Map<String, Object> expectedMessage = new HashMap<>();
        expectedMessage.put(__CLIENT_MAC, "00:1C:B3:09:85:15");
        expectedMessage.put(__CLIENT_MAC_VENDOR, "Apple");

        KeyValue<String, Map> expectedKVMessage = new KeyValue<>("KEY_A",expectedMessage);

        assertEquals(Collections.singletonList(expectedKVMessage), receivedMessagesFromOutput);

        builder.close();
    }

    @AfterClass
    public static void stopKafkaCluster() {
        CLUSTER.stop();
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
