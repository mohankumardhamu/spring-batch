# Spring Batch Microservices

Four Spring Boot 3.5 / Java 21 microservices that simulate a real-world deployment, where every
service runs as its own process on its own port with its own PostgreSQL database:

| Service           | Port | Database    | Responsibility                                                        |
|-------------------|------|-------------|-----------------------------------------------------------------------|
| `user-service`    | 8081 | `userdb`    | User CRUD + bulk user generation using **virtual threads**            |
| `order-service`   | 8082 | `orderdb`   | Order CRUD                                                            |
| `payment-service` | 8083 | `paymentdb` | Payment CRUD                                                          |
| `batch-service`   | 8084 | `batchdb`   | **Spring Batch** job that propagates users → orders → payments over HTTP |
| `postgres`        | 5432 | –           | PostgreSQL 16, one database per service (created by `docker/postgres/init.sql`) |

## Architecture

```mermaid
flowchart LR
    Client([Postman / curl])

    subgraph batch-service :8084
        PC[PropagationController] --> JOB[[Spring Batch<br/>propagateUsersJob]]
    end

    subgraph user-service :8081
        UC[UserController]
        UGC[UserGenerationController<br/>virtual threads]
    end
    subgraph order-service :8082
        OC[OrderController]
    end
    subgraph payment-service :8083
        PYC[PaymentController]
    end

    Client --> PC & UC & UGC & OC & PYC
    JOB -- "1. GET /api/users/{id} or /by-email" --> UC
    JOB -- "2. POST /api/orders" --> OC
    JOB -- "3. POST /api/payments" --> PYC

    UC & UGC --> UDB[(userdb)]
    OC --> ODB[(orderdb)]
    PYC --> PDB[(paymentdb)]
    JOB --> BDB[(batchdb)]
```

Services never share a database. `order.userId` and `payment.orderId` / `payment.userId` are plain
ids that point at other services (no cross-database foreign keys), just like in a real microservice
system. The batch-service only talks to the others through their REST APIs.

## Tech stack

- Java 21, Spring Boot 3.5, Spring Web, Spring Data JPA, Bean Validation, Actuator
- Spring Batch 5 (job repository stored in `batchdb`)
- PostgreSQL 16
- Docker (one multi-stage `Dockerfile` for all services) + Docker Compose
- Virtual threads: `spring.threads.virtual.enabled=true` in every service (Tomcat requests run on
  virtual threads), plus explicit `Executors.newVirtualThreadPerTaskExecutor()` in the user generator
  and a virtual-thread `TaskExecutor` for launching batch jobs asynchronously

## Running with Docker (recommended)

Prerequisites: Docker with Compose v2. No local Java/Maven needed — the images are built inside Docker.

```bash
docker compose up -d --build      # build all 4 images and start everything
docker compose ps                 # check status
docker compose logs -f batch-service

# health checks
curl localhost:8081/actuator/health   # user-service
curl localhost:8082/actuator/health   # order-service
curl localhost:8083/actuator/health   # payment-service
curl localhost:8084/actuator/health   # batch-service

docker compose down               # stop (data is kept in the postgres-data volume)
docker compose down -v            # stop and DELETE all data
```

Rebuild a single service after a code change:

```bash
docker compose up -d --build order-service
```

Postgres credentials default to `postgres` / `postgres`; override them with `POSTGRES_USER`,
`POSTGRES_PASSWORD` (and the host port with `POSTGRES_PORT`) in your shell or in a `.env` file.

> `docker/postgres/init.sql` only runs when the volume is first created. If you change it, run
> `docker compose down -v` first.

## Running locally (without Docker for the apps)

Prerequisites: JDK 21 and Maven 3.9+.

```bash
docker compose up -d postgres        # just the database
mvn clean package -DskipTests        # builds all modules

java -jar user-service/target/user-service.jar &
java -jar order-service/target/order-service.jar &
java -jar payment-service/target/payment-service.jar &
java -jar batch-service/target/batch-service.jar &
```

Every setting has a `localhost` default, so no configuration is needed. Environment variables you can override:

