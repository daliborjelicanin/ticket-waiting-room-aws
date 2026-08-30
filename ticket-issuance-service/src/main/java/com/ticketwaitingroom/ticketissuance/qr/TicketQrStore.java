package com.ticketwaitingroom.ticketissuance.qr;

import com.ticketwaitingroom.ticketissuance.config.TicketIssuanceProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.time.Instant;
import java.util.Optional;

/**
 * Read access to generated QR images.
 *
 * <p>Downloads are served as <strong>presigned URLs</strong>, not proxied bytes. The bucket
 * blocks all public access, so a signature scoped to one key for a few minutes is what
 * grants a customer their own ticket and nothing else — no object is ever readable without
 * one. Streaming the image through this service instead would put every ticket download
 * through a 0.25-vCPU Fargate task for no benefit.
 */
@Component
@RequiredArgsConstructor
public class TicketQrStore {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final TicketIssuanceProperties properties;

    /**
     * A time-limited download URL for a QR object, or empty if the Lambda has not written
     * it yet.
     *
     * <p>The existence check is a real {@code HeadObject} rather than a flag on the ticket
     * row. Issuance and rendering are separate deployables writing to separate stores; a
     * column here claiming the image exists would be a guess about someone else's state,
     * and the presigned URL it produced would 404 in the customer's browser. Asking S3
     * costs one request and cannot be wrong.
     */
    public Optional<PresignedQr> presign(String objectKey) {
        if (!exists(objectKey)) {
            return Optional.empty();
        }
        var presigned = presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(properties.qrUrlTtl())
                .getObjectRequest(get -> get.bucket(properties.qrBucket()).key(objectKey))
                .build());
        return Optional.of(new PresignedQr(presigned.url().toString(), presigned.expiration()));
    }

    public record PresignedQr(String url, Instant expiresAt) { }

    private boolean exists(String objectKey) {
        try {
            s3.headObject(HeadObjectRequest.builder()
                    .bucket(properties.qrBucket())
                    .key(objectKey)
                    .build());
            return true;
        } catch (NoSuchKeyException notYet) {
            return false;
        }
    }
}
