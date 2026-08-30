package com.ticketwaitingroom.ticketissuance.ticket;

import com.ticketwaitingroom.common.events.PurchaseConfirmedEvent;
import com.ticketwaitingroom.common.events.TicketIssuanceMessage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link TicketIssuanceMessage} to the state machine definition that produces it.
 *
 * <p>The producer of these messages is a JSON document deployed to Step Functions, which no
 * compiler reads. Rename a field on either side and nothing breaks loudly: the consumer
 * deserialises a message where that field is simply {@code null}, mints a ticket with a
 * null seat, and reports success. This test is the only thing that makes that a build
 * failure — the same role {@code SeatStatusSchemaContractTest} plays for the DynamoDB
 * attribute names.
 */
class TicketIssuanceContractTest {

    private static final Path SAGA_DEFINITION = Path.of("..", "docs", "checkout-saga.asl.json");

    @Test
    void everyFieldTheSagaEnqueuesIsAFieldOfTheMessage() throws IOException {
        assertThat(enqueuedFieldNames())
                .containsExactlyInAnyOrderElementsOf(componentNames(TicketIssuanceMessage.class));
    }

    @Test
    void everyFieldTheSagaPublishesIsAFieldOfTheNotification() throws IOException {
        JsonNode message = saga().path("States").path("NotifyPurchaseConfirmed")
                .path("Arguments").path("Message");

        assertThat(message.isString())
                .as("the notification payload must be a JSONata-built JSON string, not prose — "
                        + "notification-service parses it into PurchaseConfirmedEvent")
                .isTrue();

        // The Message is a JSONata expression rather than a JSON object, so its keys are
        // read out of the expression text. Crude, and still far better than nothing: the
        // alternative is discovering a renamed field from an email addressed to nobody.
        Set<String> published = Arrays.stream(message.stringValue().split("'"))
                .filter(token -> componentNames(PurchaseConfirmedEvent.class).contains(token))
                .collect(Collectors.toSet());

        assertThat(published)
                .containsExactlyInAnyOrderElementsOf(
                        componentNames(PurchaseConfirmedEvent.class));
    }

    private static List<String> enqueuedFieldNames() throws IOException {
        JsonNode body = saga().path("States").path("EnqueueTicketIssuance")
                .path("Arguments").path("MessageBody");
        assertThat(body.isObject()).as("EnqueueTicketIssuance must build a JSON message body").isTrue();
        return body.propertyNames().stream().toList();
    }

    private static Set<String> componentNames(Class<?> record) {
        return Arrays.stream(record.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
    }

    private static JsonNode saga() throws IOException {
        assertThat(SAGA_DEFINITION)
                .as("the saga definition is the producer's source of truth and must be readable from here")
                .exists();
        return JsonMapper.builder().build().readTree(Files.readString(SAGA_DEFINITION));
    }
}
