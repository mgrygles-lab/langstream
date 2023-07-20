package com.datastax.oss.sga.kafka;

import com.dastastax.oss.sga.kafka.runtime.KafkaTopic;
import com.datastax.oss.sga.api.model.Application;
import com.datastax.oss.sga.api.model.Connection;
import com.datastax.oss.sga.api.model.Module;
import com.datastax.oss.sga.api.model.TopicDefinition;
import com.datastax.oss.sga.api.runtime.ClusterRuntimeRegistry;
import com.datastax.oss.sga.api.runtime.ExecutionPlan;
import com.datastax.oss.sga.api.runtime.PluginsRegistry;
import com.datastax.oss.sga.impl.deploy.ApplicationDeployer;
import com.datastax.oss.sga.impl.k8s.tests.KubeTestServer;
import com.datastax.oss.sga.impl.parser.ModelBuilder;
import com.datastax.oss.sga.runtime.agent.AgentRunner;
import com.datastax.oss.sga.runtime.api.agent.AgentSpec;
import com.datastax.oss.sga.runtime.api.agent.CodeStorageConfig;
import com.datastax.oss.sga.runtime.api.agent.RuntimePodConfiguration;
import com.datastax.oss.sga.runtime.k8s.api.PodAgentConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.connect.connector.Task;
import org.apache.kafka.connect.sink.SinkConnector;
import org.apache.kafka.connect.sink.SinkRecord;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
class KafkaConnectRunnerTest {

    private static KafkaContainer kafkaContainer;
    private static AdminClient admin;

    @RegisterExtension
    static final KubeTestServer kubeServer = new KubeTestServer();


    @Test
    public void testRunKafkaConnectSink() throws Exception {
        String tenant = "tenant";
        kubeServer.spyAgentCustomResources(tenant, "app-step1");

        Application applicationInstance = ModelBuilder
                .buildApplicationInstance(Map.of("instance.yaml",
                        buildInstanceYaml(),
                        "module.yaml", """
                                module: "module-1"
                                id: "pipeline-1"
                                topics:
                                  - name: "input-topic"
                                    creation-mode: create-if-not-exists
                                pipeline:
                                  - name: "sink1"
                                    id: "step1"
                                    type: "sink"
                                    input: "input-topic"
                                    configuration:
                                      connector.class: %s                                        
                                      file: /tmp/test.sink.txt
                                """.formatted(DummySinkConnector.class.getName())));

        ApplicationDeployer deployer = ApplicationDeployer
                .builder()
                .registry(new ClusterRuntimeRegistry())
                .pluginsRegistry(new PluginsRegistry())
                .build();

        Module module = applicationInstance.getModule("module-1");

        ExecutionPlan implementation = deployer.createImplementation("app", applicationInstance);
        assertTrue(implementation.getConnectionImplementation(module,
                new Connection(TopicDefinition.fromName("input-topic"))) instanceof KafkaTopic);

        List<PodAgentConfiguration> customResourceDefinitions = (List<PodAgentConfiguration>) deployer.deploy(tenant, implementation, null);

        Set<String> topics = admin.listTopics().names().get();
        log.info("Topics {}", topics);
        assertTrue(topics.contains("input-topic"));

        log.info("CRDS: {}", customResourceDefinitions);

        assertEquals(1, customResourceDefinitions.size());
        PodAgentConfiguration podAgentConfiguration = customResourceDefinitions.get(0);

        RuntimePodConfiguration runtimePodConfiguration = new RuntimePodConfiguration(
                podAgentConfiguration.input(),
                podAgentConfiguration.output(),
                new AgentSpec(AgentSpec.ComponentType.valueOf(
                        podAgentConfiguration.agentConfiguration().componentType()),
                        tenant,
                        podAgentConfiguration.agentConfiguration().agentId(),
                        "application",
                        podAgentConfiguration.agentConfiguration().agentType(),
                        podAgentConfiguration.agentConfiguration().configuration()),
                applicationInstance.getInstance().streamingCluster(),
                new CodeStorageConfig("none", "none", Map.of())
        );

        try (KafkaProducer<String, String> producer = new KafkaProducer<String, String>(
                Map.of("bootstrap.servers", kafkaContainer.getBootstrapServers(),
                "key.serializer", "org.apache.kafka.common.serialization.StringSerializer",
                "value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
        )) {

            // produce one message to the input-topic
            producer
                .send(new ProducerRecord<>(
                    "input-topic",
                    null,
                    "key",
                    "{\"name\": \"some name\", \"description\": \"some description\"}",
                    List.of(new RecordHeader("header-key", "header-value".getBytes(StandardCharsets.UTF_8)))))
                .get();
            producer.flush();

            AgentRunner.run(runtimePodConfiguration, null, null, 5);

            Awaitility.await().untilAsserted(() -> {
                    DummySink.receivedRecords.forEach(r -> log.info("Received record: {}", r));
                    assertTrue(DummySink.receivedRecords.size() >= 1);
            });

        }

    }

    private static String buildInstanceYaml() {
        return """
                instance:
                  streamingCluster:
                    type: "kafka"
                    configuration:
                      admin:
                        bootstrap.servers: "%s"
                  computeCluster:
                     type: "kubernetes"
                """.formatted(kafkaContainer.getBootstrapServers());
    }


    @BeforeAll
    public static void setup() throws Exception {
        kafkaContainer = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"))
                .withLogConsumer(new Consumer<OutputFrame>() {
                    @Override
                    public void accept(OutputFrame outputFrame) {
                        log.info("kafka> {}", outputFrame.getUtf8String().trim());
                    }
                });
        // start Pulsar and wait for it to be ready to accept requests
        kafkaContainer.start();
        admin =
                AdminClient.create(Map.of("bootstrap.servers", kafkaContainer.getBootstrapServers()));
    }

    @AfterAll
    public static void teardown() {
        if (admin != null) {
            admin.close();
        }
        if (kafkaContainer != null) {
            kafkaContainer.close();
        }
    }


    public static final class DummySinkConnector extends SinkConnector {
        @Override
        public void start(Map<String, String> map) {
        }

        @Override
        public Class<? extends Task> taskClass() {
            return DummySink.class;
        }

        @Override
        public List<Map<String, String>> taskConfigs(int i) {
            return List.of(Map.of());
        }

        @Override
        public void stop() {
        }

        @Override
        public ConfigDef config() {
            return new ConfigDef();
        }

        @Override
        public String version() {
            return "1.0";
        }
    }

    public static final class DummySink extends org.apache.kafka.connect.sink.SinkTask {

        static final List<SinkRecord> receivedRecords = new CopyOnWriteArrayList<>();
        @Override
        public void start(Map<String, String> map) {
        }

        @Override
        public void put(Collection<SinkRecord> collection) {
            log.info("Sink records {}", collection);
            receivedRecords.addAll(collection);
        }

        @Override
        public void stop() {
        }

        @Override
        public String version() {
            return "1.0";
        }
    }
}