| Variable | Used by | Default |
|---|---|---|
| `SERVER_PORT` | all | 8081 / 8082 / 8083 / 8084 |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | all | `jdbc:postgresql://localhost:5432/<service db>`, `postgres`, `postgres` |
| `DB_POOL_SIZE` | all | user 30, order 20, payment 20, batch 40 in docker-compose (app defaults: 30 / 10 / 10 / 40) |
| `USER_SERVICE_URL`, `ORDER_SERVICE_URL`, `PAYMENT_SERVICE_URL` | batch | `http://localhost:8081` / `8082` / `8083` |
| `PROPAGATION_CHUNK_SIZE` | batch | 100 in docker-compose (app default 10) |
| `PROPAGATION_CONCURRENCY` | batch | 32: chunks processed in parallel |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | all | `ecs` in docker-compose (JSON logs for Filebeat); unset = plain text |

## Monitoring (Grafana, Kibana, Prometheus, Elasticsearch)

A separate Multipass VM runs the monitoring stack. Full details are in
[`monitoring/README.md`](monitoring/README.md).

```bash
monitoring/vm.sh create                     # create the VM and deploy the stack; prints URLs and passwords
docker compose --profile monitoring up -d   # app + Filebeat (logs) + postgres-exporter (DB metrics)
monitoring/vm.sh deploy                     # after changing anything in monitoring/vm/
```

- **Metrics:** every service exposes `/actuator/prometheus`, which Prometheus on the VM scrapes. That
  covers HTTP, JVM, DB pool and Spring Batch metrics, plus business counters such as
  `propagation_users_total`, `orders_placed_total` and `users_added_total`.
- **Logs:** in Docker, the services log ECS JSON. Filebeat ships them to Elasticsearch, and you can
  search them in Kibana (data view *Spring Batch logs*) or Grafana.
- **Dashboard:** Grafana's home page is *Spring Batch microservices*. It's generated from
  `monitoring/vm/grafana/generate_dashboards.py`.

## Postman collection

Import [`postman/spring-batch-microservices.postman_collection.json`](postman/spring-batch-microservices.postman_collection.json).

- Base URLs are collection variables (`userServiceUrl`, `orderServiceUrl`, `paymentServiceUrl`, `batchServiceUrl`).
- The folders are meant to be run top to bottom (or with the Collection Runner): each *Create*
  request saves the new id (`userId`, `orderId`, `paymentId`, `propagationRequestId`) into a variable
  used by the following requests.
- Every request has a test checking the expected status code.
- Each folder has *Count* requests (`GET .../count`). They sit before the delete request, so the
  data you just created is still there to count.

## API reference

All errors use the RFC 7807 `ProblemDetail` format, e.g.

```json
{ "status": 400, "title": "Bad Request", "detail": "Validation failed",
  "errors": { "email": "must be a well-formed email address" } }
```

List endpoints are paginated: `?page=0&size=20&sort=id,desc`, and return
`{ "content": [...], "page": { "size", "number", "totalElements", "totalPages" } }`.

### User service — `http://localhost:8081`

| Method | Path | Description | Success |
|---|---|---|---|
| POST | `/api/users` | Create a user | 201 |
| GET | `/api/users/{id}` | Get by id | 200 |
| GET | `/api/users/by-email?email=` | Get by email (case-insensitive) | 200 |
| GET | `/api/users` | List (paged) | 200 |
| GET | `/api/users/ids?afterId=0&limit=1000` | Ids and emails of the next `limit` (≤ 10,000) users after `afterId`, ordered by id. Keyset pagination, fast at any depth | 200 |
| GET | `/api/users/count` | Total number of users → `{"resource":"users","total":2001}` | 200 |
| PUT | `/api/users/{id}` | Update | 200 |
| DELETE | `/api/users/{id}` | Delete | 204 |

```json
{ "name": "Mohan Kumar", "email": "mohan@example.com", "phone": "+91 9876543210" }
```
`name` and `email` are required; `phone` is optional. Emails are case-insensitive: they are
stored in lowercase and must be unique (409 on duplicates). Storing them lowercase lets the by-email
lookup use the unique index, which matters at millions of rows: about 5 ms instead of a full table scan.

