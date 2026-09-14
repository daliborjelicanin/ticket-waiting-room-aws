package com.ticketwaitingroom.notification.config;

import com.ticketwaitingroom.notification.notify.LoggingNotificationSender;
import com.ticketwaitingroom.notification.notify.NotificationSender;
import com.ticketwaitingroom.notification.notify.SesNotificationSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;

@Configuration
@EnableConfigurationProperties(NotificationProperties.class)
public class NotificationConfig {

    private static final String SES = "ses";

    /** Both clients carry observability-starter's {@code tracing} override: a span per call. */
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
    public SesV2Client sesClient(
            @Value("${aws.region}") String region,
            @Value("${aws.ses.endpoint:}") String endpoint,
            ClientOverrideConfiguration tracing) {

        var builder = SesV2Client.builder().region(Region.of(region)).overrideConfiguration(tracing);
        if (StringUtils.hasText(endpoint)) {
            builder.endpointOverride(URI.create(endpoint)).credentialsProvider(localCredentials());
        }
        return builder.build();
    }

    /**
     * Chosen by a property rather than a profile or a conditional annotation, so the
     * decision is one readable line and the alternative is a bean that provably exists.
     * Same split as {@code PaymentGateway} / {@code MockPaymentGateway} in checkout.
     */
    @Bean
    public NotificationSender notificationSender(SesV2Client ses, NotificationProperties properties) {
        return SES.equalsIgnoreCase(properties.sender())
                ? new SesNotificationSender(ses, properties)
                : new LoggingNotificationSender();
    }

    private static StaticCredentialsProvider localCredentials() {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local"));
    }
}
