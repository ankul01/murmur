# Murmur — Learning Backlog

Built from DESIGN.md. Each task adds one idea and leaves the system runnable. Code follows AGENTS.md.

- **Done when:** one curl command plus one small MockMvc test. Worker tasks use a `redis-cli` or Kafka console check instead of MockMvc.
- **Tests for services that call other services:** stub the other services with `MockRestServiceServer`.
- **Before every commit:** check that no secrets are staged (DESIGN §6.7).

---

## Part A — Users and follows

### T00 · Repo conventions and agent rules
- **New idea:** One short rules file fixes the folder layout, package structure, and coding style for you and every coding agent.
- **Depends on:** —
- **Scope:**
  - `AGENTS.md` (layout, package-by-feature, code rules, sample)
  - `CLAUDE.md` containing `@AGENTS.md`, so Claude Code loads the same rules
  - `.editorconfig`, `.gitignore`, `.env.example`, `.cursorignore`
  - `README.md` with the "Agent skills" table; optionally install those skills
- **Done when:** `AGENTS.md` and `CLAUDE.md` are committed. Ask your agent "Where does `FollowService` go, and is it public?" and it answers `services/user-service/src/main/java/com/murmur/userservice/follow/FollowService.java`, package-private.
- **Learn:** package-by-feature, package-private boundaries, Java 21 records.
- **Out:** any Java code (T01 starts that).

### T01 · Parent POM and a booting user-service
- **New idea:** A Maven reactor with one parent POM builds one Spring Boot jar per service.
- **Depends on:** T00
- **Scope:**
  - Root `pom.xml` (Java 21, Boot 4.1.2, Maven wrapper with `distributionSha256Sum`, Spotless with palantir-java-format; verify plugin versions)
  - `services/user-service` with `GET /health`
- **Done when:** `./mvnw package` builds `services/user-service/target/app.jar`, and `curl localhost:8001/health` returns `{"status":"UP"}`. MockMvc test for `/health`.
- **Learn:** reactor builds, Boot 4 starter names (verify), `finalName`.
- **Out:** database, Docker.

### T02 · Compose with an empty Postgres
- **New idea:** Docker Compose runs infrastructure, and an init script creates one database per service.
- **Depends on:** T01
- **Scope:** `docker-compose.yml` with `postgres` only, plus `infra/postgres/01-init-databases.sql` (`users`, `posts`)
- **Done when:** `docker compose exec postgres psql -U murmur -l` lists `users` and `posts`.
- **Learn:** init scripts run only on a fresh volume (`down -v`).
- **Out:** Redis, Kafka, tables.

### T03 · Create and get users
- **New idea:** A service owns its table through `schema.sql` and `JdbcClient`, using ids it generates itself.
- **Depends on:** T02
- **Scope:**
  - Shared module with the UUIDv7 helper
  - `users` table
  - `POST /users`, `GET /users/{id}`
- **Done when:** `curl -X POST localhost:8001/users -d '{"handle":"Ada","displayName":"Ada"}'` → `201` with a UUIDv7 id and handle `ada`. Repeating it → `409`. MockMvc test for a bad handle → `400`.
- **Learn:** `spring.sql.init`, UUIDv7 layout.
- **Out:** follows, the `X-User-Id` header.

### T04 · Follow and unfollow write path
- **New idea:** One transaction inserts the follow, updates both counts, and sets `is_celebrity`, and repeats change nothing.
- **Depends on:** T03
- **Scope:**
  - Shared `X-User-Id` handling (401)
  - `follows` table
  - `POST` and `DELETE /users/{id}/follow`
- **Done when:** following twice returns `changed:true`, then `changed:false`. `GET /users/{id}` shows `followerCount:1`. With `CELEBRITY_FOLLOWER_THRESHOLD=1`, it shows `isCelebrity:true`. MockMvc test: self-follow → `400`.
- **Learn:** `ON CONFLICT DO NOTHING`, rows affected, why the flag changes only on writes.
- **Out:** events, list routes.

### T05 · User read routes
- **New idea:** Internal list and batch routes give other services the data they need without cross-database joins.
- **Depends on:** T04
- **Scope:** `GET /users/{id}/followers?cursor=&limit=`, `GET /users/{id}/following`, `GET /users?ids=`
- **Done when:** with 3 followers and `limit=2`, `followers` returns 2 ids plus a `nextCursor`, and the next page returns 1. MockMvc test: `ids` with more than 100 values → `400`.
- **Learn:** keyset pagination by follower id.
- **Out:** callers of these routes.

## Part B — Posts

### T06 · Create a post
- **New idea:** post-service owns the `posts` table and counts text length in code points.
- **Depends on:** T03
- **Scope:** `services/post-service`, `posts` table, `POST /posts`
- **Done when:** `curl -X POST localhost:8002/posts -H "X-User-Id: $ADA" -d '{"text":"hi"}'` → `201`. MockMvc tests: 140 emoji → `201`, 141 characters → `400`.
- **Learn:** UTF-16 vs code points, `created_at` taken from the UUIDv7.
- **Out:** delete, reads, Kafka.