### User generator (virtual threads) — `http://localhost:8081`

| Method | Path | Description | Success |
|---|---|---|---|
| POST | `/api/users/generate` | Generate and insert N random users concurrently | 201 |

```json
{ "count": 1000, "emailPrefix": "load" }
```
Response:
```json
{ "requested": 7882999, "created": 7882999, "failed": 0, "durationMs": 22421, "rowsPerSecond": 351589,
  "chunks": 789, "chunkSize": 10000, "concurrency": 26,
  "threadModel": "virtual-thread-per-chunk", "sampleErrors": [] }
```

`count` can be 1 to 10,000,000 per call. Emails look like `<emailPrefix>.<random tag>.<n>@example.com`, so
repeated calls never collide.

How it scales to millions:

- The users are split into **chunks of 10,000**. Each chunk runs on its **own virtual thread**
  (`Executors.newVirtualThreadPerTaskExecutor()`) and is inserted in **one transaction with JDBC batch
  inserts**. `reWriteBatchedInserts=true` makes the PostgreSQL driver turn each batch into a multi-row
  `INSERT`. Saving JPA entities one by one can't be batched with IDENTITY ids, and was about 25 times slower.
- A **semaphore sized to the connection pool** (pool size − 4, leaving connections for normal API
  traffic) limits how many chunks run at once. A chunk only builds its rows once it has a slot, so
  memory stays flat even for 10 million users.
- Measured on a 12-core machine: **10 million users ≈ 30 s** (about 350k rows/s). That's 2.2 GB in
  Postgres, including the indexes.

**Fill the database with 10 million users:**

```bash
curl -X POST localhost:8081/api/users/generate -H 'Content-Type: application/json' \
     -d '{"count":10000000,"emailPrefix":"bulk"}'
curl localhost:8081/api/users/count      # {"resource":"users","total":10000000}
```

The request is synchronous and returns when every user is inserted. Postman waits for it with no
timeout by default. Progress is logged every 5% (`docker compose logs -f user-service`).

### Order service — `http://localhost:8082`

| Method | Path | Description | Success |
|---|---|---|---|
| POST | `/api/orders` | Create an order | 201 |
| GET | `/api/orders/{id}` | Get by id | 200 |
| GET | `/api/orders?userId=` | List (paged), optional filter by user | 200 |
| GET | `/api/orders/count?userId=` | Total orders plus a count per status, optionally for one user | 200 |
| PUT | `/api/orders/{id}` | Update | 200 |
| DELETE | `/api/orders/{id}` | Delete | 204 |

```json
{ "userId": 1, "productName": "Laptop", "quantity": 2, "unitPrice": 45000.50, "status": "CREATED" }
```
`totalAmount` is calculated as `quantity × unitPrice`. `status` is optional:
`CREATED` (default), `CONFIRMED`, `PAID`, `SHIPPED`, `DELIVERED`, `CANCELLED`.

### Payment service — `http://localhost:8083`

| Method | Path | Description | Success |
|---|---|---|---|
| POST | `/api/payments` | Create a payment | 201 |
| GET | `/api/payments/{id}` | Get by id | 200 |
| GET | `/api/payments?orderId=&userId=` | List (paged), optional filter by order or user | 200 |
| GET | `/api/payments/count?orderId=&userId=` | Total payments, count per status and summed `totalAmount`, optionally for one order or user | 200 |
| PUT | `/api/payments/{id}` | Update | 200 |
| DELETE | `/api/payments/{id}` | Delete | 204 |

```json
{ "orderId": 1, "userId": 1, "amount": 90001.00, "method": "UPI", "status": "PENDING" }
```
`method`: `CREDIT_CARD`, `DEBIT_CARD`, `UPI`, `NET_BANKING`, `WALLET`, `CASH_ON_DELIVERY`.
`status` is optional: `PENDING` (default), `COMPLETED`, `FAILED`, `REFUNDED`.
A unique `transactionRef` (e.g. `TXN-5C161B7C6F814086A9B5`) is generated on create.

