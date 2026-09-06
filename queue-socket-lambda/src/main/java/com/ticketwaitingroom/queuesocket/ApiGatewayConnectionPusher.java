package com.ticketwaitingroom.queuesocket;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.apigatewaymanagementapi.ApiGatewayManagementApiClient;
import software.amazon.awssdk.services.apigatewaymanagementapi.model.PostToConnectionRequest;

import java.nio.charset.StandardCharsets;

/**
 * Pushes through API Gateway's {@code @connections} management API.
 *
 * <p>This is the one call in the system that a server initiates towards a browser. Note
 * that it goes to the API's own execute-api endpoint rather than to any client address —
 * API Gateway holds the socket, and the {@code connectionId} is the whole address.
 */
@RequiredArgsConstructor
class ApiGatewayConnectionPusher implements ConnectionPusher {

    private final ApiGatewayManagementApiClient management;

    @Override
    public void push(String connectionId, String payload) {
        management.postToConnection(PostToConnectionRequest.builder()
                .connectionId(connectionId)
                .data(SdkBytes.fromString(payload, StandardCharsets.UTF_8))
                .build());
    }
}
