package dev.souradip.surge.waitingroom;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/events/{eventId}/queue")
class WaitingRoomController {

    private final WaitingRoom room;

    WaitingRoomController(WaitingRoom room) {
        this.room = room;
    }

    @PostMapping
    ResponseEntity<Map<String, Object>> join(@PathVariable long eventId, @RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.accepted().body(view(room.join(eventId, userId)));
    }

    @GetMapping("/me")
    Map<String, Object> me(@PathVariable long eventId, @RequestHeader("X-User-Id") String userId) {
        return view(room.status(eventId, userId));
    }

    private static Map<String, Object> view(WaitingRoom.Status status) {
        return switch (status) {
            case WaitingRoom.Status.Admitted admitted -> Map.of("state", "ADMITTED", "admissionToken", admitted.token());
            case WaitingRoom.Status.Waiting waiting -> Map.of("state", "WAITING", "position", waiting.position());
            case WaitingRoom.Status.NotQueued notQueued -> Map.of("state", "NOT_QUEUED");
        };
    }
}