Example count responses (statuses with no rows are listed as 0):
```json
GET /api/orders/count?userId=2
{ "resource": "orders", "userId": 2, "total": 2,
  "byStatus": { "CREATED": 2, "CONFIRMED": 0, "PAID": 0, "SHIPPED": 0, "DELIVERED": 0, "CANCELLED": 0 } }

GET /api/payments/count
{ "resource": "payments", "userId": null, "orderId": null, "total": 11,
  "byStatus": { "PENDING": 0, "COMPLETED": 11, "FAILED": 0, "REFUNDED": 0 }, "totalAmount": 5489.00 }
```

### Propagation (Spring Batch) — `http://localhost:8084`

| Method | Path | Description | Success |
|---|---|---|---|
| POST | `/api/propagate` | Start a propagation job for the given user ids/emails (async) | 202 |
| POST | `/api/propagate/bulk` | Propagate the **next N users that haven't been propagated yet** (async), see [Bulk propagation](#bulk-propagation) | 202 |
| GET | `/api/propagate/{requestId}` | Status and counts (poll this) | 200 |
| GET | `/api/propagate/{requestId}/items?status=` | Result for each user, optional filter `SUCCESS`/`FAILED`/`PENDING` | 200 |
| POST | `/api/propagate/{requestId}/retry` | Run the failed items again | 202 / 409 |
| GET | `/api/propagate` | List requests, newest first | 200 |
| GET | `/api/propagate/count` | Requests per status, and user items per status (`SUCCESS`/`FAILED`/`PENDING`) | 200 |

**Counts** across all propagation requests:

```json
GET /api/propagate/count
{ "resource": "propagationRequests", "total": 5,
  "byStatus": { "SUBMITTED": 0, "RUNNING": 0, "COMPLETED": 3, "COMPLETED_WITH_ERRORS": 2, "FAILED": 0 },
  "itemTotal": 14, "itemsByStatus": { "PENDING": 0, "SUCCESS": 11, "FAILED": 3 } }
```

**1. Submit** user ids and/or emails (at least one, up to 10,000; duplicates are removed):

```bash
curl -X POST localhost:8084/api/propagate -H 'Content-Type: application/json' \
     -d '{"userIds":[1,2,3],"emails":["mohan@example.com"]}'
```
```json
{ "requestId": 1, "type": "MANUAL", "jobExecutionId": 1, "status": "SUBMITTED", "totalItems": 4,
  "requestedCount": null, "statusUrl": "http://localhost:8084/api/propagate/1",
  "itemsUrl": "http://localhost:8084/api/propagate/1/items" }
```

**2. Poll** the status until it is `COMPLETED`, `COMPLETED_WITH_ERRORS` or `FAILED`:

```json
{ "requestId": 1, "type": "MANUAL", "status": "COMPLETED_WITH_ERRORS", "jobExecutionId": 1,
  "jobName": "propagateUsersJob", "jobStatus": "COMPLETED", "jobExitCode": "COMPLETED",
  "requestedCount": null, "totalItems": 4, "attempts": 1,
  "succeeded": 3, "failed": 1, "pending": 0, "durationMs": 350, "usersPerSecond": 11, ... }
```

**3. Inspect** the result for each user:

```json
{ "identifierType": "USER_ID", "identifier": "2", "status": "SUCCESS",
  "userId": 2, "userEmail": "...", "orderId": 3, "orderAmount": 499.00,
  "paymentId": 3, "paymentStatus": "COMPLETED", "transactionRef": "TXN-...",
  "failedStage": null, "errorMessage": null }
```
```json
{ "identifierType": "USER_ID", "identifier": "999999", "status": "FAILED",
  "failedStage": "USER_LOOKUP",
  "errorMessage": "user-service responded 404: {... \"detail\":\"User not found with id 999999\" ...}" }
```

### Bulk propagation

Give a count instead of ids. The job selects the **next N users that haven't been propagated yet**
from user-service and creates an order and a payment for each:

