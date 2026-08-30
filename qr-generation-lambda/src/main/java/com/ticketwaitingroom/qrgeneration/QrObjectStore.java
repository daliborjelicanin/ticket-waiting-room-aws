package com.ticketwaitingroom.qrgeneration;

import com.ticketwaitingroom.common.tickets.TicketQrObjects;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Writes a rendered QR image to the ticket bucket.
 *
 * <p>An unconditional {@code PutObject}, on purpose. The key is derived from the ticket id
 * and the bytes are deterministic, so "already there" and "not there yet" are the same
 * write — a conditional put or a preceding HeadObject would add a request and a race in
 * order to skip an operation that is already harmless.
 */
final class QrObjectStore {

    private final S3Client s3;
    private final String bucket;

    QrObjectStore(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    void store(String ticketId, byte[] png) {
        s3.putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(TicketQrObjects.key(ticketId))
                        // Without this S3 serves application/octet-stream and the browser
                        // downloads the ticket instead of showing it.
                        .contentType(TicketQrObjects.CONTENT_TYPE)
                        .build(),
                RequestBody.fromBytes(png));
    }
}
