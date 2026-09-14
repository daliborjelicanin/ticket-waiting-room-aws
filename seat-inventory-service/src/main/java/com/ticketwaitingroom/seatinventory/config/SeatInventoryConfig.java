package com.ticketwaitingroom.seatinventory.config;

import com.ticketwaitingroom.seatinventory.inventory.SeatRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.URI;
import java.time.Clock;

/**
 * Wires the seat-inventory beans. The DynamoDB client honors an optional endpoint
 * override so the same code runs against LocalStack locally (endpoint set + dummy
 * credentials) and against real AWS in the cloud (endpoint unset → default endpoint
 * and the default credentials provider chain).
 *
 * <p>Scheduling is enabled for one task: the {@code seats.remaining} gauge refresh in
 * {@code SeatInventoryMetrics}.
 */
@Configuration
@EnableConfigurationProperties(SeatInventoryProperties.class)
@EnableScheduling
public class SeatInventoryConfig {

    /**
     * @param tracing observability-starter's SDK hook — a span per DynamoDB call, which is
     *                how a conditional-write loss shows up in X-Ray as a 400 subsegment
     *                under the request that lost the race
     */
    @Bean
    public DynamoDbClient dynamoDbClient(
            @Value("${aws.region}") String region,
            @Value("${aws.dynamodb.endpoint:}") String endpoint,
            ClientOverrideConfiguration tracing) {

        var builder = DynamoDbClient.builder().region(Region.of(region)).overrideConfiguration(tracing);
        if (StringUtils.hasText(endpoint)) {
            builder.endpointOverride(URI.create(endpoint))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("local", "local")));
        }
        return builder.build();
    }

    @Bean
    public SeatRepository seatRepository(DynamoDbClient dynamoDbClient, SeatInventoryProperties properties) {
        return new SeatRepository(dynamoDbClient, properties.tableName());
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
