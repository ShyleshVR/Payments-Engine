package com.shylesh.payout_service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
 * payout-ledger-replies. The outcome can be decided per command (to exercise rejections).
 */
class StubLedger implements AutoCloseable {

    record Command(UUID commandId, String commandType, UUID sagaId, UUID payoutId, String cutoff) {
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final KafkaConsumer<String, String> consumer;
    private final KafkaProducer<String, String> producer;
    private final List<Command> commands = new CopyOnWriteArrayList<>();
    private volatile boolean running = true;
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
        consumer.subscribe(List.of("ledger-commands"));
        Thread thread = new Thread(this::run, "stub-ledger");
        thread.setDaemon(true);
        thread.start();
    }

    void decideWith(Function<Command, String[]> decider) {
        this.decider = decider;
    }

    void alwaysSucceed() {
        decideWith(command -> new String[]{"SUCCEEDED", null});
    }

    List<String> commandTypesFor(UUID payoutId) {
        return commands.stream().filter(c -> c.payoutId().equals(payoutId)).map(Command::commandType).toList();
    }

    List<Command> commandsFor(UUID payoutId) {
        return commands.stream().filter(c -> c.payoutId().equals(payoutId)).toList();
    }

    private void run() {
        while (running) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(100))) {
                try {
                    JsonNode data = objectMapper.readTree(record.value()).get("data");
                    Command command = new Command(UUID.fromString(data.get("commandId").asText()), data.get("commandType").asText(),
                            UUID.fromString(data.get("sagaId").asText()), UUID.fromString(data.get("payoutId").asText()),
                            data.hasNonNull("cutoff") ? data.get("cutoff").asText() : null);
                    commands.add(command);
                    String[] decision = decider.apply(command);
                    String reply = "{\"commandId\":\"" + command.commandId() + "\",\"commandType\":\"" + command.commandType()
                            + "\",\"sagaId\":\"" + command.sagaId() + "\",\"paymentId\":null,\"payoutId\":\"" + command.payoutId()
                            + "\",\"outcome\":\"" + decision[0] + "\",\"reason\":" + (decision[1] == null ? "null" : "\"" + decision[1] + "\"")
                            + ",\"transactionId\":\"" + UUID.randomUUID() + "\"}";
                    producer.send(new ProducerRecord<>("payout-ledger-replies", command.payoutId().toString(),
                            "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"LEDGER_REPLY\",\"occurredAt\":\""
                                    + LocalDateTime.now() + "\",\"data\":" + reply + "}"));
                    producer.flush();
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