### T07 · Soft delete and batch get
- **New idea:** A soft-deleted post stays in the table but disappears from the batch read.
- **Depends on:** T06
- **Scope:** `DELETE /posts/{id}`, `GET /posts?ids=`
- **Done when:** after the owner deletes a post (`204`), `GET /posts?ids=<it>,<other>` returns only `<other>`. MockMvc test: deleting someone else's post → `404`.
- **Learn:** partial index `WHERE deleted_at IS NULL`.
- **Out:** timeline filtering.

### T08 · By-author query and profile timeline
- **New idea:** "Latest live posts by these authors, older than the cursor" is one indexed query, and it serves both the profile page and fan-out on read.
- **Depends on:** T07
- **Scope:** `POST /posts/by-authors`, `GET /users/{id}/posts?cursor=&limit=`
- **Done when:** with 25 posts, paging the profile with `limit=10` returns 10, 10, then 5 items with `nextCursor:null`. MockMvc test for the `before` cursor.
- **Learn:** postId as the cursor, `EXPLAIN` on `(user_id, id DESC)`.
- **Out:** home timeline.

## Part C — Events and fan-out on write

### T09 · Publish post.created
- **New idea:** A service publishes an event to Kafka after its database commit.
- **Depends on:** T08
- **Scope:** Kafka and `kafka-init` added to Compose; post-service sends `post.created`, keyed by `authorId`.
- **Done when:** `docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:19092 --topic post.created --from-beginning` shows the event after a curl post.
- **Learn:** listeners (`localhost:9092` vs `kafka:19092`), keys and partitions, the dual-write gap.
- **Out:** consumers.

### T10 · Publish follow.changed
- **New idea:** An event is published only when state actually changed.
- **Depends on:** T05, T09
- **Scope:** user-service sends `follow.changed`, keyed by `followerId`.
- **Done when:** two identical follows produce **one** event in the console consumer, and an unfollow produces one `unfollow` event.
- **Learn:** idempotent APIs and events.
- **Out:** consumers.

### T11 · Fan-out worker: write strategy
- **New idea:** A Kafka consumer pushes each post id into every follower's Redis ZSET.
- **Depends on:** T10
- **Scope:**
  - `services/fanout-worker` and Redis added to Compose
  - `@KafkaListener` on `post.created`, using group `fanout-workers`
  - Paged followers, ZADD, trim
- **Done when:** after Grace follows Ada and Ada posts, `redis-cli ZRANGE timeline:$GRACE 0 -1 WITHSCORES` shows the post id with its millisecond score.
- **Learn:** consumer groups, idempotent ZADD, `TIMELINE_MAX_LENGTH` trim.
- **Out:** reading the timeline, backfill, strategies.

### T12 · Timeline read
- **New idea:** The home timeline is a Redis range, then a post batch, then a user batch, paged by postId.
- **Depends on:** T11
- **Scope:** `services/timeline-service`, `GET /timeline?cursor=&limit=` (write strategy only)
- **Done when:** Grace's `curl localhost:8004/timeline` shows Ada's post with handle and display name. With 25 posts and `limit=10`, three pages come back with no duplicates. MockMvc test with stubbed user and post services.
- **Learn:** hydration, why the cursor is the postId.
- **Out:** filtering, pull.

### T13 · Filtering deletes and unfollows on read
- **New idea:** Stale ZSET entries are dropped when the timeline is read, instead of being cleaned out of Redis.
- **Depends on:** T12
- **Scope:** drop ids missing from the batch and authors not in `following`; overfetch.
- **Done when:** after a delete and an unfollow, the timeline omits both, while `redis-cli ZSCORE` still finds the members.
- **Learn:** short pages that still return a `nextCursor`.
- **Out:** pruning Redis.

### T14 · Follow backfill
- **New idea:** On follow, the worker copies the followee's latest N posts into the follower's ZSET.
- **Depends on:** T13
- **Scope:** `@KafkaListener` on `follow.changed`; `FOLLOW_BACKFILL_COUNT`.
- **Done when:** Ada posts 3 times, then Linus follows Ada, and Linus's timeline shows all 3.
- **Learn:** why "latest N" closes the follow/post race.
- **Out:** unfollow handling (lazy).

## Part D — Strategies

### T15 · Fan-out on read
- **New idea:** With `FANOUT_STRATEGY=read`, the worker writes nothing, and the timeline pulls posts for all followees.
- **Depends on:** T14
- **Scope:** strategy setting in the worker and timeline-service; the pull path.
- **Done when:** with `read`, a new post shows up immediately even with the worker stopped, and `redis-cli EXISTS timeline:$GRACE` stays unchanged.
- **Learn:** read cost grows with the following count.
- **Out:** celebrities.

