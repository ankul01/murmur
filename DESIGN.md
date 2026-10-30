# Twitter Clone — Learning Design (HLD, LLD, Local, AWS Free Plan)

One document for the whole project. It replaces the earlier DESIGN.md, LOCAL_DEV.md, and DEPLOYMENT.md.

Items marked **(verify before use)** depend on versions or prices that change. Check them before you rely on them.

---

## 1. Purpose, scope, non-goals

### Purpose

This is a hands-on learning project with two goals:

1. **Design and coding.** Learn home-timeline fan-out by switching between **fan-out on write**, **fan-out on read**, and **hybrid**, and measure how they differ.
2. **AWS fluency.** Run the same system on a new AWS account on the **Free plan**, spending **$0**. Practice IAM, security groups, EC2, SSM, CloudWatch, Budgets, and optionally RDS.

It runs on a laptop first, then on one EC2 instance. It is never exposed to the public internet and is never productionized.

### In scope

- Create a user
- Follow and unfollow
- Create a tweet (text only) and soft-delete it
- Home timeline with cursor pagination
- Three fan-out strategies, switchable with one setting
- Log lines and experiments that make the trade-offs visible

### Non-goals

- Images and media (see §9 v2)
- Login and auth: no JWT, no Cognito. Identity is a header, and safety comes from the network.
- API Gateway, load balancers, rate limits
- Likes, replies, retweets, search, DMs, notifications, websockets
- Flyway, retries, dead-letter topics, tracing, metrics stacks, CI/CD, Terraform
- Managed Kafka/Redis, ECS, or anything that costs money on AWS

---

## 2. Requirements

### Functional

| # | Requirement |
| --- | --- |
| F1 | Create a user with a unique handle and a display name. |
| F2 | Follow or unfollow another user. Repeats are idempotent. No self-follow. |
| F3 | Maintain follower and following counts and an `is_celebrity` flag. |
| F4 | Post a tweet with 1–140 Unicode code points of text. |
| F5 | Soft-delete your own tweet. It disappears from every timeline on the next read. |
| F6 | Home timeline: tweets from people you follow **plus your own tweets**, newest first, cursor-paginated. Own tweets can be turned off with `INCLUDE_OWN_TWEETS=false`. |
| F8 | Profile timeline: a user's own live tweets, newest first, cursor-paginated (`GET /users/{id}/tweets`). |
| F7 | Strategy setting `FANOUT_STRATEGY = write \| read \| hybrid` changes how the timeline is built, without any code change. |

### Non-functional (learning-sized)

- Read-heavy workload. The timeline should feel instant on the cached path, and logs show the milliseconds.
- Strong consistency inside each database. The timeline is **eventually consistent**: a second or two of lag after a tweet is expected under `write`.
- Everything fits on one 4–8 GB machine.

---

## 3. High-level design

### Service map

| Service | Port | Responsibility | Owns |
| --- | --- | --- | --- |
| **user-service** | 8001 | Users, follows, counts, celebrity flag. Publishes `follow.changed`. | Postgres database `users` (tables `users`, `follows`) |
| **tweet-service** | 8002 | Create, batch-get, by-author list, soft-delete. Publishes `tweet.created`. | Postgres database `tweets` (table `tweets`) |
| **timeline-service** | 8004 | `GET /timeline`: reads Redis and/or pulls tweets, merges, filters, hydrates. | Nothing durable. Reads `timeline:{userId}`. |
| **fanout-worker** | 8005 | Consumes Kafka and writes follower timelines into Redis. | Writes `timeline:{userId}` |

Port 8003 is intentionally unused; it is reserved for the v2 media-service.

### Diagram

```text
                 curl / scripts  (laptop, or through SSM on EC2)
                         │  header X-User-Id
      ┌──────────────────┼────────────────────┐
      ▼                  ▼                    ▼
┌─────────────┐   ┌─────────────┐     ┌────────────────┐
│ user-service│   │tweet-service│     │timeline-service│
│    :8001    │   │    :8002    │     │     :8004      │
└──┬───────┬──┘   └──┬───────┬──┘     └──┬──────────┬──┘
   │       │         │       │           │ reads    │ calls user + tweet
   ▼       │         ▼       │           ▼          │ (following, batch)
┌────────┐ │     ┌────────┐  │       ┌────────┐     │
│Postgres│ │     │Postgres│  │       │ Redis  │◀────┼──────────┐
│ users  │ │     │ tweets │  │       │timeline│     │          │ ZADD / trim
└────────┘ │     └────────┘  │       └────────┘     │          │
           │ follow.changed  │ tweet.created        │   ┌──────┴───────┐
           └────────────▶ Kafka ◀────────┘          │   │ fanout-worker│
                            │                       │   │    :8005     │
                            └──────────────────────────▶└──┬───────────┘
                                                            │ calls user (author, followers)
                                                            │ calls tweet (backfill)
```

### Data ownership rules

- Services never share tables. Cross-service references are ids only (`user_id`).
- Lookups across services use batch HTTP calls, never cross-database joins.
- Redis `timeline:{userId}` is written **only** by fanout-worker and read **only** by timeline-service.

### Events (Kafka)

| Topic | Producer | Consumer | Key | When |
| --- | --- | --- | --- | --- |
| `tweet.created` | tweet-service | fanout-worker | `authorId` | After the tweet row commits |
| `follow.changed` | user-service | fanout-worker | `followerId` | After a follow row is actually inserted or deleted (not on idempotent repeats) |

There is no `tweet.deleted` event. Deletes are handled when the timeline is read.

### Fan-out strategies

| | **write** (push) | **read** (pull) | **hybrid** |
| --- | --- | --- | --- |
| On tweet | Worker ZADDs into every follower's ZSET | Worker does nothing | Normal author: push. Celebrity: nothing. |
| On follow | Worker backfills the followee's latest tweets | Nothing | Normal followee: backfill. Celebrity: nothing. |
| On read | Read the ZSET, hydrate | Load following list, query tweets by all followees, merge | Read the ZSET **plus** query tweets by followed celebrities, merge |
| Write cost | O(followers) Redis writes per tweet | O(1) | O(followers) for normal authors only |
| Read cost | 1 Redis range plus batch get | One SQL over all followees (grows with following count) | 1 Redis range plus one SQL over celebrities only |
| Freshness | Lags until the worker runs | Immediate | Normal: lags. Celebrity: immediate. |
| Celebrity with many followers | Write storm | Fine | Fine |
| Viewer following thousands | Fine | Slow reads | Fine |
| Worker down | New tweets missing until catch-up | No effect | Normal authors' new tweets missing until catch-up |
| Redis lost | Empty timelines (no rebuild in v1) | No effect | Only celebrity tweets shown |

