package dev.souradip.surge.event;

/** The part of an event that rarely changes, and so is safe to cache. */
public record EventDetails(long id, String name, int capacity) {}
