# AGENTS.md — rules for anyone (human or agent) writing code here

This repo is a learning project. Read this file first, then only the DESIGN.md sections your task names.

## Workflow
- Work on **one BACKLOG.md task at a time**. Do only that task's Scope. If it conflicts with DESIGN.md, stop and ask.
- Read DESIGN.md by section (for example §4.4 routes, §4.9 timeline). Don't load the whole file unless you need to.
- Don't add dependencies, modules, or config keys that aren't in DESIGN.md or the task.
- Build and test only the module you touch: `./mvnw -q -pl services/<name> -am verify`.
- Format before you finish: `./mvnw -q spotless:apply`.
- Never commit secrets. `.env*` files are gitignored, except `.env.example`.
- When you're done, say which "Done when" check you ran and what it printed.

## Stack (fixed)
Java 21 · Spring Boot 4.1.2 · Maven multi-module with wrapper · Spring MVC · JdbcClient · StringRedisTemplate · KafkaTemplate/@KafkaListener (String JSON) · RestClient · uuid-creator (UUIDv7) · JUnit 5 + MockMvc + AssertJ.
Not allowed: JPA/Hibernate, Lombok, WebFlux/WebClient, Flyway, MapStruct, Feign.

## Layout
```text
twitter-clone/
├── AGENTS.md  CLAUDE.md  DESIGN.md  BACKLOG.md  EXPERIMENTS.md  README.md
├── pom.xml  mvnw  mvnw.cmd  .mvn/
├── .gitignore  .env.example  .editorconfig
├── docker-compose.yml
├── infra/postgres/01-init-databases.sql
├── scripts/  seed.sh  smoke.sh
├── libs/common/                         # only: Ids (UUIDv7), X-User-Id resolution
└── services/
    ├── user-service/   tweet-service/   timeline-service/   fanout-worker/
```

Each module uses the standard Maven layout. Base package: `io.github.<github-user>.twitter.<module>`, with no dashes (`userservice`, `tweetservice`, `timelineservice`, `fanoutworker`, `common`).

```text
services/user-service/
├── pom.xml
└── src/
    ├── main/java/io/github/<github-user>/twitter/userservice/
    │   ├── UserServiceApplication.java
    │   ├── HealthController.java
    │   ├── user/                       # feature package
    │   │   ├── UserController.java
    │   │   ├── UserService.java
    │   │   ├── UserRepository.java
    │   │   ├── User.java               # record: one table row
    │   │   ├── CreateUserRequest.java  # record: API input
    │   │   └── UserResponse.java       # record: API output
    │   ├── follow/
    │   │   ├── FollowController.java  FollowService.java  FollowRepository.java
    │   │   ├── FollowResult.java      FollowChangedEvent.java  FollowEventPublisher.java
    │   └── config/
    │       └── AppProperties.java      # @ConfigurationProperties record
    ├── main/resources/  application.yml  schema.sql
    └── test/java/io/github/<github-user>/twitter/userservice/user/UserControllerTest.java
```

## Code rules
- **Package by feature**, not by layer. There are no `controller/`, `service/`, or `repository/` folders. A feature package holds its controller, service, repository, and records.
- **Keep things package-private by default.** Only API records, the application class, and `common` types are `public`.
- **Flow:** Controller (HTTP only) → Service (rules, `@Transactional`, events after commit) → Repository (SQL only).
- **Constructor injection** with `private final` fields. No `@Autowired` on fields.
- **Records** for DTOs, events, table rows, and config. Use enums with `switch` expressions for closed sets, for example `FanoutStrategy`.
- **SQL** goes in text blocks inside the repository, with named parameters. Read rows into records with `.query(Row.class)`. Use `OffsetDateTime` for `TIMESTAMPTZ` and `UUID` for ids.
- **Errors:** throw `ResponseStatusException` with the status from DESIGN §4.4, and enable `spring.mvc.problemdetails.enabled=true`. Don't build a custom exception hierarchy.
- **Validation:** explicit checks in the service. Text length uses `codePointCount`.
- **Config:** environment variables are bound to an `AppProperties` record. No scattered `@Value`.
- **Logging:** SLF4J, using only the key=value lines from DESIGN §4.11, plus errors.
- **Naming:** `XxxController`, `XxxService`, `XxxRepository`, `XxxRequest`, `XxxResponse`, `XxxEvent`, `XxxClient` (RestClient wrapper).
- **Comments:** only explain *why*. No Javadoc on obvious methods.
- **Tests:** they mirror the main package. Use `@WebMvcTest(XxxController.class)` with `@MockitoBean` for the service, and `MockRestServiceServer` for clients. Name tests like `create_returns409_whenHandleTaken`.