---

## 4. Low-level design

### 4.1 Ids, time, and cursor

- Every id is a **UUIDv7** generated in the application (`com.github.f4b6a3:uuid-creator`). Postgres never generates ids.
- `tweets.created_at` is set from the tweet id's UUIDv7 timestamp, so Postgres and Redis agree on the time.
- Redis score = UUIDv7 timestamp in epoch milliseconds.
- UUIDv7 values sort by time, both as Postgres `uuid` and as lowercase strings. Ties inside one millisecond are broken by the rest of the UUID.
- **Cursor = the `tweetId` of the last item on the previous page.** It is opaque to clients. "Older than cursor" means `id < cursor`.

### 4.2 Tables (SQL DDL)

Each service ships a `schema.sql` that runs on startup (`spring.sql.init.mode=always`). There is no Flyway. Every statement uses `IF NOT EXISTS`, so restarts are safe. Schema changes during learning: drop the volume and start again.

The databases themselves are created once by `infra/postgres/01-init-databases.sql`:

```sql
CREATE DATABASE users;
CREATE DATABASE tweets;
```

**user-service: database `users`**

```sql
CREATE TABLE IF NOT EXISTS users (
    id              UUID         PRIMARY KEY,               -- UUIDv7 from the app
    handle          VARCHAR(30)  NOT NULL UNIQUE,           -- stored lowercased
    display_name    VARCHAR(50)  NOT NULL,
    follower_count  BIGINT       NOT NULL DEFAULT 0,
    following_count BIGINT       NOT NULL DEFAULT 0,
    is_celebrity    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS follows (
    follower_id UUID        NOT NULL REFERENCES users(id),
    followee_id UUID        NOT NULL REFERENCES users(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (follower_id, followee_id),                 -- also serves "who do I follow"
    CHECK (follower_id <> followee_id)
);

-- "followers of X", paged by follower_id
CREATE INDEX IF NOT EXISTS idx_follows_followee ON follows (followee_id, follower_id);
```

**tweet-service: database `tweets`**

```sql
CREATE TABLE IF NOT EXISTS tweets (
    id         UUID         PRIMARY KEY,                    -- UUIDv7 from the app
    user_id    UUID         NOT NULL,                       -- author; no FK across services
    text       VARCHAR(140) NOT NULL CHECK (char_length(text) BETWEEN 1 AND 140),
    created_at TIMESTAMPTZ  NOT NULL,                       -- from the UUIDv7 timestamp
    deleted_at TIMESTAMPTZ                                  -- null = live
);

-- "latest live tweets by these authors, older than cursor"
CREATE INDEX IF NOT EXISTS idx_tweets_author_live ON tweets (user_id, id DESC) WHERE deleted_at IS NULL;
```

Text length is counted in **Unicode code points** everywhere: `String.codePointCount` in Java, and `char_length` in a UTF-8 Postgres database. An emoji counts as 1.

### 4.3 Identity

- Callers send `X-User-Id: <uuid>`. If it is missing or not a UUID, the service returns `401`.
- Services trust the header. The network is the security boundary (§7). Only user-service checks that the caller exists, and only on follow and unfollow.
- `POST /users` is the only route that doesn't need the header.

### 4.4 Routes

All bodies are JSON. Every service also has `GET /health` → `200 {"status":"UP"}`. Routes marked *internal* are called only by other services, but they aren't technically restricted, since the network already is.

**user-service :8001**

| Method and path | Header | Request | Response | Errors |
| --- | --- | --- | --- | --- |
| `POST /users` | — | `{handle, displayName}` | `201 {id, handle, displayName}` | `400` invalid (handle `^[a-z0-9_]{3,30}$` after lowercasing; displayName 1–50 code points). `409` handle taken. |
| `GET /users/{id}` | — | — | `200 {id, handle, displayName, followerCount, followingCount, isCelebrity, createdAt}` | `404` |
| `POST /users/{id}/follow` | required | — | `200 {following:true, changed:true\|false}` | `400` self-follow. `401` caller unknown. `404` followee unknown. |
| `DELETE /users/{id}/follow` | required | — | `200 {following:false, changed:true\|false}` | `401`, `404` |
| `GET /users/{id}/followers?cursor=&limit=` *internal* | — | `limit` default 500, max 1000 | `200 {ids:[...], nextCursor}`, ordered by follower id | `404` |
| `GET /users/{id}/following` *internal* | — | — | `200 {items:[{id, isCelebrity}]}` (everyone, capped at 5,000) | `404` |
| `GET /users?ids=a,b,...` *internal* | — | up to 100 ids | `200 {items:[{id, handle, displayName}]}`. Unknown ids are left out. | `400` more than 100 ids |

**tweet-service :8002**

