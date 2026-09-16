# circuit-breaker-order-service

Order intake for the circuit breaker demo, and the *caller* half of it. Every order is checked against
[`../circuit-breaker-inventory-service`](../circuit-breaker-inventory-service) before it is confirmed,
which makes this service's availability depend on someone else's.

`InventoryClient.checkStock` carries a Resilience4j `@CircuitBreaker`: once inventory has failed
enough, the breaker opens and further orders are rejected **without the call ever leaving this
service**.

## How it works

```
POST /v1/orders ──► OrderController ──► OrderServiceImpl
                                              │
                                     InventoryClient.checkStock   ◄── @CircuitBreaker("inventory")
                                              │  (RestClient, JDK HttpClient, 2s connect / 3s read)
                                              │
                            breaker OPEN? ──yes──► checkStockFallback ──► 503 in ~1ms, no call made
                                              │
                                              no
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

Java 21 · Spring Boot 4 · Spring MVC · RestClient · Resilience4j · Spring Data JPA · H2 (in-memory) · Bean Validation · MapStruct · Lombok

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

## Circuit breaker

The breaker guards the single remote call. Its configuration lives under `resilience4j.circuitbreaker.instances.inventory`:

| Property | Value | Why |
|---|---|---|
| `sliding-window-type` | `COUNT_BASED` | The Resilience4j default: 50% of the last N calls, no clock involved. Deterministic to drive by hand, unlike `TIME_BASED` where the window empties while you type the next command. |
| `sliding-window-size` | `10` | How many calls the rate is computed over. |
| `minimum-number-of-calls` | `5` | **The default is 100**, which is why most hand-run demos never trip. |
| `failure-rate-threshold` | `50` | Open once half the window failed. |
| `wait-duration-in-open-state` | `10s` | How long it stays OPEN before probing again. |
| `permitted-number-of-calls-in-half-open-state` | `3` | Probe calls in HALF_OPEN: all pass → CLOSED, any fails → OPEN again. |
| `automatic-transition-from-open-to-half-open-enabled` | `true` | Without it the breaker only reaches HALF_OPEN when a call arrives, so the actuator endpoint keeps reporting OPEN with no traffic and looks stuck. |
| `event-consumer-buffer-size` | `50` | Keeps enough history for `/actuator/circuitbreakerevents`. |
| `record-exceptions` | `InventoryUnavailableException` | Redundant but explicit: a 409 for missing stock is raised outside the guarded method and must never open the circuit. |

The fallback **rethrows** rather than returning a degraded `StockCheck`. Without knowing the stock an
order cannot be confirmed, and inventing an answer would either reject valid orders or accept ones
that cannot be fulfilled. **The status code does not change — the difference is in the latency.**

### Watching it

```bash
curl -s localhost:8081/actuator/circuitbreakers
# state, failureRate, bufferedCalls, failedCalls, notPermittedCalls

curl -s localhost:8081/actuator/circuitbreakerevents/inventory/STATE_TRANSITION
curl -s localhost:8081/actuator/metrics/resilience4j.circuitbreaker.state
```

The endpoint also has a write operation, so the state can be forced without causing real failures:

```bash
curl -X POST localhost:8081/actuator/circuitbreakers/inventory \
  -H 'Content-Type: application/json' -d '{"updateState":"FORCE_OPEN"}'   # or CLOSE, or DISABLE
```

> ⚠️ **`/actuator/health` will not show the breaker on Spring Boot 4.** Resilience4j's health
> auto-configuration is guarded on `org.springframework.boot.actuate.health.HealthIndicator`, which
> moved to `org.springframework.boot.health.contributor` in Boot 4, so it backs off silently. Setting
> `register-health-indicator: true` would be dead config. Read the state from `/actuator/circuitbreakers`.

> ⚠️ Boot 4 has no `spring-boot-starter-aop` — it is `spring-boot-starter-aspectj`. Without it the
> `@CircuitBreaker` annotation is ignored with no warning. If `bufferedCalls` stays at 0 after failing
> calls, the aspect never ran.

## Driving it

```bash
curl -s localhost:8081/actuator/circuitbreakers                 # CLOSED

# Inventory starts failing every stock check
curl -s -X POST localhost:8082/v1/inventory/toggle-fault        # {"faultEnabled":true}

# Five failures reach minimum-number-of-calls at a 100% failure rate
for i in $(seq 1 5); do
  curl -s -o /dev/null -w "%{http_code} %{time_total}s\n" -X POST localhost:8081/v1/orders \
    -H 'Content-Type: application/json' -d '{"productId":1,"quantity":2}'
done

curl -s localhost:8081/actuator/circuitbreakers                 # OPEN

# Still 503, but now answered without touching inventory at all
curl -s -o /dev/null -w "%{http_code} %{time_total}s\n" -X POST localhost:8081/v1/orders \
  -H 'Content-Type: application/json' -d '{"productId":1,"quantity":2}'

# Fix inventory, wait out the 10s, then three good calls close the circuit
curl -s -X POST localhost:8082/v1/inventory/toggle-fault
curl -s localhost:8081/actuator/circuitbreakers                 # HALF_OPEN after ~10s
for i in 1 2 3; do curl -s -o /dev/null -X POST localhost:8081/v1/orders \
  -H 'Content-Type: application/json' -d '{"productId":1,"quantity":2}'; done
curl -s localhost:8081/actuator/circuitbreakers                 # CLOSED
```

A 409 never opens the circuit — product 3 is seeded with zero stock, so this leaves the breaker CLOSED:

```bash
for i in $(seq 1 10); do curl -s -o /dev/null -X POST localhost:8081/v1/orders \
  -H 'Content-Type: application/json' -d '{"productId":3,"quantity":1}'; done
curl -s localhost:8081/actuator/circuitbreakers
```

Stopping the inventory process gives the same 503 through a different route: the connection is refused
rather than answered. On localhost that refusal is immediate — the 2s connect timeout only shows up
against a host that accepts the TCP connection and then stalls.

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

`InventoryClientCircuitBreakerTest` is the one test that needs a Spring context: the breaker is applied
by an AOP proxy, so without the container there is nothing to exercise but the plain try/catch. It
drives CLOSED → OPEN and asserts that the rejected call never reached the dependency.
