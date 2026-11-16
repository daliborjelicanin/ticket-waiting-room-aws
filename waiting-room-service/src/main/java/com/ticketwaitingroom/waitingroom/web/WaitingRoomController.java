package com.ticketwaitingroom.waitingroom.web;

import com.ticketwaitingroom.waitingroom.queue.QueuePlace;
import com.ticketwaitingroom.waitingroom.queue.QueueStatus;
import com.ticketwaitingroom.waitingroom.queue.WaitingRoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * HTTP surface for the waiting room.
 *
 * <p>Join and position are <strong>fan-door</strong> endpoints: the caller is whoever the
 * verified JWT's {@code sub} says, and nothing else. Neither takes a token in the URL, and
 * that is the point — a caller can only ever act on their own place, so polling somebody
 * else's position is not a request the API can express.
 *
 * <p>Admit and the admission check are <strong>ops-door</strong> endpoints. Admit pops real
 * people off the head of a real queue; the admission check names a customer in the URL,
 * which a fan door must never do. {@code SecurityConfig} requires the ops scope for both
 * before this class is ever reached; the controller itself has nothing to check.
 */
@RestController
@RequiredArgsConstructor
public class WaitingRoomController {

    private final WaitingRoomService waitingRoomService;

    @PostMapping("/events/{eventId}/queue")
    @ResponseStatus(HttpStatus.CREATED)
    public QueuePlaceResponse join(@PathVariable String eventId, @AuthenticationPrincipal Jwt caller) {
        return QueuePlaceResponse.of(waitingRoomService.join(eventId, caller.getSubject()));
    }

    @GetMapping("/events/{eventId}/queue")
    public QueuePlaceResponse place(@PathVariable String eventId, @AuthenticationPrincipal Jwt caller) {
        return QueuePlaceResponse.of(waitingRoomService.place(eventId, caller.getSubject()));
    }

    @PostMapping("/events/{eventId}/queue/admit")
    public AdmitResponse admit(@PathVariable String eventId, @RequestBody AdmitRequest request) {
        WaitingRoomService.Admission admission = waitingRoomService.admitNext(eventId, request.count());
        return new AdmitResponse(eventId, admission.tokens(), admission.tokens().size(),
                admission.admissionExpiresAt());
    }

    /**
     * The saga's first step: may this customer buy now? Always 200, with {@code admitted}
     * true only for an unexpired pass, so the state machine branches on a field rather than
     * on an HTTP error it would have to tell apart from an outage.
     */
    @GetMapping("/events/{eventId}/admissions/{customerRef}")
    public AdmissionResponse admission(@PathVariable String eventId, @PathVariable String customerRef) {
        QueuePlace place = waitingRoomService.admission(eventId, customerRef);
        boolean admitted = place.status() == QueueStatus.ADMITTED;
        return new AdmissionResponse(eventId, admitted, place.status(),
                admitted ? place.admissionExpiresAt() : null);
    }

    public record AdmitRequest(int count) { }

    public record AdmissionResponse(String eventId, boolean admitted, QueueStatus status, Instant admissionExpiresAt) { }

    public record AdmitResponse(String eventId, List<String> tokens, int admitted, Instant admissionExpiresAt) { }

    /**
     * The token is still returned, because the WebSocket connection is keyed by it — but it
     * is now an opaque handle the server issued to this identity rather than a credential
     * the client chose and must remember to send back.
     */
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