| Method and path | Header | Request | Response | Errors |
| --- | --- | --- | --- | --- |
| `POST /tweets` | required | `{text}` | `201 {id, userId, text, createdAt}` | `400` empty or more than 140 code points |
| `DELETE /tweets/{id}` | required | — | `204` (also when it's already deleted by its owner) | `404` missing or not the owner |
| `GET /tweets?ids=a,b,...` *internal* | — | up to 100 ids | `200 {items:[{id, userId, text, createdAt}]}`. Deleted and unknown ids are left out. | `400` more than 100 ids |
| `GET /users/{id}/tweets?cursor=&limit=` | — | `limit` default 20, max 50 | `200 {items:[...], nextCursor}`: that user's live tweets, newest first (profile timeline) | `400` |
| `POST /tweets/by-authors` *internal* | — | `{authorIds:[...], before?: tweetId, limit}` (up to 5,000 authors, limit max 800) | `200 {items:[...]}`: live tweets, newest first, `id < before` | `400` |

`by-authors` is a read that uses `POST`, because 5,000 UUIDs don't fit in a URL. Tomcat's default header limit is 8 KB.

**timeline-service :8004**

| Method and path | Header | Request | Response | Errors |
| --- | --- | --- | --- | --- |
| `GET /timeline?cursor=&limit=` | required | `limit` default 20, max 50 | `200 {items:[{tweetId, userId, handle, displayName, text, createdAt}], nextCursor}` | `400` bad cursor or limit |

`nextCursor` is `null` when there is nothing older. A page may hold fewer than `limit` items after filtering, and still have a `nextCursor`.

### 4.5 Follow and unfollow write path (user-service)

All steps run in one transaction. The event is published **after** the commit.

```text
follow(caller, followee):
  if caller == followee              -> 400
  if caller or followee missing      -> 401 / 404
  inserted = INSERT INTO follows ... ON CONFLICT DO NOTHING   -- 1 or 0 rows
  if inserted == 1:
     UPDATE users SET follower_count = follower_count + 1,
                      is_celebrity   = (follower_count + 1) >= :threshold
      WHERE id = :followee
     UPDATE users SET following_count = following_count + 1 WHERE id = :caller
  COMMIT
  if inserted == 1: publish follow.changed {action:"follow"}
  return {following:true, changed: inserted == 1}

unfollow: the same shape with DELETE, -1, and is_celebrity = (follower_count - 1) >= :threshold
```

- The celebrity flag only changes on a follow or unfollow write. Changing `CELEBRITY_FOLLOWER_THRESHOLD` and restarting does nothing until the next follow or unfollow of that user.
- If the service crashes between commit and publish, the event is lost. This is accepted in v1 (see §9, outbox).

### 4.6 Event formats

```json
// topic tweet.created, key = authorId
{ "eventId": "uuidv7", "tweetId": "uuidv7", "authorId": "uuidv7", "createdAt": "2026-11-01T12:00:00.123Z" }

// topic follow.changed, key = followerId
{ "eventId": "uuidv7", "followerId": "uuidv7", "followeeId": "uuidv7", "action": "follow|unfollow", "at": "2026-11-01T12:01:00Z" }
```

- Payloads are String JSON. The producer and consumer use String serializers and map the JSON themselves.
- Malformed messages are logged and skipped, using the default listener error handling.
- Keys keep one author's tweets, and one follower's follow changes, in order within a partition.
- Topics have 3 partitions and replication factor 1, and are created by `kafka-init`. Auto-create is off.
- Consumer group `fanout-workers`, `auto-offset-reset=earliest`. One listener per topic.

### 4.7 Redis

| Item | Value |
| --- | --- |
| Key | `timeline:{userId}` (ZSET) |
| Member | tweetId (UUIDv7 string) |
| Score | UUIDv7 epoch millis |
| Write | `ZADD timeline:{f} <millis> <tweetId>`, then `ZREMRANGEBYRANK timeline:{f} 0 -(TIMELINE_MAX_LENGTH+1)` |
| Read | `ZREVRANGEBYSCORE timeline:{v} <cursorMillis or +inf> -inf WITHSCORES LIMIT 0 <F>`, then drop members with `id >= cursor` |
| Never | No ZREM on unfollow or delete, and no TTL. A missing key means an empty list. |

Re-adding the same member with the same score changes nothing, so fan-out and backfill are safe to repeat. Members with the same score come back in reverse string order, which matches UUIDv7 order.

### 4.8 fanout-worker pseudocode

```text
on tweet.created(e):
  start = now()
  if FANOUT_STRATEGY == read:
      log fanout skip reason=read-strategy; return
  author = GET user-service /users/{e.authorId}
  if FANOUT_STRATEGY == hybrid and author.isCelebrity:
      log fanout skip reason=celebrity; return
  score = uuidv7Millis(e.tweetId); cursor = null; written = 0; pages = 0
  do:
      page = GET /users/{e.authorId}/followers?cursor=cursor&limit=FANOUT_PAGE_SIZE
      for f in page.ids:
          ZADD timeline:{f} score e.tweetId
          ZREMRANGEBYRANK timeline:{f} 0 -(TIMELINE_MAX_LENGTH+1)
          written++
      cursor = page.nextCursor; pages++
  while cursor != null
  log fanout (fields in 4.11)

on follow.changed(e):
  if e.action == unfollow: return                      -- filtered on read, no prune
  if FANOUT_STRATEGY == read: return
  followee = GET /users/{e.followeeId}
  if FANOUT_STRATEGY == hybrid and followee.isCelebrity: return
  tweets = POST tweet-service /tweets/by-authors {authorIds:[e.followeeId], limit: FOLLOW_BACKFILL_COUNT}
  for t in tweets: ZADD timeline:{e.followerId} uuidv7Millis(t.id) t.id
  ZREMRANGEBYRANK timeline:{e.followerId} 0 -(TIMELINE_MAX_LENGTH+1)
  log fanout event=follow.changed backfilled=len(tweets)
```

**Why backfill takes "latest N" and not "last T minutes":** suppose a tweet is created just before the follow commits. Its fan-out may have read the follower list before the new follower existed, so it misses them. The backfill runs after the follow commits and asks for the newest N tweets, which already includes that tweet. That closes the race with no time window to tune.

### 4.9 timeline-service read pseudocode

```text
GET /timeline(viewer, cursor, limit):
  F = limit + TIMELINE_OVERFETCH                       -- extra room for filtered items
  following = GET user-service /users/{viewer}/following      -- [{id, isCelebrity}]
  followSet = ids(following)
  if INCLUDE_OWN_TWEETS: followSet += viewer          -- own tweets survive the unfollow filter
  candidates = []                                      -- items of (tweetId, maybe tweet)

  if strategy in (write, hybrid):
      ids = ZREVRANGEBYSCORE timeline:{viewer} (cursor ? millis(cursor) : +inf) -inf LIMIT 0 F
      ids = ids where id < cursor (if cursor)
      candidates += ids                                -- not hydrated yet

  pullAuthors = strategy == read   ? followSet
              : strategy == hybrid ? {f.id for f in following if f.isCelebrity}
              : {}
  if INCLUDE_OWN_TWEETS: pullAuthors += viewer        -- own tweets are always pulled, never pushed
  if pullAuthors not empty:
      candidates += POST tweet-service /tweets/by-authors {authorIds: pullAuthors, before: cursor, limit: F}

  candidates = dedupe by tweetId, sort by tweetId desc, take first F
  hydrate the Redis ids: GET tweet-service /tweets?ids=...   (chunks of 100)
  drop items missing from the batch                    -- deleted       -> count dropDeleted
  drop items whose userId not in followSet             -- unfollowed    -> count dropUnfollowed
  page = first `limit` survivors
  profiles = GET user-service /users?ids=<authors of page>
  nextCursor = page full                 ? last(page).tweetId
             : more candidates may exist ? last examined candidate's tweetId
             : null
  log timeline (fields in 4.11)
  return {items, nextCursor}
```

- **Own tweets** are pulled on read under every strategy: one extra author in the by-author query, no extra ZADD. They show up immediately (read-your-own-writes), even when the worker is down.
- Calls run one after another (sequential `RestClient`). Running them in parallel is a v2 experiment.
- "More candidates may exist" means some source returned a full `F` items.
- Correctness of the merge: each source returns its newest `F` items older than the cursor. So after the merge, every item newer than the F-th candidate is present, and nothing is skipped across pages.

### 4.10 Config keys

| Key | Default (local) | Used by |
| --- | --- | --- |
| `PORT` | 8001 / 8002 / 8004 / 8005 | all |
| `DB_URL` | `jdbc:postgresql://localhost:5432/users` (user), `.../tweets` (tweet) | user, tweet |
| `DB_USER` / `DB_PASSWORD` | `twitter` / `twitter_local` | user, tweet |
| `REDIS_URL` | `redis://localhost:6379` | timeline, worker |
| `KAFKA_BROKERS` | `localhost:9092` (containers use `kafka:19092`) | user, tweet, worker |
| `KAFKA_CONSUMER_GROUP` | `fanout-workers` | worker |
| `USER_SERVICE_URL` | `http://localhost:8001` | timeline, worker |
| `TWEET_SERVICE_URL` | `http://localhost:8002` | timeline, worker |
| `FANOUT_STRATEGY` | `write` | timeline, worker (**restart both** to change) |
| `CELEBRITY_FOLLOWER_THRESHOLD` | `5` | user |
| `TIMELINE_MAX_LENGTH` | `800` | worker |
| `FANOUT_PAGE_SIZE` | `500` | worker |
| `FOLLOW_BACKFILL_COUNT` | `50` | worker |
| `TIMELINE_DEFAULT_LIMIT` / `TIMELINE_MAX_LIMIT` | `20` / `50` | timeline |
| `TIMELINE_OVERFETCH` | `20` | timeline |
| `INCLUDE_OWN_TWEETS` | `true` | timeline |
| `JAVA_TOOL_OPTIONS` | (EC2) `-Xms128m -Xmx256m -XX:+UseSerialGC` | all |

These are plain environment variables, with defaults in `application.yml`. There are no Spring profiles.

### 4.11 Log lines (what you compare in experiments)

```text
fanout   event=tweet.created strategy=write tweetId=… authorId=… celebrity=false followers=200 zadds=200 pages=1 ms=41
fanout   event=tweet.created strategy=hybrid tweetId=… authorId=… skip=celebrity ms=2
fanout   event=follow.changed action=follow strategy=write followerId=… followeeId=… backfilled=37 ms=9
timeline strategy=hybrid viewer=… following=42 celebrities=2 zsetIds=40 pulled=12 candidates=52 dropDeleted=1 dropUnfollowed=3 returned=20 ms=18
```

Each is one line at INFO level, in key=value form, so you can `grep` it and compare.

---

## 5. Consistency and failure modes you can reproduce

| Case | What happens | How to see it |
| --- | --- | --- |
| Worker down (`write` / `hybrid`) | New tweets from normal authors are missing. Kafka keeps the events, and the worker catches up after a restart. | Stop the worker, tweet, read (missing), start the worker, read (present). Under `read` nothing changes. |
| Backfill race | Closed by "latest N" backfill (4.8) | Follow, then read immediately. The latest tweets appear once the worker runs. |
| Strategy switch | Data is not migrated. Tweets posted under `read` never entered ZSETs, so after switching back to `write` they are missing. | Experiment E4 |
| Delete | The ZSET still has the id. The read drops it (`dropDeleted`). | `redis-cli ZSCORE` shows it; the timeline doesn't. |
| Unfollow | The ZSET still has their tweets. The read drops them (`dropUnfollowed`). Re-follow brings them back. | Experiment E6 |
| Celebrity flip **up** (`hybrid`) | Old tweets stay in ZSETs. New tweets arrive by pull. Nothing is lost. | Raise followers past the threshold |
| Celebrity flip **down** (`hybrid`) | Tweets posted while they were a celebrity were never pushed and are no longer pulled, so they **vanish** from timelines. | Experiment E5. This is a known gap in hybrid designs. |
| Redis flushed | `write`: empty timelines. `hybrid`: only celebrity tweets. `read`: no effect. No rebuild in v1. | `redis-cli FLUSHALL` |
| Event lost (crash between commit and publish) | That tweet is never fanned out. | Hard to trigger; read about the outbox pattern (§9). |

---

## 6. Local setup

### 6.1 Repository layout

The full folder tree, package structure, and code rules are in **AGENTS.md**, which agents read first. In short:

- `libs/common`, plus `services/<name>`, each using the standard Maven layout
- Base package `io.github.<github-user>.twitter.<module>`, organized by feature (`user/`, `follow/`, `tweet/`, `timeline/`, `fanout/`)
- `schema.sql` and `application.yml` under `src/main/resources`

Each service builds `target/app.jar` (Maven `finalName` = `app`), so Compose can mount a fixed path.

### 6.2 docker-compose.yml (verify image tags before use)

Infrastructure starts by default. The four services start only with `--profile apps`, which EC2 uses. Locally you usually run the services from the IDE or with `java -jar`.

```yaml
name: twitter-clone

x-app: &app
  image: eclipse-temurin:21-jre          # verify before use
  profiles: ["apps"]
  command: ["java", "-jar", "/app/app.jar"]
  restart: unless-stopped
  environment: &app-env
    JAVA_TOOL_OPTIONS: "-Xms128m -Xmx256m -XX:+UseSerialGC"
    KAFKA_BROKERS: kafka:19092
    REDIS_URL: redis://redis:6379
    USER_SERVICE_URL: http://user-service:8001
    TWEET_SERVICE_URL: http://tweet-service:8002
    FANOUT_STRATEGY: ${FANOUT_STRATEGY:-write}
    DB_USER: ${DB_USER:-twitter}
    DB_PASSWORD: ${DB_PASSWORD:-twitter_local}

services:
  postgres:
    image: postgres:16-alpine            # verify before use
    ports: ["127.0.0.1:5432:5432"]
    environment:
      POSTGRES_USER: twitter
      POSTGRES_PASSWORD: twitter_local
    volumes:
      - pgdata:/var/lib/postgresql/data
      - ./infra/postgres/01-init-databases.sql:/docker-entrypoint-initdb.d/01-init-databases.sql:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U twitter"]
      interval: 5s
      retries: 10

  redis:
    image: redis:7.4-alpine              # verify before use
    command: ["redis-server", "--maxmemory", "256mb", "--maxmemory-policy", "allkeys-lru"]
    ports: ["127.0.0.1:6379:6379"]
    volumes: [redisdata:/data]
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      retries: 10

  kafka:
    image: apache/kafka:3.9.0            # official Apache image; verify before use
    hostname: kafka
    ports: ["127.0.0.1:9092:9092"]
    environment:
      CLUSTER_ID: "MkU3OEVBNTcwNTJENDM2Qk"
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: INTERNAL://:19092,EXTERNAL://:9092,CONTROLLER://:9093
      KAFKA_ADVERTISED_LISTENERS: INTERNAL://kafka:19092,EXTERNAL://localhost:9092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_INTER_BROKER_LISTENER_NAME: INTERNAL
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: 0
      KAFKA_HEAP_OPTS: "-Xms512m -Xmx512m"
    volumes: [kafkadata:/var/lib/kafka/data]
    healthcheck:
      test: ["CMD-SHELL", "/opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:19092 --list"]
      interval: 10s
      retries: 12
      start_period: 20s

  kafka-init:
    image: apache/kafka:3.9.0
    depends_on: { kafka: { condition: service_healthy } }
    entrypoint: ["/bin/sh", "-c"]
    command:
      - |
        for t in tweet.created follow.changed; do
          /opt/kafka/bin/kafka-topics.sh --create --if-not-exists --bootstrap-server kafka:19092 \
            --topic "$$t" --partitions 3 --replication-factor 1
        done
        /opt/kafka/bin/kafka-topics.sh --list --bootstrap-server kafka:19092

  user-service:
    <<: *app
    ports: ["127.0.0.1:8001:8001"]
    volumes: ["./services/user-service/target/app.jar:/app/app.jar:ro"]
    environment:
      <<: *app-env
      PORT: 8001
      DB_URL: ${USERS_DB_URL:-jdbc:postgresql://postgres:5432/users}
      CELEBRITY_FOLLOWER_THRESHOLD: ${CELEBRITY_FOLLOWER_THRESHOLD:-5}
    depends_on: { postgres: { condition: service_healthy }, kafka-init: { condition: service_completed_successfully } }

  tweet-service:
    <<: *app
    ports: ["127.0.0.1:8002:8002"]
    volumes: ["./services/tweet-service/target/app.jar:/app/app.jar:ro"]
    environment:
      <<: *app-env
      PORT: 8002
      DB_URL: ${TWEETS_DB_URL:-jdbc:postgresql://postgres:5432/tweets}
    depends_on: { postgres: { condition: service_healthy }, kafka-init: { condition: service_completed_successfully } }

  timeline-service:
    <<: *app
    ports: ["127.0.0.1:8004:8004"]
    volumes: ["./services/timeline-service/target/app.jar:/app/app.jar:ro"]
    environment: { <<: *app-env, PORT: 8004, INCLUDE_OWN_TWEETS: "${INCLUDE_OWN_TWEETS:-true}" }
    depends_on: { redis: { condition: service_healthy } }

  fanout-worker:
    <<: *app
    ports: ["127.0.0.1:8005:8005"]
    volumes: ["./services/fanout-worker/target/app.jar:/app/app.jar:ro"]
    environment:
      <<: *app-env
      PORT: 8005
      TIMELINE_MAX_LENGTH: 800
      FANOUT_PAGE_SIZE: 500
      FOLLOW_BACKFILL_COUNT: 50
    depends_on: { redis: { condition: service_healthy }, kafka-init: { condition: service_completed_successfully } }

volumes: { pgdata: {}, redisdata: {}, kafkadata: {} }
```

Every port binds to `127.0.0.1`, so even on EC2 nothing listens on a public interface.

### 6.3 Local environment (`.env.local`, for services run from the IDE or shell)

```bash
export FANOUT_STRATEGY=write
export DB_USER=twitter DB_PASSWORD=twitter_local
export KAFKA_BROKERS=localhost:9092 REDIS_URL=redis://localhost:6379
export USER_SERVICE_URL=http://localhost:8001 TWEET_SERVICE_URL=http://localhost:8002
export CELEBRITY_FOLLOWER_THRESHOLD=5
# per service: PORT and DB_URL (jdbc:postgresql://localhost:5432/users or /tweets)
```

### 6.4 Startup order

```text
1. docker compose up -d                 # postgres, redis, kafka, kafka-init
2. docker compose ps                    # wait for healthy; kafka-init exited 0
3. user-service  :8001
4. tweet-service :8002
5. fanout-worker :8005
6. timeline-service :8004
```

Checks:

```bash
docker compose exec postgres psql -U twitter -l           # users, tweets listed
docker compose exec redis redis-cli ping
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --list --bootstrap-server kafka:19092
curl -s localhost:8001/health
```

### 6.5 Seed script (`scripts/seed.sh`, bash + curl + jq)

It creates the data the experiments need, using only the public routes:

- `USERS` (default 200) users with handles `seed_0001`…
- An author `normal_author` followed by `NORMAL_FOLLOWERS` (50) users
- An author `celeb_author` followed by `CELEB_FOLLOWERS` (150) users
- A `viewer` user who follows both authors plus 20 random users
- `TWEETS_PER_USER` (2) tweets for each followed user
- It writes the ids to `scripts/seed.out.env`, so experiments can `source` them.

For experiments, run user-service with `CELEBRITY_FOLLOWER_THRESHOLD=100` so `celeb_author` crosses it and `normal_author` doesn't.

### 6.6 Smoke test (`scripts/smoke.sh`, `FANOUT_STRATEGY=write`)

The follow happens **before** the tweet, so the result never depends on backfill timing. Start from a clean state (`docker compose down -v`), because handles are unique.

```bash
U=http://localhost:8001; T=http://localhost:8002; TL=http://localhost:8004
J='Content-Type: application/json'

ADA=$(curl -s -X POST $U/users -H "$J" -d '{"handle":"ada","displayName":"Ada Lovelace"}' | jq -r .id)
GRACE=$(curl -s -X POST $U/users -H "$J" -d '{"handle":"grace","displayName":"Grace Hopper"}' | jq -r .id)

curl -s -X POST $U/users/$ADA/follow -H "X-User-Id: $GRACE" | jq .   # changed: true
curl -s -X POST $U/users/$ADA/follow -H "X-User-Id: $GRACE" | jq .   # changed: false (idempotent)

TWEET=$(curl -s -X POST $T/tweets -H "$J" -H "X-User-Id: $ADA" -d '{"text":"Hello from Ada!"}' | jq -r .id)
sleep 2
curl -s "$TL/timeline?limit=10" -H "X-User-Id: $GRACE" | jq .       # Ada's tweet
curl -s "$TL/timeline?limit=10" -H "X-User-Id: $ADA" | jq .         # Ada sees her own tweet
docker compose exec redis redis-cli ZRANGE timeline:$GRACE 0 -1 WITHSCORES

curl -s -X DELETE $U/users/$ADA/follow -H "X-User-Id: $GRACE" | jq .
curl -s "$TL/timeline?limit=10" -H "X-User-Id: $GRACE" | jq .       # empty; the ZSET still has it
curl -s -X POST $U/users/$ADA/follow -H "X-User-Id: $GRACE" | jq .

curl -s -o /dev/null -w "%{http_code}\n" -X DELETE $T/tweets/$TWEET -H "X-User-Id: $ADA"   # 204
curl -s "$TL/timeline?limit=10" -H "X-User-Id: $GRACE" | jq .       # gone
```

Repeat it with `FANOUT_STRATEGY=read` and `hybrid` (restart the worker and timeline-service each time). The expected output is the same; only the log lines differ.

### 6.7 Secrets and the public repo

- Committed: `.env.example` with placeholder values, and the local-only defaults (`twitter` / `twitter_local`) in Compose and `application.yml`. They protect nothing, because every port binds to `127.0.0.1`.
- Never committed: `.env`, `.env.local`, any RDS password, AWS keys. `.gitignore` lists `.env`, `.env.*` (except `.env.example`), `target/`, and `scripts/seed.out.env`.
- EC2 needs no AWS keys at all: the instance role provides credentials.
- Before every push: `git diff --cached | grep -iE "password|secret|AKIA"` should find nothing new.

### 6.8 Tear down

`docker compose down` keeps data. `docker compose down -v` wipes Postgres, Redis, and Kafka.

---

## 7. AWS dev deployment ($0, Free plan)

### 7.1 Rules

- **A new AWS account on the Free plan.** It comes with up to $200 in credits. It cannot be charged unless you upgrade to a paid plan. It closes after 6 months or when the credits run out. Free plan accounts can only use selected services. **Do not upgrade to the paid plan.** (verify before use)
- **Every resource is paid from credits.** Spend them slowly: stop everything after each session.
- **Forbidden** (expensive or unnecessary): load balancers, NAT Gateway, MSK, ElastiCache, ECS/Fargate, API Gateway, Cognito, CloudFront, VPC interface endpoints, Elastic IPs, and anything the Free plan doesn't allow.
- **Not public.** The security group has **no inbound rules**. You reach the instance only through **SSM Session Manager**.
- **Keep the code on GitHub, not only on EC2.** When the Free plan ends, the account and its resources go away.

### 7.2 Architecture

```text
 Laptop ──(AWS CLI + Session Manager plugin, HTTPS out)──▶ SSM ──▶ EC2 instance (default VPC, public subnet)
                                                                   │  SG: inbound none, outbound all
                                                                   │  Amazon Linux 2023, Docker Compose
                                                                   │   postgres  redis  kafka
                                                                   │   user tweet timeline fanout  (127.0.0.1 only)
                                                                   │
                                        Stage 2 (optional) ───────▶ RDS PostgreSQL (private, SG allows only the EC2 SG)
```

The instance needs an auto-assigned public IPv4 only for **outbound** traffic: the SSM agent, `docker pull`, and `git clone`. No ports are open. An auto-assigned IP is released when the instance stops, so it costs nothing while stopped.

### 7.3 Cost (approximate; verify before use against current pricing for your region)

| Resource | Rate | Credits per 10-hour session | Notes |
| --- | --- | --- | --- |
| EC2 `m7i-flex.large` (8 GB) | ~$0.08–0.10/h | ~$1.00 | Preferred. Use whichever 4–8 GB type the launch wizard labels Free-plan eligible. |
| EC2 `c7i-flex.large` (4 GB) | ~$0.08–0.09/h | ~$0.90 | Fallback; add 2 GB swap |
| Public IPv4 (auto-assigned) | ~$0.005/h while running | ~$0.05 | Released on stop |
| EBS gp3, 20 GB | ~$0.08/GB-month | ~$0.05/day | **Charged even while stopped** |
| RDS `db.t4g.micro` + 20 GB (Stage 2) | ~$0.016/h + ~$2.3/month storage | ~$0.25 | **A stopped RDS instance restarts by itself after 7 days.** Delete it when you're done. |
| SSM Session Manager | free | 0 | |
| CloudWatch Logs | free within the Free plan limits (verify) | 0 | 1-day retention |
| AWS Budgets | free | 0 | |

Rough total: 3 sessions a week of 3 hours for 6 months ≈ 80 hours of EC2 ≈ $8, plus EBS ≈ $10, for about **$18 of the $100 starting credits**. Leaving the instance running all the time would cost roughly $70 a month.

### 7.4 One-time account setup

1. Sign up for a new account and pick the **Free plan**. Enable MFA on the root user, and stop using root after this.
2. Create an admin user for daily work (IAM Identity Center or an IAM user) with MFA.
3. On your laptop, install AWS CLI v2 and the **Session Manager plugin**. Then run `aws configure sso` (or set access keys).
4. Pick one region, for example `ap-south-1` (Mumbai), and use it for everything.
5. **Billing → Budgets**: create a $1 cost budget with an email alert. Also check the Free plan credit balance weekly in Billing.

### 7.5 Stage 1: EC2 (console or CLI)

**IAM role**: `twitter-dev-ec2-role`, trusted by EC2, with the policy `AmazonSSMManagedInstanceCore`. For the optional CloudWatch Logs, also allow `logs:CreateLogGroup`, `logs:CreateLogStream`, and `logs:PutLogEvents`.

```bash
aws iam create-role --role-name twitter-dev-ec2-role \
  --assume-role-policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}'
aws iam attach-role-policy --role-name twitter-dev-ec2-role \
  --policy-arn arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore
aws iam create-instance-profile --instance-profile-name twitter-dev-ec2-profile
aws iam add-role-to-instance-profile --instance-profile-name twitter-dev-ec2-profile --role-name twitter-dev-ec2-role
```

**Security group** in the default VPC, with no inbound rules:

```bash
VPC=$(aws ec2 describe-vpcs --filters Name=is-default,Values=true --query 'Vpcs[0].VpcId' --output text)
SG=$(aws ec2 create-security-group --group-name twitter-dev-sg --description "no inbound" --vpc-id $VPC --query GroupId --output text)
# Default outbound allow-all is kept. Add no ingress rules.
```

**Launch** (console: Amazon Linux 2023, a Free-plan-eligible type, 20 GB gp3, the default VPC public subnet, auto-assign public IP on, the instance profile above, no key pair, IMDSv2 required, tag `Project=twitter-dev`):

```bash
AMI=$(aws ssm get-parameter --name /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 --query Parameter.Value --output text)
ID=$(aws ec2 run-instances --image-id $AMI --instance-type m7i-flex.large \
  --security-group-ids $SG --iam-instance-profile Name=twitter-dev-ec2-profile \
  --associate-public-ip-address \
  --metadata-options HttpTokens=required \
  --block-device-mappings '[{"DeviceName":"/dev/xvda","Ebs":{"VolumeSize":20,"VolumeType":"gp3"}}]' \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=Project,Value=twitter-dev}]' \
  --query 'Instances[0].InstanceId' --output text)
echo $ID
```

**Connect and install** (the SSM agent is preinstalled on AL2023):

```bash
aws ssm start-session --target $ID
# on the instance:
sudo dnf install -y docker git java-21-amazon-corretto-devel
sudo systemctl enable --now docker && sudo usermod -aG docker ssm-user
sudo mkdir -p /usr/local/lib/docker/cli-plugins
sudo curl -SL https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64 \
  -o /usr/local/lib/docker/cli-plugins/docker-compose && sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
# 4 GB instance only: add swap
# sudo dd if=/dev/zero of=/swapfile bs=1M count=2048 && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
```

**Deploy:**

```bash
git clone https://github.com/<you>/twitter-clone.git && cd twitter-clone   # public repo, no token needed
./mvnw -q package -DskipTests
docker compose --profile apps up -d
docker compose ps
bash scripts/smoke.sh          # run the smoke test on the instance itself
```

To deploy again: `git pull && ./mvnw -q package -DskipTests && docker compose --profile apps up -d --force-recreate <service>`.

**Use it from your laptop** (one port-forward per terminal):

```bash
aws ssm start-session --target $ID --document-name AWS-StartPortForwardingSession \
  --parameters '{"portNumber":["8004"],"localPortNumber":["18004"]}'
curl -s localhost:18004/timeline -H "X-User-Id: <id>"
```

**Optional logs**: add `logging: { driver: awslogs, options: { awslogs-region: ap-south-1, awslogs-group: twitter-dev, awslogs-create-group: "true" } }` to the app services, and set the log group's retention to 1 day.

**After every session:** `aws ec2 stop-instances --instance-ids $ID`. Start it again with `start-instances`. Docker restarts the containers by itself (`restart: unless-stopped`), and the data volumes survive.

### 7.6 Stage 2 (optional): Postgres on RDS

1. Create security group `twitter-dev-rds-sg` with inbound TCP 5432 **from `twitter-dev-sg` only**.
2. RDS → Create database:
   - PostgreSQL 16, **Free tier** template, smallest instance (`db.t4g.micro` / `db.t3.micro`), 20 GB storage
   - Single-AZ, **Public access: No**, the security group above
   - Performance Insights and Enhanced Monitoring **off**; backup retention 1 day or 0
3. From the EC2 instance, create the databases:
   `docker run --rm -it postgres:16-alpine psql "host=<endpoint> user=twitter sslmode=require" -c "CREATE DATABASE users" -c "CREATE DATABASE tweets"`
4. Create `.env` next to the compose file (`chmod 600`):

   ```bash
   USERS_DB_URL=jdbc:postgresql://<endpoint>:5432/users?sslmode=require
   TWEETS_DB_URL=jdbc:postgresql://<endpoint>:5432/tweets?sslmode=require
   DB_USER=twitter
   DB_PASSWORD=<rds password>
   ```

   RDS for PostgreSQL 15+ forces SSL by default, hence `sslmode=require` (verify before use).
5. Run `docker compose --profile apps up -d --force-recreate user-service tweet-service`, then `docker compose stop postgres`. Each service creates its tables on startup from `schema.sql`.
6. When you're done learning RDS: **delete** the instance (no final snapshot) and delete any manual snapshots.

### 7.7 Local → AWS mapping

| Concern | Local | EC2 Stage 1 | EC2 Stage 2 |
| --- | --- | --- | --- |
| Postgres | Compose `localhost:5432` | Compose `postgres:5432` | RDS endpoint + `sslmode=require` |
| Redis | Compose | Compose | Compose |
| Kafka | `localhost:9092` | `kafka:19092` | `kafka:19092` |
| Services | IDE or `java -jar` | Compose `--profile apps` | Compose `--profile apps` |
| Access | localhost | SSM shell or port-forward | the same |
| Secrets | `.env.local` | none needed | `.env` on the instance (`chmod 600`) |

### 7.8 Teardown checklist

- [ ] `aws ec2 terminate-instances --instance-ids $ID` (deletes the root EBS volume)
- [ ] RDS instance deleted, and no snapshots left (RDS → Snapshots)
- [ ] EC2 → Volumes, Snapshots, and Elastic IPs all empty
- [ ] CloudWatch log group `twitter-dev` deleted
- [ ] Security groups `twitter-dev-sg` and `twitter-dev-rds-sg` deleted, plus the IAM role and instance profile
- [ ] Billing shows no running resources; Budgets alert stays in place

---

## 8. Experiments

Run each one locally first and then on EC2 with larger numbers. Record the `fanout` and `timeline` log lines. For E1 and E2, set `INCLUDE_OWN_TWEETS=false` so `pulled` counts only followed authors.

| # | Experiment | Steps | What to observe |
| --- | --- | --- | --- |
| E1 | Push vs pull for a normal author | Seed. `write`: `normal_author` tweets; viewer reads. Restart both services with `read`, tweet again, read again. | `write`: `zadds=50`, fast read with `pulled=0`. `read`: worker `skip`, read shows `pulled>0` and higher `ms`. |
| E2 | Celebrity write storm | Threshold 100, `celeb_author` has 150 followers (scale up on EC2, e.g. 5,000). Compare `write` with `hybrid`. | `write`: `zadds=150` and worker `ms` grows with followers. `hybrid`: `skip=celebrity`, and the read shows `celebrities=1 pulled>0`. |
| E3 | Worker down | `write`. Stop fanout-worker, post 3 tweets, read, start the worker, read. | Missing, then present. Repeat under `read`: no difference. |
| E4 | Strategy switch | `write` → post A. `read` → post B. `write` → read. | A present, B **missing** (it never entered the ZSET). Explains why real systems need a rebuild or migration. |
| E5 | Celebrity flip down | `hybrid`, `celeb_author` tweets C. Unfollow enough users to drop below the threshold. Read. | C vanishes for remaining followers. This is the known hybrid gap. |
| E6 | Lazy filtering | Delete a tweet and unfollow an author. Read, and check `redis-cli ZSCORE`. | Members still in Redis, while `dropDeleted` and `dropUnfollowed` are above 0 in the log. |

Stretch on EC2: run 10,000 seed users and time the timeline read under `read` versus `hybrid` while the viewer follows 1,000 users.

---

## 9. v2 ideas, decision log, open questions

### v2 ideas (documented, not built)

- **Images.** A media-service on port 8003 owning a `media` database:
  - `POST /media/uploads` creates a `pending` row and returns a presigned PUT URL with a signed Content-Length (at most `MEDIA_MAX_BYTES`).
  - The client PUTs the bytes straight to MinIO locally or S3 on AWS. Objects use the key `media/{userId}/{mediaId}`.
  - `POST /media/{id}/finalize` checks the object with HeadObject and marks it `ready`.
  - Tweets carry up to 4 `mediaIds`, accepted without checking. Timeline hydrates them with `GET /media?ids=`, keeping only `ready` media owned by the tweet's author.
  - A sweeper deletes `pending` items older than 24 hours.
  - On AWS, S3 is cheap but mostly not free; keep usage tiny.
- **Auth.** HS256 JWT locally and Cognito on AWS. Requests resolve `sub` to a user id through an internal user-service route. Cognito access tokens have `client_id`, not `aud`.
- **Redis hygiene.** ZREM on unfollow, and a `tweet.deleted` consumer.
- **Rebuild.** `POST /admin/timelines/{userId}/rebuild` from follows and tweets, to compare rebuild cost against fan-out and to fix E4.
- **Outbox pattern** for publishing events without losing them, and **parallel hydration** with virtual threads.
- **Managed AWS.** ECS, MSK, and ElastiCache cost money: about $0.75/h for MSK Serverless. These are for reading about, not for this account.

### Decision log

| Decision | Why |
| --- | --- |
| Removed the Media service, MinIO, S3, and CloudFront | It doesn't teach fan-out; it moved to v2 |
| Removed JWT, Cognito, and the internal token; added `X-User-Id` | Auth isn't the learning goal; the network is the boundary |
| Removed Flyway; added `schema.sql` | One less tool; dropping the volume is fine for learning |
| Removed API Gateway, the load balancer, and rate limits | They cost money and aren't the learning goal |
| Removed `tweet.deleted` | Lazy filtering covers deletes |
| Added `FANOUT_STRATEGY` | It's the core experiment |
| Backfill is "latest N" instead of "last 300 s" | Closes the race and gives a sensible timeline after a follow |
| Cursor is the tweetId | UUIDv7 is time-ordered and unique, so there are no same-millisecond ties |
| `POST /tweets/by-authors` | Large author lists don't fit in a URL |
| Text counted in code points | Java and Postgres count the same way |
| AWS: one EC2 instance, SSM only, optional RDS | $0 on the Free plan, nothing public, real AWS practice |
| Apache Kafka image instead of Bitnami | Bitnami's free images moved (verify) |
| Own tweets in the home timeline, pulled on read (`INCLUDE_OWN_TWEETS`) | Easier testing and read-your-own-writes; pulling costs one author in an existing query, with no extra fan-out. The profile route stays for "my tweets only". |
| Public repo, no secrets committed | Only local dev defaults are in git; real values live in `.env` (gitignored) |

### Resolved questions

1. The home timeline includes the viewer's own tweets (pulled on read, switchable). `GET /users/{id}/tweets` shows only that user's tweets.
2. The repo is public on GitHub. No secrets are committed (see §6.7).
3. Region: `ap-south-1` (Mumbai).
4. Spring Boot 4.1.2 is fixed. Check the Boot 4 starter names (webmvc, kafka, data-redis, jdbc) when you write the POM (verify before use).
