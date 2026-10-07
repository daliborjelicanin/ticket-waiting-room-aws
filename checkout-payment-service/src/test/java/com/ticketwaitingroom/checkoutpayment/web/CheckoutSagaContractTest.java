package com.ticketwaitingroom.checkoutpayment.web;

import com.ticketwaitingroom.checkoutpayment.payment.PaymentStatus;
import com.ticketwaitingroom.checkoutpayment.payment.RefundStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins this service's saga endpoints to the state machine that calls them.
 *
 * <p>The caller is a JSON document deployed to Step Functions, which no compiler reads. A
 * renamed field fails silently on both sides: a request body field arrives as {@code null},
 * and a {@code Choice} that reads a renamed response field takes its {@code Default}. For
 * {@code PaymentCaptured} the default is the seat release, and for {@code RefundSucceeded}
 * it is {@code RefundFailed}. Either way the saga stays green while doing the wrong thing.
 * The ticket-issuance side has the same test for the message it consumes
 * ({@code TicketIssuanceContractTest}).
 */
class CheckoutSagaContractTest {

    private static final Path SAGA_DEFINITION = Path.of("..", "docs", "checkout-saga.asl.json");

    @Test
    void createOrderSendsExactlyTheFieldsTheRequestDeclares() throws IOException {
        assertThat(requestFields("CreateOrder"))
                .containsExactlyInAnyOrderElementsOf(components(CheckoutController.CreateOrderRequest.class));
    }

    @Test
    void chargeSendsTheRequestAndBranchesOnAFieldTheResponseHas() throws IOException {
        assertThat(requestFields("ChargePayment"))
                .containsExactlyInAnyOrderElementsOf(components(CheckoutController.ChargeRequest.class));

        String condition = choiceCondition("PaymentCaptured");
        assertThat(components(CheckoutController.ChargeResponse.class)).contains("paymentStatus");
        assertThat(condition).contains("$payment.paymentStatus").contains("'" + PaymentStatus.CAPTURED + "'");
    }

    @Test
    void refundSendsTheRequestAndBranchesOnAFieldTheResponseHas() throws IOException {
        assertThat(requestFields("RefundPayment"))
                .containsExactlyInAnyOrderElementsOf(components(CheckoutController.RefundRequest.class));

        String condition = choiceCondition("RefundSucceeded");
        assertThat(components(CheckoutController.RefundResponse.class)).contains("refundStatus");
        assertThat(condition).contains("$refund.refundStatus").contains("'" + RefundStatus.SUCCEEDED + "'");
    }

    /**
     * The refund is only safe on a <em>definite</em> refusal of the confirm. If the 404 route
     * ever widened to {@code States.ALL}, a timed-out confirm that had in fact sold the seat
     * would be refunded too, and the buyer would keep the seat and get the money back.
     */
    @Test
    void onlyADefiniteRefusalOfTheConfirmLeadsToARefund() throws IOException {
        JsonNode catches = saga().path("States").path("ConfirmPurchase").path("Catch");

        assertThat(catches.get(0).path("ErrorEquals").get(0).stringValue()).isEqualTo("States.Http.StatusCode.404");
        assertThat(catches.get(0).path("Next").stringValue()).isEqualTo("RefundPayment");
        assertThat(catches.get(1).path("ErrorEquals").get(0).stringValue()).isEqualTo("States.ALL");
        assertThat(catches.get(1).path("Next").stringValue()).isEqualTo("PaidButNotConfirmed");
    }

    private static List<String> requestFields(String state) throws IOException {
        JsonNode body = saga().path("States").path(state).path("Arguments").path("RequestBody");
        assertThat(body.isObject()).as(state + " must send a JSON request body").isTrue();
        return body.propertyNames().stream().toList();
    }

    private static String choiceCondition(String state) throws IOException {
        return saga().path("States").path(state).path("Choices").get(0).path("Condition").stringValue();
    }

    private static List<String> components(Class<?> record) {
        return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    private static JsonNode saga() throws IOException {
        assertThat(SAGA_DEFINITION).as("the saga definition must be readable from here").exists();
        return JsonMapper.builder().build().readTree(Files.readString(SAGA_DEFINITION));
    }
}
