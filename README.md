# Surge

[![ci](https://github.com/SouradipG/surge/actions/workflows/ci.yml/badge.svg)](https://github.com/SouradipG/surge/actions/workflows/ci.yml)

A ticket drop and flash sale backend that stays up and never oversells when a hundred thousand people press the button in the same second.

Java 25, Spring Boot 4, Redis, PostgreSQL.

## The problem

A concert goes on sale at 10:00. At 09:59:59 the traffic is normal. At 10:00:01 it is fifty times normal, and every one of those people wants the same few thousand seats. Two things go wrong in almost every first version.

**Overselling.** The obvious code reads the remaining count, checks it is above zero, and writes it back minus one. Two requests read 1 at the same moment, both see a ticket, both sell it. Under real concurrency this happens constantly.

**Collapse.** Every request gets slower, timeouts fire, clients retry, and the retries add more load. The database runs out of connections, the event page stops loading, and people who already had tickets in their cart lose them. The site fails at exactly the moment it matters.

Surge handles the first with atomic operations and a database constraint as the last line of defence, and the second with a waiting room, rate limits, priority load shedding and caching designed for one very hot key.

## What it covers

### Never oversell

* **Atomic reserve in Redis.** One Lua script checks the remaining count and the buyer's per person limit and takes the tickets, all in one step. Nothing can run between the check and the decrement.
* **Postgres is the source of truth.** `check (sold <= capacity)` on the inventory row means that even if every layer above were wrong, the database would refuse the sale.
* **Holds with expiry.** A hold keeps tickets for five minutes while the buyer pays. Payment and expiry race each other with conditional updates on the same row, so for every hold exactly one of them wins.
* **Reconciliation.** If Redis restarts, fails over, or drifts from Postgres, a job rebuilds the counters from the database. The overwrite is conditional on nothing having changed in the meantime, so it never wipes out a sale in progress.

### Stay up

* **Virtual waiting room.** Everyone gets a place in a FIFO queue, and users are let in in batches at the rate the backend can serve. Admission is a signed token any instance can check without a lookup.
* **Rate limiting** per user and per IP, as a token bucket shared by every instance through Redis.
* **Priority load shedding.** In flight requests are capped, and browsing may only use 60 percent of the capacity. The rest is kept for people who are already paying.
* **Two level cache with single flight** for the event page, so a cold cache at the moment of the drop costs one database query, not ten thousand.

## Running it

```bash
docker compose up -d     # Postgres 18 and Redis 8
./mvnw spring-boot:run
```

```bash
# create an event with 100 tickets
curl -s -X POST localhost:8080/events -H 'Content-Type: application/json' -d '{"name":"Final","capacity":100}'

# join the waiting room, then poll until admitted (one batch is let in every second)
curl -s -X POST localhost:8080/events/1/queue -H 'X-User-Id: alice'
curl -s localhost:8080/events/1/queue/me -H 'X-User-Id: alice'

# hold two tickets with the admission token, then pay
curl -s -X POST localhost:8080/events/1/holds \
  -H 'X-User-Id: alice' -H 'X-Admission-Token: <token>' \
  -H 'Content-Type: application/json' -d '{"quantity":2}'
curl -s -X POST localhost:8080/holds/<hold id>/purchase -H 'X-User-Id: alice'
```

Tests need only Docker. Testcontainers starts Postgres and Redis itself:

```bash
./mvnw verify
```

## Request path

```
request
  |
  LoadSheddingFilter     in memory counter, no I/O. Over the cap: 503 and Retry-After.
  |                      Browsing is refused first.
  RateLimitFilter        token bucket per IP and per user, in Redis. Over: 429 and Retry-After.
  |
  POST /events/{id}/queue      ZSET, place in line kept on rejoin
  AdmissionTicker              every second, one instance pops the next batch and signs tokens
  |
  POST /events/{id}/holds      verify token -> reserve.lua -> insert hold
  POST /holds/{id}/purchase    HELD -> PURCHASED and sold += n, one transaction
  HoldExpiryWorker             HELD -> EXPIRED, give the tickets back
```

The cheapest checks run first. A request that is going to be refused should cost as little as possible, because during a spike most requests are.

## Decisions and trade offs

### Why the count lives in Redis and not in Postgres

Ten thousand buyers decrementing one Postgres row all queue on that row's lock. The sale runs at the speed of one row, around a few thousand updates a second at best, and every waiting transaction holds a connection while it waits. Redis runs the contended step in memory, single threaded, in microseconds.

The price is that there are now two copies of the truth. Surge accepts that and handles it explicitly:

| What can go wrong | What happens |
|---|---|
| Redis reserves, then the Postgres insert fails | The service releases the tickets in Redis straight away. |
| The process dies between those two steps | The release never runs. The reconciler sees fewer tickets in Redis than Postgres accounts for and fixes the counter. Tickets are briefly unsellable, never oversold. |
| Redis loses data (restart without persistence, failover) | Reserves fail with `EVENT_NOT_ON_SALE` instead of guessing. The reconciler rebuilds the counter and every per person total from Postgres. |
| A reconcile reads Postgres while a reserve runs | Every reserve and release bumps an ops counter. The overwrite only applies if the counter has not moved since the read, otherwise it tries again. |
| All of the above fail at once | `check (sold <= capacity)` rejects the purchase and the buyer gets `SOLD_OUT`. |

Failing in the direction of unsold tickets rather than sold twice is deliberate. An unsold ticket is a small loss. A sold twice ticket is two angry people at the same seat.

### Purchase against expiry

A buyer pays at 4:59.9 into a five minute hold while the expiry worker runs at 5:00.0. If both win, the buyer is charged and their ticket goes back on sale for someone else. Both sides are one conditional update on the same row:

```sql
-- purchase                                    -- expiry
update holds set status = 'PURCHASED'          update holds set status = 'EXPIRED'
where id = :id and status = 'HELD'             where status = 'HELD' and expires_at <= now()
  and expires_at > now()                       ... for update skip locked
```

Whichever commits first changes the status, and the other's `where` no longer matches. Only the expiry side gives tickets back, so a hold can never be both paid and returned. `HoldLifecycleTest` races 200 purchases against the expiry job around the deadline and checks Redis and Postgres agree afterwards.

### One admission batch per tick, however many instances

Every instance runs the ticker. Without coordination, five instances would each admit a batch and the backend would see five times the rate it was sized for. Each tick takes a lock with `SET NX` and an expiry equal to the tick, so exactly one instance admits per tick, and if that instance dies the lock simply runs out. `WaitingRoomTest` runs five instances at the same moment and expects one batch.

### Why the admission token is signed, not looked up

Every hold request has to prove the user came through the queue. A lookup in Redis would add a round trip to the hottest path. An HMAC signed token naming the event, the user and an expiry can be verified by any instance with no I/O. Because the user id is part of what is signed, a token copied to another account fails. The comparison is constant time.

### Fail open or fail closed

When Redis is unreachable, the rate limiter cannot decide. Reads fail open, because a short window without limits is better than taking the event page down. Writes fail closed with `503`, because holds and purchases are exactly what bots target. The event page's cache treats Redis as optional and falls back to Postgres.

### Cache for one hot key

At the moment of the drop everyone requests the same event. Three things keep that from reaching Postgres:

* A 250 ms cache in each instance absorbs most reads before they leave the process, because even Redis has a limit for a single key.
* The Redis entry's expiry has random jitter, so every instance's copy does not expire on the same tick.
* On a miss, only one request per instance loads from Postgres. The rest wait on that load. `EventCacheTest` sends 1,000 concurrent requests at a cold cache and expects exactly one query.

Remaining ticket counts are never cached. They are read live from the Redis counter, because a stale count is what makes people click buy on a sold out event.

## Works locally, breaks in production

| Looks fine locally | What happens in production | What Surge does |
|---|---|---|
| Multi key Lua script on a single Redis | On Redis Cluster the keys hash to different slots and the script fails with `CROSSSLOT`. | Every key for an event shares the `{event:N}` hash tag, so they always land on one slot. |
| Rate limiter in a `ConcurrentHashMap` | Three instances give each client three times the limit, and every deploy resets it. | Token bucket in Redis, shared by all instances. `RateLimiterTest` shows the in memory version letting through 3x. |
| Token bucket using the app's clock | Instances disagree on the time by a few hundred milliseconds and refill the same bucket at different rates. | The script reads time from Redis `TIME`. |
| `@Scheduled` admission job | Runs on every instance, so the admit rate multiplies by the instance count. | Per tick lock with `SET NX` and an expiry. |
| Relying on autoscaling for the spike | New instances take a minute or more to start. The spike is over, or the site already fell over. | Waiting room and load shedding hold the line with the capacity that is already running. |
| One `UPDATE inventory SET sold = sold - 1` | Fine at ten requests a second. At ten thousand, every request queues on one row lock. | Contended step in Redis, Postgres updated once per purchase, constraint as the backstop. |
| Testing with a closed loop load tool | When the server slows down the tool sends less, and the latency numbers look better than reality. | `load/flash-sale.js` uses a constant arrival rate in k6. |
| Redis without a timeout | A slow Redis holds every request thread until the whole service stops. | 500 ms command timeout, and a decision per call site about what to do when it fires. |
| Cache miss handled by every request | A cold key at the moment of the drop sends thousands of identical queries to Postgres. | Single flight per instance plus jittered expiry. |

## Load testing

`load/flash-sale.js` creates an event, then sends a crowd at a fixed arrival rate through the whole flow: view the event, join the queue, poll until admitted, hold a ticket. It fails the run if p99 latency or the error rate crosses a threshold, and counts held, sold out and shed outcomes separately.

```bash
k6 run -e BASE_URL=http://localhost:8080 -e RATE=300 -e CAPACITY=1000 load/flash-sale.js
```

After any run, this must return no rows:

```sql
select * from inventory where sold > capacity;
```

## What is next

* **Payment through an external provider**, with the hold extended while a payment is in flight and the same unknown outcome handling as [Ledgerline](https://github.com/SouradipG/ledgerline).
* **Published load numbers** for a single instance and three instances, with and without the waiting room.
* **Redis failover drill** with Sentinel, checking that the reconciler brings counters back without a single oversell.
