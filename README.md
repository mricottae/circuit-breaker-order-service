# circuit-breaker-order-service

Order intake for the circuit breaker demo, and the *caller* half of it. Every order is checked against
[`../circuit-breaker-inventory-service`](../circuit-breaker-inventory-service) before it is confirmed,
which makes this service's availability depend on someone else's.

**There is no Resilience4j here yet, on purpose.** This is the baseline: when inventory misbehaves,
every request still travels the full path and fails. `InventoryClient.checkStock` is the single seam
where `@CircuitBreaker` goes in the next commit.

## How it works

```
POST /v1/orders ──► OrderController ──► OrderServiceImpl
                                              │
                                     InventoryClient.checkStock   ◄── the circuit breaker seam
                                              │  (RestClient, JDK HttpClient, 2s connect / 3s read)
                                              │
                    ┌─────────────────────────┼──────────────────────────┐
                    │                         │                          │
              RestClientException        inStock: false              inStock: true
                    │                         │                          │
        InventoryUnavailableException   OutOfStockException      save(CONFIRMED) ──► 201
                    │                         │
                   503                       409
```

- **Two failure modes, two status codes.** A 409 means inventory answered and there is not enough
  stock — a healthy business outcome. A 503 means inventory could not be reached. When the breaker
  arrives, only the 503 path should count as a circuit failure; a 409 must never open it.
- **`createOrder` is not `@Transactional`.** Wrapping the remote call would hold a JDBC connection for
  the whole read timeout, which is exactly what the fault injection produces. The save runs in its own
  implicit transaction.
- **Timeouts are explicit** (2s connect, 3s read). Without them a hung inventory would block the
  request forever and there would be no failure for a breaker to react to.
- **Nothing is persisted on failure.** An order only exists if inventory confirmed the stock.

## Stack

Java 21 · Spring Boot 4 · Spring MVC · RestClient · Spring Data JPA · H2 (in-memory) · Bean Validation · MapStruct · Lombok

## Run

Start the inventory service first, then this one:

```bash
cd ../circuit-breaker-inventory-service && ./mvnw spring-boot:run   # 8082
./mvnw spring-boot:run                                             # 8081
```

> ⚠️ Port 8081 is also used by `../cqrs-command-service`. Do not run both at the same time.

No Docker needed: the database is in-memory and empty on every start.

## Endpoints

| Method | Path | Description | Success |
|---|---|---|---|
| POST | `/v1/orders` | Create an order after checking stock. Body: `{"productId":1,"quantity":2}` | 201 + `Location` |
| GET | `/v1/orders` | List every order, oldest first | 200 |

```bash
curl -i -X POST localhost:8081/v1/orders \
  -H 'Content-Type: application/json' -d '{"productId":1,"quantity":2}'
# HTTP/1.1 201, Location: http://localhost:8081/v1/orders/1
# {"id":1,"productId":1,"quantity":2,"status":"CONFIRMED","createdAt":"..."}

curl -s localhost:8081/v1/orders
```

Error responses:

| Status | When |
|---|---|
| 400 | `productId` null, `quantity` not positive, or malformed JSON |
| 409 | Inventory answered, but there are not enough units |
| 503 | Inventory returned 5xx, timed out, or refused the connection |

## Seeing the baseline failure

```bash
# Inventory starts failing every stock check
curl -s -X POST localhost:8082/v1/inventory/toggle-fault      # {"faultEnabled":true}

# Every order now travels the full path and comes back 503
curl -i -X POST localhost:8081/v1/orders \
  -H 'Content-Type: application/json' -d '{"productId":1,"quantity":2}'

curl -s -X POST localhost:8082/v1/inventory/toggle-fault      # back to normal
```

Stopping the inventory process gives the same 503 through a different route: the connection is refused
rather than answered. On localhost that refusal is immediate — the 2s connect timeout only shows up
against a host that accepts the TCP connection and then stalls.

Either way, **every single request pays the full round trip**. That is the cost a circuit breaker
removes, and the reason to measure it before adding one.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `server.port` | `8081` | HTTP port |
| `app.inventory.base-url` | `http://localhost:8082` | Inventory service root |
| `app.inventory.connect-timeout` | `2s` | TCP connect timeout for the inventory call |
| `app.inventory.read-timeout` | `3s` | Response timeout for the inventory call |
| `spring.datasource.url` | `jdbc:h2:mem:orders;DB_CLOSE_DELAY=-1` | In-memory database; `DB_CLOSE_DELAY=-1` keeps the schema alive between pooled connections |
| `spring.jpa.hibernate.ddl-auto` | `update` | Schema comes from the JPA annotations; there is no Flyway and no `.sql` file |
| `spring.threads.virtual.enabled` | `true` | Virtual threads, so the blocking inventory call parks a virtual thread |

## Tests

```bash
./mvnw clean verify
```

Mockito + AssertJ unit tests for the service and the mapper, a `MockRestServiceServer` test for the
inventory client, and a `@WebMvcTest` slice covering 201, 400, 409 and 503.
