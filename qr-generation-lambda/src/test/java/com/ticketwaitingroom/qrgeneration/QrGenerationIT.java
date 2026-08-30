package com.ticketwaitingroom.qrgeneration;

import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.ticketwaitingroom.common.tickets.TicketQrObjects;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Object;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the QR Lambda writes an image a scanner can actually read, and that redelivery —
 * which SQS guarantees will happen — costs nothing.
 *
 * <p>Runs against a real S3 (LocalStack) rather than a mocked client because the claim
 * being made is about objects in a bucket: their key, their content type, and the fact that
 * writing one twice leaves one object. None of that is observable through a mock.
 */
@Testcontainers
class QrGenerationIT {

    private static final String BUCKET = "ticket-qr-test";
    private static final String VERIFY_BASE_URL = "https://tickets.example.test";

    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:3.8"));

    private static S3Client s3;
    private QrGenerationHandler handler;

    @BeforeAll
    static void createClient() {
        s3 = S3Client.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build();
    }

    @AfterAll
    static void closeClient() {
        if (s3 != null) {
            s3.close();
        }
    }

    @BeforeEach
    void freshBucket() {
        emptyBucket();
        handler = new QrGenerationHandler(new QrObjectStore(s3, BUCKET), new QrRenderer(), VERIFY_BASE_URL);
    }

    @Test
    void writesAQrThatDecodesToTheTicketsVerificationUrl() {
        String ticketId = UUID.randomUUID().toString();

        SQSBatchResponse response = handler.handleRequest(batch(ticketId), null);

        assertThat(response.getBatchItemFailures()).isEmpty();
        assertThat(decode(object(TicketQrObjects.key(ticketId))))
                .isEqualTo(VERIFY_BASE_URL + "/tickets/" + ticketId);
    }

    @Test
    void servesTheImageAsAPngSoBrowsersRenderIt() {
        String ticketId = UUID.randomUUID().toString();

        handler.handleRequest(batch(ticketId), null);

        // Without an explicit content type S3 serves application/octet-stream, and the
        // customer's browser downloads their ticket instead of showing it.
        assertThat(s3.headObject(head -> head.bucket(BUCKET).key(TicketQrObjects.key(ticketId))).contentType())
                .isEqualTo("image/png");
    }

    @Test
    void aRedeliveryRewritesTheIdenticalObject() {
        String ticketId = UUID.randomUUID().toString();

        handler.handleRequest(batch(ticketId), null);
        byte[] first = object(TicketQrObjects.key(ticketId));
        handler.handleRequest(batch(ticketId), null);
        byte[] second = object(TicketQrObjects.key(ticketId));

        // This is the entire idempotency story: a deterministic key and a pure renderer, so
        // the second delivery is indistinguishable from the first and needs no guard.
        assertThat(second).isEqualTo(first);
        assertThat(keysInBucket()).containsExactly(TicketQrObjects.key(ticketId));
    }

    @Test
    void aMalformedRecordFailsAloneAndLeavesItsBatchMatesDone() {
        String good = UUID.randomUUID().toString();

        SQSBatchResponse response = handler.handleRequest(batch(good, "not-a-ticket-id"), null);

        // Throwing instead would fail the whole batch: the good record would be redelivered
        // and re-rendered on every attempt, and it would follow the bad one to the DLQ.
        assertThat(response.getBatchItemFailures())
                .extracting(SQSBatchResponse.BatchItemFailure::getItemIdentifier)
                .containsExactly("msg-not-a-ticket-id");
        assertThat(keysInBucket()).containsExactly(TicketQrObjects.key(good));
    }

    @Test
    void anEmptyBodyIsAFailureRatherThanAnObjectUnderAnEmptyKey() {
        SQSBatchResponse response = handler.handleRequest(batch(""), null);

        assertThat(response.getBatchItemFailures()).hasSize(1);
        assertThat(keysInBucket()).isEmpty();
    }

    private static SQSEvent batch(String... bodies) {
        SQSEvent event = new SQSEvent();
        event.setRecords(List.of(bodies).stream().map(body -> {
            SQSEvent.SQSMessage record = new SQSEvent.SQSMessage();
            record.setMessageId("msg-" + body);
            record.setBody(body);
            return record;
        }).toList());
        return event;
    }

    private static String decode(byte[] png) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            return new MultiFormatReader()
                    .decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image))))
                    .getText();
        } catch (NotFoundException notAQrCode) {
            throw new AssertionError("The rendered PNG does not contain a readable QR code", notAQrCode);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] object(String key) {
        ResponseBytes<?> bytes = s3.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(BUCKET).key(key).build());
        return bytes.asByteArray();
    }

    private static List<String> keysInBucket() {
        return s3.listObjectsV2(ListObjectsV2Request.builder().bucket(BUCKET).build())
                .contents().stream().map(S3Object::key).toList();
    }

    private static void emptyBucket() {
        try {
            keysInBucket().forEach(key -> s3.deleteObject(delete -> delete.bucket(BUCKET).key(key)));
        } catch (NoSuchBucketException firstRun) {
            s3.createBucket(create -> create.bucket(BUCKET));
        }
    }
}
