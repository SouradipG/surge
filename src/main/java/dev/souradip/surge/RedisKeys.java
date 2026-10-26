package dev.souradip.surge;

/**
 * Every key for one event carries the same {event:N} hash tag. Redis Cluster places keys by the
 * tag, so all of an event's keys land on one slot and multi key Lua scripts keep working when
 * the deployment moves from a single Redis to a cluster.
 */
public final class RedisKeys {

    private RedisKeys() {}

    public static final String ACTIVE_EVENTS = "surge:active-events";

    private static String tag(long eventId) {
        return "surge:{event:" + eventId + "}";
    }

    public static String available(long eventId) {
        return tag(eventId) + ":available";
    }

    public static String perUser(long eventId) {
        return tag(eventId) + ":per-user";
    }

    public static String ops(long eventId) {
        return tag(eventId) + ":ops";
    }

    public static String queue(long eventId) {
        return tag(eventId) + ":queue";
    }

    public static String queueSeq(long eventId) {
        return tag(eventId) + ":queue-seq";
    }

    public static String admitted(long eventId, String userId) {
        return tag(eventId) + ":admitted:" + userId;
    }

    public static String admissionLock(long eventId) {
        return tag(eventId) + ":admission-lock";
    }

    public static String eventCache(long eventId) {
        return tag(eventId) + ":details";
    }

    public static String bucket(String subject) {
        return "surge:{bucket:" + subject + "}";
    }
}