```bash
curl -X POST localhost:8084/api/propagate/bulk -H 'Content-Type: application/json' -d '{"count":100000}'
```
```json
{ "requestId": 12, "type": "BULK", "jobExecutionId": 15, "status": "SUBMITTED",
  "totalItems": 0, "requestedCount": 100000,
  "statusUrl": "http://localhost:8084/api/propagate/12", "itemsUrl": "http://localhost:8084/api/propagate/12/items" }
```

`totalItems` is 0 until the job has selected the users. Then poll the same status, items and retry
endpoints as for a manual request:

```json
GET /api/propagate/12
{ "requestId": 12, "type": "BULK", "status": "RUNNING", "jobName": "bulkPropagateUsersJob",
  "requestedCount": 100000, "totalItems": 100000, "succeeded": 41070, "failed": 0, "pending": 58930,
  "durationMs": 24000, "usersPerSecond": 1711, ... }
```

- `count` is 1 to 10,000,000.
- **No user is propagated twice.** A saved position (the `bulk_propagation_cursor` table) holds the
  highest user id bulk propagation has claimed, and each call continues after it. Calling
  `{"count":1000}` repeatedly walks through all 10 million users in order. Users that already have a
  `SUCCESS` or `PENDING` item from any request (including manual `/api/propagate` calls) are skipped.
- **Concurrent bulk calls are safe.** Selection locks the saved position row (`SELECT … FOR UPDATE`),
  so two requests running at the same time never claim the same users. The second one waits until
  the first has finished selecting.
- **Fewer users than requested:** if the end of the users table is reached, the request propagates
  what was left (`totalItems` < `requestedCount`). Once everyone has been propagated, `totalItems` is 0.
- **Throughput** on a 12-core machine running everything (4 services and Postgres): **about 1,700
  users/s**, so 100k users take about 1 minute and 1 million about 10 minutes. Each user is 2 HTTP
  calls and 2 database commits, in two other services. The limit is then this one machine's CPU and disk.

## How the batch job works

```
POST /api/propagate                          POST /api/propagate/bulk {"count": N}
  │ saves request + 1 item per identifier      │ saves a BULK request (no items yet)
  ▼                                            ▼
propagateUsersJob                            bulkPropagateUsersJob
 │                                            ├─ selectUsersStep (tasklet, one transaction)
 │                                            │    lock cursor → GET user-service /api/users/ids?afterId=
 │                                            │    (keyset pages of 10k) → skip already-propagated users
 │                                            │    → batch-INSERT N items (userId + email filled in)
 │                                            │    → move the cursor forward
 └─ propagateStep ◄───────────────────────────┘
     chunk-oriented, multi-threaded: 32 chunks in parallel on virtual threads, 100 items per chunk
     ├─ reader     JdbcPagingItemReader  → the request's items, keyset-paged by id (thread-safe)
     ├─ processor  CompositeItemProcessor
     │     1. UserLookupProcessor        GET  user-service    /api/users/{id} | /by-email
     │                                   (skipped when userId is already known, e.g. bulk items)
     │     2. OrderCreationProcessor     POST order-service   /api/orders     (sample order)
     │     3. PaymentCreationProcessor   POST payment-service /api/payments   (amount = order total)
     └─ writer     JdbcBatchItemWriter   → one batched UPDATE per chunk with ids / status / error
PropagationJobListener → sets the request to RUNNING, then COMPLETED / COMPLETED_WITH_ERRORS / FAILED
```

Both jobs run asynchronously on a virtual thread, and `POST` returns 202 immediately. A retry always
runs `propagateUsersJob` on the existing items. The exception is a bulk request whose selection step
never finished: its retry runs the selection again.

- **The default order and payment** come from `batch-service/src/main/resources/application.yml`:

  ```yaml
  propagation:
    sample-order: { product-name: Sample Product, quantity: 1, unit-price: 499.00 }
    payment:      { method: UPI, status: COMPLETED }
  ```
- **One failing user doesn't stop the job.** If a hop fails, that item is marked `FAILED` with
  `failedStage` (`USER_LOOKUP`, `ORDER_CREATION`, `PAYMENT_CREATION`) and the error message. The job
  carries on with the other users.
