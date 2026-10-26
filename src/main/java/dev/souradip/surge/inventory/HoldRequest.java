package dev.souradip.surge.inventory;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record HoldRequest(@NotNull @Min(1) @Max(10) Integer quantity) {}
