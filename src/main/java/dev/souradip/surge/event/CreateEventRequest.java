package dev.souradip.surge.event;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateEventRequest(@NotBlank String name, @NotNull @Min(1) @Max(1_000_000) Integer capacity) {}
