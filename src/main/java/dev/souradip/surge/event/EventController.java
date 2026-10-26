package dev.souradip.surge.event;

import dev.souradip.surge.api.ApiException;
import dev.souradip.surge.inventory.InventoryStore;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class EventController {

    private final EventRepository events;
    private final EventCache cache;
    private final InventoryStore inventory;

    EventController(EventRepository events, EventCache cache, InventoryStore inventory) {
        this.events = events;
        this.cache = cache;
        this.inventory = inventory;
    }

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.CREATED)
    EventView create(@Valid @RequestBody CreateEventRequest request) {
        EventDetails event = events.create(request.name(), request.capacity());
        inventory.initialise(event.id(), event.capacity());
        return new EventView(event.id(), event.name(), event.capacity(), event.capacity());
    }

    @GetMapping("/events/{eventId}")
    EventView get(@PathVariable long eventId) {
        EventDetails event = cache.get(eventId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND", "no such event"));
        return new EventView(event.id(), event.name(), event.capacity(), Math.max(0, inventory.available(eventId)));
    }
}
