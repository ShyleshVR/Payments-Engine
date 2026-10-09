package com.shylesh.payment_service.saga;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shylesh.payment_service.event.Topics;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Stands in for ledger-service over real Kafka: consumes ledger-commands and replies on
 * ledger-replies. Replies can be paused (to exercise re-sends) and their outcome decided per
 * command (to exercise rejections).
 */
class StubLedger implements AutoCloseable {

    record Command(UUID commandId, String commandType, UUID sagaId, UUID paymentId) {
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final KafkaConsumer<String, String> consumer;
    private final KafkaProducer<String, String> producer;
    private final Thread thread;
    private final List<Command> commands = new CopyOnWriteArrayList<>();
    private volatile boolean running = true;
    private volatile boolean paused;
    /** command -> {outcome, reason}; default SUCCEEDED */
    private volatile Function<Command, String[]> decider = command -> new String[]{"SUCCEEDED", null};

    StubLedger(String bootstrapServers) {
        consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "stub-ledger",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
        consumer.subscribe(List.of(Topics.LEDGER_COMMANDS));
        thread = new Thread(this::run, "stub-ledger");
        thread.setDaemon(true);
        thread.start();
    }

    void pauseReplies() {
        paused = true;
    }

    void resumeReplies() {
        paused = false;
    }

    void decideWith(Function<Command, String[]> decider) {
        this.decider = decider;
    }

    void alwaysSucceed() {
        decideWith(command -> new String[]{"SUCCEEDED", null});
    }

    List<Command> commandsFor(UUID paymentId) {
        return commands.stream().filter(c -> c.paymentId().equals(paymentId)).toList();
    }

    /** Sends a reply as the ledger would (for duplicate / stale reply tests). */
    void reply(UUID paymentId, UUID sagaId, UUID commandId, String commandType, String outcome, String reason) {
        String data = "{\"commandId\":\"" + commandId + "\",\"commandType\":\"" + commandType + "\",\"sagaId\":\"" + sagaId
                + "\",\"paymentId\":\"" + paymentId + "\",\"outcome\":\"" + outcome + "\",\"reason\":"
                + (reason == null ? "null" : "\"" + reason + "\"") + ",\"transactionId\":\"" + UUID.randomUUID() + "\"}";
        String message = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"LEDGER_REPLY\",\"occurredAt\":\""
                + LocalDateTime.now() + "\",\"data\":" + data + "}";
        producer.send(new ProducerRecord<>(Topics.LEDGER_REPLIES, paymentId.toString(), message));
        producer.flush();
    }

    private void run() {
        while (running) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(100))) {
                try {
                    JsonNode data = objectMapper.readTree(record.value()).get("data");
                    Command command = new Command(UUID.fromString(data.get("commandId").asText()), data.get("commandType").asText(),
                            UUID.fromString(data.get("sagaId").asText()), UUID.fromString(data.get("paymentId").asText()));
                    commands.add(command);
                    if (!paused) {
                        String[] decision = decider.apply(command);
                        reply(command.paymentId(), command.sagaId(), command.commandId(), command.commandType(), decision[0], decision[1]);
                    }
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        consumer.close();
    }

    @Override
    public void close() {
        running = false;
        producer.close();
    }
}
