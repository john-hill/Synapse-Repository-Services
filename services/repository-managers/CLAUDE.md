# services/repository-managers

Business logic layer — Manager interfaces and implementations that sit between controllers and DAOs. This module enforces authorization, validation, and transaction boundaries.

## Package Structure

```
org.sagebionetworks.repo.manager
├── (root)           # Core managers: EntityManager, UserManager, NodeManager, etc.
├── asynch/          # Async job framework (AsynchJobStatusManager, AsyncJobRunner)
├── entity/          # Entity authorization
├── file/            # File handle operations
├── table/           # Table/view managers
├── schema/          # JSON Schema managers
├── grid/            # Grid/Curator managers
├── agent/           # AI agent managers
├── config/          # Spring @Configuration classes
└── ...              # Many more domain sub-packages
```

## Manager Pattern

### Interface + Impl

```java
// Interface — defines the contract
public interface EntityManager {
    Entity getEntity(UserInfo userInfo, String entityId) throws NotFoundException, UnauthorizedException;
}

// Implementation — @Service, constructor injection, transaction annotations
@Service
public class EntityManagerImpl implements EntityManager {
    private final NodeManager nodeManager;
    private final EntityAuthorizationManager entityAuthorizationManager;
    // ... more dependencies

    // Constructor injection (preferred over @Autowired fields)
    public EntityManagerImpl(NodeManager nodeManager, EntityAuthorizationManager authManager, ...) {
        this.nodeManager = nodeManager;
        this.entityAuthorizationManager = authManager;
    }
}
```

### Authorization

Check access before performing operations:

```java
entityAuthorizationManager.hasAccess(userInfo, entityId, ACCESS_TYPE.READ)
    .checkAuthorizationOrElseThrow();
```

- Returns `AuthorizationStatus` with `.checkAuthorizationOrElseThrow()`
- Throws `UnauthorizedException` on failure
- Every public method that accepts `UserInfo` should check authorization

### Input Validation

```java
ValidateArgument.required(userInfo, "userInfo");
ValidateArgument.required(entityId, "entityId");
ValidateArgument.requiredNotBlank(name, "name");
ValidateArgument.requiredNotEmpty(list, "list");
```

## Transaction Annotations

Defined in `org.sagebionetworks.repo.transactions`. Applied on **implementation methods**, not interfaces.

| Annotation | Behavior |
|-----------|----------|
| `@WriteTransaction` | Joins existing transaction or creates a new one. Standard for most write operations. |
| `@MandatoryWriteTransaction` | **Requires** an existing transaction — throws if none exists. Used for methods that must be called within an outer transaction. |
| `@NewWriteTransaction` | Always creates a **new, independent** transaction (suspends any existing one). Used for operations that must commit independently (e.g., updating job progress). |

Read-only operations have no transaction annotation (default Spring behavior).

## Async Job Framework

For long-running operations exposed as async REST endpoints:

1. **Define request/response schemas** in `lib-auto-generated` (extend `AsynchronousRequestBody` / `AsynchronousResponseBody`)
2. **Implement `AsyncJobRunner<Req, Resp>`** in the manager layer:
   ```java
   @Service
   public class MyAsyncWorker implements AsyncJobRunner<MyRequest, MyResponse> {
       public MyResponse run(Long jobId, UserInfo user, MyRequest request, JobCancelCallback cancelCallback) {
           // Do work, return response
       }
   }
   ```
3. **Wire in worker config** — add a `@Bean` method in `AsyncJobWorkersConfig` that wraps the runner with `AsyncJobRunnerAdapter` and a `WorkerTriggerBuilder`
4. **Controller** calls `asynchJobStatusManager.startJob(userInfo, request)` to enqueue, client polls `getJobStatus()`

## Spring Configuration

- Managers use `@Service` annotation — discovered via component scan
- Constructor injection preferred (fields are `private final`)
- **Preferred**: Add new bean definitions to `ManagerConfiguration` (`org.sagebionetworks.repo.manager.config.ManagerConfiguration`)
- **Legacy**: Spring XML configs (`*-spb.xml` in `src/main/resources/`) still used for some beans and `MigrationTypeListener` registration (`managers-spb.xml`). Do not add new XML configs.
- Controllers access managers through `ServiceProvider` (not direct injection)

## Testing

- Unit tests: `@ExtendWith(MockitoExtension.class)` with `@Mock` and `@InjectMocks`
- Mock DAOs and other managers, verify interactions
- Test authorization failures (verify `UnauthorizedException` thrown)
- Test input validation (verify `IllegalArgumentException` thrown)
- Integration tests in `integration-test/` module test the full stack