### T16 · Own posts in the home timeline
- **New idea:** The viewer's own posts join the home timeline as one extra author in the pull path, under every strategy.
- **Depends on:** T15
- **Scope:** `INCLUDE_OWN_POSTS` (default `true`) in timeline-service; the viewer is added to the pull authors and to the unfollow-filter set.
- **Done when:** under `write` with the worker stopped, Ada posts, and her own timeline shows it immediately. With `INCLUDE_OWN_POSTS=false` it doesn't. MockMvc test with stubbed services.
- **Learn:** read-your-own-writes, and why pulling your own posts is cheaper than pushing them.
- **Out:** fan-out into your own ZSET.

### T17 · Hybrid with celebrity merge
- **New idea:** Celebrities skip push, and the timeline merges their posts on read with the ZSET.
- **Depends on:** T15
- **Scope:** `hybrid` in the worker (skip fan-out and backfill) and in the timeline (merge celebrities).
- **Done when:** with `hybrid` and threshold 1, Ada is a celebrity. Her new post is not in Grace's ZSET but does appear in Grace's timeline.
- **Learn:** merge correctness across two sources.
- **Out:** rebuild.

### T18 · Observation logs and seed data
- **New idea:** One key=value log line per fan-out and per read makes strategy costs measurable.
- **Depends on:** T17
- **Scope:** log lines (DESIGN §4.11), `scripts/seed.sh`
- **Done when:** after `seed.sh`, a `normal_author` post logs `zadds=50`, and a `celeb_author` post under `hybrid` logs `skip=celebrity`.
- **Learn:** measuring before optimizing.
- **Out:** metrics stacks.

### T19 · Smoke test and experiments
- **New idea:** One script proves the full flow, and six experiments compare the strategies.
- **Depends on:** T18
- **Scope:** `scripts/smoke.sh`; `EXPERIMENTS.md` with E1–E6 results recorded.
- **Done when:** `smoke.sh` passes under `write`, `read`, and `hybrid` (restart between runs), and EXPERIMENTS.md has the log lines for each experiment.
- **Learn:** the strategy trade-off table, backed by your own numbers.
- **Out:** Testcontainers.

## Part E — Containers and AWS ($0)

### T20 · Run the services in Compose
- **New idea:** The same jars run as containers on the Compose network, with every port bound to 127.0.0.1.
- **Depends on:** T19
- **Scope:** `apps` profile in Compose (`eclipse-temurin:21-jre`, mounted `app.jar`, small heaps)
- **Done when:** `docker compose --profile apps up -d`, then `smoke.sh` passes with no services running in the IDE.
- **Learn:** container hostnames vs localhost, the Kafka INTERNAL listener.
- **Out:** AWS.

### T21 · AWS account guardrails
- **New idea:** A Free plan account with MFA, an admin user, the CLI, and a $1 budget protects the $0 rule.
- **Depends on:** — (can be done anytime)
- **Scope:** sign-up on the Free plan, root MFA, admin user, `aws configure sso`, Session Manager plugin, Budgets alert, region `ap-south-1`.
- **Done when:** `aws sts get-caller-identity` works as the admin user, and Billing shows the budget and the credit balance.
- **Learn:** IAM vs root, Free plan limits.
- **Out:** resources.

### T22 · EC2 behind SSM only
- **New idea:** One instance with no inbound ports runs the whole stack, and you reach it only through SSM.
- **Depends on:** T20, T21
- **Scope:** IAM role and instance profile, security group with no inbound rules, AL2023 launch, Docker and Compose install, `git clone`, build, `--profile apps up`.
- **Done when:** `smoke.sh` passes inside an SSM session, a port-forward to 8004 works from the laptop, and the instance is stopped afterwards.
- **Learn:** instance roles, security groups, SSM port forwarding, which costs keep running while stopped.
- **Out:** load balancers, Elastic IPs.

### T23 · CloudWatch Logs (optional)
- **New idea:** Container logs ship to CloudWatch through the awslogs driver and the instance role.
- **Depends on:** T22
- **Scope:** log permissions on the role; `logging` in Compose; 1-day retention.
- **Done when:** a `fanout …` line is visible in CloudWatch Logs Insights.
- **Out:** metrics, alarms.

### T24 · Postgres on RDS (optional)
- **New idea:** Moving the databases to RDS changes only `DB_URL` and the security group rules.
- **Depends on:** T22
- **Scope:** RDS security group (from the EC2 security group only), smallest Free-plan instance, `sslmode=require`, `.env` on the instance.
- **Done when:** `smoke.sh` passes with the Compose `postgres` container stopped. RDS is **deleted** afterwards.
- **Learn:** private RDS, SG-to-SG rules, forced SSL, the 7-day auto-restart.
- **Out:** Multi-AZ, backups.

### T25 · Teardown
- **New idea:** A checklist leaves no billable resource behind.
- **Depends on:** whenever you finish
- **Scope:** DESIGN §7.8
- **Done when:** every checklist item is ticked, and Billing shows no running resources.

---

## Order

T00 → T01 → T02 → T03 → T04 → T05 → T06 → T07 → T08 → T09 → T10 → T11 → T12 → T13 → T14 → T15 → T16 → T17 → T18 → T19 → T20 → T22 → (T23, T24 optional) → T25

T21 has no dependencies. Do it early so the account is ready by T22.

**Start with T00.**
