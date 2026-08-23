package com.ticketwaitingroom.waitingroom.web;

import com.ticketwaitingroom.waitingroom.queue.QueuePlace;
import com.ticketwaitingroom.waitingroom.queue.QueueStatus;
import com.ticketwaitingroom.waitingroom.queue.WaitingRoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * HTTP surface for the waiting room. Joining returns 201 with the token the client must
 * keep and send back; polling an unknown token is mapped to 404 by
 * {@link ApiExceptionHandler}.
 */
@RestController
@RequiredArgsConstructor
public class WaitingRoomController {

    private final WaitingRoomService waitingRoomService;

    @PostMapping("/events/{eventId}/queue")
    @ResponseStatus(HttpStatus.CREATED)
    public QueuePlaceResponse join(@PathVariable String eventId,
                                   @RequestBody(required = false) JoinRequest request) {
        return QueuePlaceResponse.of(waitingRoomService.join(eventId, request == null ? null : request.token()));
    }

    @GetMapping("/events/{eventId}/queue/{token}")
    public QueuePlaceResponse place(@PathVariable String eventId, @PathVariable String token) {
        return QueuePlaceResponse.of(waitingRoomService.place(eventId, token));
    }

    @PostMapping("/events/{eventId}/queue/admit")
    public AdmitResponse admit(@PathVariable String eventId, @RequestBody AdmitRequest request) {
        WaitingRoomService.Admission admission = waitingRoomService.admitNext(eventId, request.count());
        return new AdmitResponse(eventId, admission.tokens(), admission.tokens().size(),
                admission.admissionExpiresAt());
    }

    /** Send back a previously issued token to re-join idempotently; omit it to be issued one. */
    public record JoinRequest(String token) { }

    public record AdmitRequest(int count) { }

    public record AdmitResponse(String eventId, List<String> tokens, int admitted, Instant admissionExpiresAt) { }

    public record QueuePlaceResponse(
            String eventId,
            String token,
            QueueStatus status,
            Long position,
            long queueLength,
            Instant admissionExpiresAt
    ) {

        static QueuePlaceResponse of(QueuePlace place) {
            return new QueuePlaceResponse(place.eventId(), place.token(), place.status(),
                    place.position(), place.queueLength(), place.admissionExpiresAt());
        }
    }
}
