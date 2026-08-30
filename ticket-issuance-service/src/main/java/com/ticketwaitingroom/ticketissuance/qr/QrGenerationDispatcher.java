package com.ticketwaitingroom.ticketissuance.qr;

import com.ticketwaitingroom.ticketissuance.config.TicketIssuanceProperties;
import com.ticketwaitingroom.ticketissuance.ticket.Ticket;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * Hands a ticket off to the QR-generation Lambda by way of {@code qr-generation-queue}.
 *
 * <p>A queue rather than a direct {@code lambda:InvokeFunction}: rendering is slow, failable
 * work that nothing is waiting on, and a queue is what gives it retries, a dead-letter queue
 * and back-pressure without this service knowing anything about any of it. Invoking the
 * Lambda synchronously would put a cold start on the consumer's critical path and make a
 * rendering failure into an issuance failure.
 *
 * <p>The body is the bare ticket id. Everything the Lambda needs is derived from it — the
 * object key and the encoded verification URL both — so there is no JSON on this hop and
 * therefore no JSON parser in a Lambda that pays cold-start time for every class it loads.
 */
@Component
@RequiredArgsConstructor
public class QrGenerationDispatcher {

    private final SqsClient sqs;
    private final TicketIssuanceProperties properties;

    /**
     * Request the QR image for a ticket. Safe to call repeatedly: the object key is derived
     * from the ticket id, so a second render overwrites the first with identical bytes.
     */
    public void requestQr(Ticket ticket) {
        sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(properties.qrQueueUrl())
                .messageBody(ticket.getId().toString())
                .build());
    }
}
