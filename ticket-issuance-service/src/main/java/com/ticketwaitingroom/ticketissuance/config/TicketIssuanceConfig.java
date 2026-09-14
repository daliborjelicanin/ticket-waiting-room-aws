package com.ticketwaitingroom.ticketissuance.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;
import java.time.Clock;

/**
 * Wires the issuance beans. Every client honors an optional endpoint override so the same
 * code runs against LocalStack locally (endpoint set + dummy credentials) and against real
 * AWS in the cloud (endpoint unset → default endpoints and the default credentials provider
 * chain, i.e. the ECS task role). Same shape as {@code SeatInventoryConfig}.
 *
 * <p>The SQS and S3 clients take observability-starter's {@code tracing} override: a span
 * per call, and on the QR request the trace written into the message. The presigner does
 * not — it signs URLs locally and never makes a call worth a span.
 */
@Configuration
@EnableConfigurationProperties(TicketIssuanceProperties.class)
public class TicketIssuanceConfig {

    @Bean
    public SqsClient sqsClient(
            @Value("${aws.region}") String region,
            @Value("${aws.sqs.endpoint:}") String endpoint,
            ClientOverrideConfiguration tracing) {

        var builder = SqsClient.builder().region(Region.of(region)).overrideConfiguration(tracing);
        if (StringUtils.hasText(endpoint)) {
            builder.endpointOverride(URI.create(endpoint)).credentialsProvider(localCredentials());
        }
        return builder.build();
    }

    @Bean
    public S3Client s3Client(
            @Value("${aws.region}") String region,
            @Value("${aws.s3.endpoint:}") String endpoint,
            ClientOverrideConfiguration tracing) {

        var builder = S3Client.builder().region(Region.of(region)).overrideConfiguration(tracing);
        if (StringUtils.hasText(endpoint)) {
            // Virtual-host addressing resolves <bucket>.localhost, which does not exist.
            builder.endpointOverride(URI.create(endpoint))
                    .forcePathStyle(true)
                    .credentialsProvider(localCredentials());
        }
        return builder.build();
    }

    @Bean
    public S3Presigner s3Presigner(
            @Value("${aws.region}") String region,
            @Value("${aws.s3.endpoint:}") String endpoint) {

        var builder = S3Presigner.builder().region(Region.of(region));
        if (StringUtils.hasText(endpoint)) {
            builder.endpointOverride(URI.create(endpoint))
                    .credentialsProvider(localCredentials());
        }
        return builder.build();
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    private static StaticCredentialsProvider localCredentials() {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local"));
    }
}
