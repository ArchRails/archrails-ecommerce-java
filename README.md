# ArchRails — E-Commerce Platform Demo (Java)

A CLI demo that simulates the full CALM 1.2 architecture for the
e-commerce order-processing platform.

## Architecture implemented

```
actor-customer ──interacts──▶ service-api-gateway (nodejs:18, :443)
actor-admin    ──interacts──▶ service-api-gateway

service-api-gateway ──connects REST──▶ service-order     (go:1.20,     :8080)
service-api-gateway ──connects REST──▶ service-inventory (python:3.11, :8080)
service-order       ──connects REST──▶ service-payment   (java:17,     :8080)
service-order       ──connects SQL──▶  db-orders         (postgres:15, :5432)
service-inventory   ──connects SQL──▶  db-inventory      (postgres:15, :5432)
service-payment     ──connects HTTPS──▶ payments.example-gateway.com:443
```

All nodes are part of `system-ecommerce-platform` (kubernetes, us-west-2).

## Requirements

- Java 17 or higher  (`java -version`)

## Run

```bash
# Compile + run:
javac src/EcommercePlatform.java -d out/
java -cp out EcommercePlatform

# Or with the helper script:
chmod +x run.sh && ./run.sh
```

On Windows:
```cmd
javac src\EcommercePlatform.java -d out\
java -cp out EcommercePlatform
```

## Demo scenarios

The demo walks through 6 interactive scenarios (press ENTER to advance each):

| # | Scenario | Actor |
|---|----------|-------|
| 1 | Browse product catalogue | actor-customer |
| 2 | Place a successful order | actor-customer |
| 3 | Order rejected — out of stock | actor-customer |
| 4 | Admin views all orders | actor-admin |
| 5 | Inspect inventory DB state | (internal) |
| 6 | Second customer exhausts remaining stock | actor-customer-2 |

Each scenario prints timestamped log lines from each service, e.g.:

```
12:34:56.789  [service-api-gateway]   POST /orders  ←  actor: actor-customer
12:34:56.820  [service-api-gateway]   Auth token validated. Routing to order-service:8080
12:34:56.851  [service-order]         Received order request from customer: actor-customer
12:34:56.931  [service-inventory]     Checking stock for 2 line(s)
12:34:56.932  [service-inventory]     AVAILABLE: KB-PRO-001 — 12 in stock ✓
12:34:57.052  [service-inventory]     → inventory-db:5432  BEGIN TRANSACTION
12:34:57.174  [service-payment]       Charging $389.97 for order ORD-A1B2C3
12:34:57.374  [service-payment]       → payments.example-gateway.com:443  POST /charge
12:34:57.576  [service-payment]       AUTHORISED: TXN-F9E8D7C6
12:34:57.638  [service-order]         → orders-db:5432  INSERT INTO orders ...
12:34:57.700  [service-api-gateway]   201 Created  →  ORD-A1B2C3
```

## Project structure

```
ecommerce-demo/
├── README.md
├── run.sh                       # convenience script (Linux/macOS)
└── src/
    └── EcommercePlatform.java   # single-file: all services + models + demo
```

## Design notes

- **No external dependencies** — pure Java stdlib only
- **Sealed interfaces** for `OrderResult` (Success / Failure) — idiomatic Java 17
- **Records** for all domain models (Product, Order, OrderLine, PaymentResult…)
- In-memory `InventoryDatabase` and `OrderDatabase` simulate the two Postgres stores
- Each service class maps 1-to-1 with a CALM node (`unique-id`, runtime, host:port)
- `ApiGateway` enforces auth routing and a per-actor rate limiter
- `PaymentService` simulates a ~5% decline rate from the external gateway
# archrails-ecommerce-java