- **Remote calls are retried.** Connection errors and 5xx responses are retried up to 3 times with
  backoff (`services.http.*`). 4xx responses (e.g. user not found) fail immediately.
- **Retry resumes from the hop that failed.** Every hop stores its result on the item (`userId`,
  `orderId`, `paymentId`). `POST /retry` skips `SUCCESS` items, and for a user whose order was already
  created but whose payment failed, it only creates the payment. No duplicate order is created.
- **Restarts are safe.** If batch-service stops while a job is running, Spring Batch would leave that
  execution `STARTED` forever. On startup, `OrphanedJobRecovery` marks such executions and their
  requests `FAILED`, so you can call `/retry`.
- **The parallel step is tuned for millions of rows.** The reader and writer use plain JDBC with keyset
  paging (`WHERE id > :last ORDER BY id`) and batched `UPDATE`s. The JPA repository reader/writer would
  use `OFFSET` paging and a select plus an update per item. Note: Spring Batch 5 limits a
  multi-threaded step to **4 concurrent chunks** unless `throttleLimit` is set, whatever the executor
  allows. `propagateStep` sets it to `propagation.concurrency`.
- **Connection budget:** each running chunk holds a batch-service connection (pool 40 ≥ concurrency
  32). Across all services the pools add up to 110 connections, so docker-compose starts Postgres with
  `max_connections=200` (the default is 100).
- Spring Batch metadata (`BATCH_JOB_EXECUTION`, `BATCH_STEP_EXECUTION`, …) lives in `batchdb`, next to
  the `propagation_request` / `propagation_item` tables.

## Project layout

```
.
├── pom.xml                     # parent POM (multi-module)
├── Dockerfile                  # shared multi-stage build:  --build-arg SERVICE=<module>
├── docker-compose.yml
├── docker/postgres/init.sql    # creates userdb, orderdb, paymentdb, batchdb
├── postman/                    # Postman collection
├── monitoring/                 # monitoring VM (see monitoring/README.md)
│   ├── vm.sh                   #   create | deploy | status | urls | destroy
│   ├── cloud-init.yaml         #   VM bootstrap (Docker)
│   ├── app/filebeat.yml        #   runs next to the app (compose profile "monitoring")
│   └── vm/                     #   deployed to the VM: compose, prometheus/, grafana/, elastic/
├── user-service/      controller/ service/ repository/ entity/ dto/ exception/
├── order-service/     controller/ service/ repository/ entity/ dto/ exception/
├── payment-service/   controller/ service/ repository/ entity/ dto/ exception/
└── batch-service/     controller/ service/ repository/ entity/ dto/ exception/
                       batch/   (job config, bulk selection tasklet, processors, listener,
                                 orphan recovery)
                       client/  (RestClient wrappers for user/order/payment services)
                       config/  (typed properties, RestClient beans)
```

## Notes and limitations

This is a demo setup. Before production you'd want to change the following:

- **Schema management:** tables are created by Hibernate `ddl-auto: update`. Use Flyway or Liquibase in production.
- **No distributed transactions:** if batch-service crashes *after* order-service has created an
  order but *before* the chunk is committed, the item doesn't know that order id. A retry then
  creates a second order. Fixing this properly needs idempotency keys on order-service and payment-service.
- **Single batch-service instance:** orphan recovery assumes one batch-service instance. With several
  instances, a node could mark another node's running job as failed.
- **No authentication or authorization** on any endpoint.
- **Large tables and paging:** at 10 million users, `GET /api/users/count` and each page of
  `GET /api/users` take about 0.25 s, because Spring Data counts the whole table to report
  `totalElements`. Deep pages are slower still (page 400,000 ≈ 1.8 s), because the database has to
  skip every row before that page. Fetching by id or by email stays at about 5–10 ms. To page through
  everything, use keyset pagination (`WHERE id > :lastId`).
- **Bulk propagation only moves forward:** the saved position never moves back. A user who failed in
  a bulk request is picked up again only by that request's `/retry`. Users created later get higher ids,
  so later bulk calls include them.
- **User deletion doesn't cascade** to orders or payments, because they live in other services.
