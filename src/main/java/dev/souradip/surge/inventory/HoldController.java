package dev.souradip.surge.inventory;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class HoldController {

    private final HoldService holds;

    HoldController(HoldService holds) {
        this.holds = holds;
    }

    @PostMapping("/events/{eventId}/holds")
    @ResponseStatus(HttpStatus.CREATED)
    HoldView hold(@PathVariable long eventId,
                  @RequestHeader("X-User-Id") String userId,
                  @RequestHeader(value = "X-Admission-Token", required = false) String token,
                  @Valid @RequestBody HoldRequest request) {
        return holds.hold(eventId, userId, request.quantity(), token);
    }

    @PostMapping("/holds/{holdId}/purchase")
    @ResponseStatus(HttpStatus.CREATED)
    OrderView purchase(@PathVariable UUID holdId, @RequestHeader("X-User-Id") String userId) {
        return holds.purchase(holdId, userId);
    }
}