## Sample: `POST /users` (imports omitted; check Boot 4 test package names)
```java
// user/CreateUserRequest.java
public record CreateUserRequest(String handle, String displayName) {}

// user/UserResponse.java
public record UserResponse(UUID id, String handle, String displayName) {
    static UserResponse from(User u) { return new UserResponse(u.id(), u.handle(), u.displayName()); }
}

// user/User.java — one row of table users
record User(UUID id, String handle, String displayName, long followerCount,
            long followingCount, boolean isCelebrity, OffsetDateTime createdAt) {}

// user/UserController.java
@RestController
@RequestMapping("/users")
class UserController {
    private final UserService users;
    UserController(UserService users) { this.users = users; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    UserResponse create(@RequestBody CreateUserRequest request) {
        return UserResponse.from(users.create(request));
    }
}

// user/UserService.java
@Service
class UserService {
    private static final Pattern HANDLE = Pattern.compile("^[a-z0-9_]{3,30}$");
    private final UserRepository repository;
    UserService(UserRepository repository) { this.repository = repository; }

    User create(CreateUserRequest request) {
        String handle = request.handle() == null ? "" : request.handle().toLowerCase(Locale.ROOT);
        String name = request.displayName() == null ? "" : request.displayName().strip();
        int nameLength = name.codePointCount(0, name.length());
        if (!HANDLE.matcher(handle).matches() || nameLength < 1 || nameLength > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid handle or displayName");
        }
        var user = new User(Ids.newId(), handle, name, 0, 0, false, OffsetDateTime.now(ZoneOffset.UTC));
        try {
            repository.insert(user);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "handle taken");
        }
        return user;
    }
}

// user/UserRepository.java
@Repository
class UserRepository {
    private final JdbcClient jdbc;
    UserRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    void insert(User u) {
        jdbc.sql("""
                INSERT INTO users (id, handle, display_name, created_at)
                VALUES (:id, :handle, :displayName, :createdAt)
                """)
            .param("id", u.id()).param("handle", u.handle())
            .param("displayName", u.displayName()).param("createdAt", u.createdAt())
            .update();
    }

    Optional<User> findById(UUID id) {
        return jdbc.sql("SELECT * FROM users WHERE id = :id").param("id", id).query(User.class).optional();
    }
}

// common: io.github.<github-user>.twitter.common.Ids
public final class Ids {
    private Ids() {}
    public static UUID newId() { return UuidCreator.getTimeOrderedEpoch(); }   // UUIDv7
    public static long millis(UUID v7) { return v7.getMostSignificantBits() >>> 16; }
}

// test: user/UserControllerTest.java
@WebMvcTest(UserController.class)
class UserControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean UserService users;

    @Test
    void create_returns201() throws Exception {
        var id = Ids.newId();
        when(users.create(any())).thenReturn(new User(id, "ada", "Ada", 0, 0, false, OffsetDateTime.now()));
        mvc.perform(post("/users").contentType(APPLICATION_JSON).content("""
                {"handle":"Ada","displayName":"Ada"}"""))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.handle").value("ada"));
    }
}
```
