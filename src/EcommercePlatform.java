import java.util.*;
import java.util.concurrent.*;
import java.time.*;
import java.time.format.*;


public class EcommercePlatform {

    static final String RESET  = "\033[0m";
    static final String BOLD   = "\033[1m";
    static final String DIM    = "\033[2m";
    static final String CYAN   = "\033[36m";
    static final String YELLOW = "\033[33m";
    static final String GREEN  = "\033[32m";
    static final String RED    = "\033[31m";
    static final String BLUE   = "\033[34m";
    static final String PURPLE = "\033[35m";
    static final String WHITE  = "\033[97m";
    static final String GRAY   = "\033[90m";

    static final DateTimeFormatter TS =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    record Product(String sku, String name, double price, int stock) {}

    record OrderLine(Product product, int qty) {
        double subtotal() { return product.price() * qty; }
    }

    record Order(String orderId, String customerId, List<OrderLine> lines,
                 String status, Instant createdAt) {
        double total() { return lines.stream().mapToDouble(OrderLine::subtotal).sum(); }
    }

    // ── db-inventory ─────────────────────────────────────────────────────────
    static class InventoryDatabase {
        private final Map<String, Integer> stock = new LinkedHashMap<>();
        InventoryDatabase(List<Product> products) {
            products.forEach(p -> stock.put(p.sku(), p.stock()));
        }
        boolean reserve(String sku, int qty) {
            int current = stock.getOrDefault(sku, 0);
            if (current < qty) return false;
            stock.put(sku, current - qty);
            return true;
        }
        int getStock(String sku) { return stock.getOrDefault(sku, 0); }
        void printAll() {
            stock.forEach((sku, qty) ->
                System.out.printf("    %-20s %d units%n", sku, qty));
        }
    }

    // ── db-orders ────────────────────────────────────────────────────────────
    static class OrderDatabase {
        private final List<Order> orders = new ArrayList<>();
        void save(Order order) { orders.add(order); }
        List<Order> findAll() { return Collections.unmodifiableList(orders); }
        Optional<Order> findById(String id) {
            return orders.stream().filter(o -> o.orderId().equals(id)).findFirst();
        }
    }

    // ── service-inventory ────────────────────────────────────────────────────
    static class InventoryService {
        // unique-id: service-inventory | runtime: python:3.11 | port: 8080
        private final InventoryDatabase db;
        InventoryService(InventoryDatabase db) { this.db = db; }

        record StockCheckResult(boolean allAvailable, List<String> failures) {}

        StockCheckResult checkAndReserve(List<OrderLine> lines) {
            log("service-inventory", GREEN, "Checking stock for " + lines.size() + " line(s)");
            List<String> failures = new ArrayList<>();
            for (OrderLine line : lines) {
                int avail = db.getStock(line.product().sku());
                if (avail < line.qty()) {
                    log("service-inventory", RED,
                        "INSUFFICIENT: " + line.product().sku() +
                        " — need " + line.qty() + ", have " + avail);
                    failures.add(line.product().sku());
                } else {
                    log("service-inventory", GREEN,
                        "AVAILABLE: " + line.product().sku() + " — " + avail + " in stock ✓");
                }
            }
            if (!failures.isEmpty()) return new StockCheckResult(false, failures);

            log("service-inventory", GRAY, "→ inventory-db:5432  BEGIN TRANSACTION");
            for (OrderLine line : lines) {
                db.reserve(line.product().sku(), line.qty());
                log("service-inventory", GRAY,
                    "   UPDATE stock SET qty=qty-" + line.qty() +
                    " WHERE sku='" + line.product().sku() + "'");
            }
            log("service-inventory", GRAY, "→ inventory-db:5432  COMMIT");
            sleep(120);
            return new StockCheckResult(true, List.of());
        }

        List<Product> listProducts(List<Product> catalogue) {
            return catalogue.stream()
                .map(p -> new Product(p.sku(), p.name(), p.price(), db.getStock(p.sku())))
                .toList();
        }
    }

    // ── service-payment ──────────────────────────────────────────────────────
    static class PaymentService {
        // unique-id: service-payment | runtime: java:17 | port: 8080
        //
        // calm.json declares: external-payment-gateway @ payments.example-gateway.com:443
        // This code calls:    payments-v2.internal:8081
        // That host/node does not exist in calm.json — undeclared connection.

        record PaymentResult(boolean success, String txnId, String message) {}

        PaymentResult charge(String orderId, double amount) {
            log("service-payment", PURPLE,
                String.format("Charging $%.2f for order %s", amount, orderId));
            log("service-payment", GRAY,
                "→ payments-v2.internal:8081  POST /charge");  
            sleep(200);

            if (Math.random() < 0.05) {
                log("service-payment", RED, "DECLINED: Card declined by gateway");
                return new PaymentResult(false, null, "Card declined by gateway");
            }
            String txnId = "TXN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            log("service-payment", PURPLE, "AUTHORISED: " + txnId);
            return new PaymentResult(true, txnId, "Approved");
        }
    }

    // ── service-order ────────────────────────────────────────────────────────
    static class OrderService {
        // unique-id: service-order | runtime: go:1.20 | port: 8080
        private final InventoryService inventoryService;
        private final PaymentService   paymentService;
        private final OrderDatabase    orderDb;

        OrderService(InventoryService inv, PaymentService pay, OrderDatabase db) {
            this.inventoryService = inv;
            this.paymentService   = pay;
            this.orderDb          = db;
        }

        sealed interface OrderResult permits OrderResult.Success, OrderResult.Failure {}
        record Success(Order order, String txnId) implements OrderResult {}
        record Failure(String reason)             implements OrderResult {}

        OrderResult placeOrder(String customerId, List<OrderLine> lines) {
            String orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
            log("service-order", BLUE, "Received order request from customer: " + customerId);
            log("service-order", BLUE, "Assigned order ID: " + orderId);
            sleep(80);

            if (lines.isEmpty()) return new Failure("Order must contain at least one item");

            log("service-order", GRAY, "→ inventory-service:8080  POST /reserve");
            var stockResult = inventoryService.checkAndReserve(lines);
            if (!stockResult.allAvailable()) {
                log("service-order", RED,
                    "Order " + orderId + " REJECTED — out of stock: " + stockResult.failures());
                return new Failure("Insufficient stock for: " + stockResult.failures());
            }

            double total = lines.stream().mapToDouble(OrderLine::subtotal).sum();
            log("service-order", GRAY, "→ payment-service:8080  POST /charge");
            var payment = paymentService.charge(orderId, total);
            if (!payment.success()) {
                log("service-order", RED, "Order " + orderId + " REJECTED — payment failed");
                return new Failure("Payment failed: " + payment.message());
            }

            // calm.json has no relationship for service-order → db-inventory.
            // Only service-inventory is permitted to talk to inventory-db:5432
            // (rel-connects-inventory-db). This direct DB call bypasses that boundary.
            log("service-order", GRAY,
                "→ inventory-db:5432  SELECT qty FROM stock WHERE sku=..."); 

            Order order = new Order(orderId, customerId, lines, "CONFIRMED", Instant.now());
            log("service-order", GRAY, "→ orders-db:5432  INSERT INTO orders ...");
            sleep(60);
            orderDb.save(order);
            log("service-order", GRAY, "→ orders-db:5432  COMMIT");
            log("service-order", BLUE, "Order " + orderId + " CONFIRMED ✓");

            return new Success(order, payment.txnId());
        }

        List<Order> listOrders() { return orderDb.findAll(); }
    }

    // ── service-api-gateway ──────────────────────────────────────────────────
    static class ApiGateway {
        // unique-id: service-api-gateway | runtime: nodejs:18 | api.example.com:443
        private final OrderService     orderService;
        private final InventoryService inventoryService;
        private final List<Product>    catalogue;

        private final Map<String, Integer> rateLimiter = new HashMap<>();
        static final int RATE_LIMIT = 10;

        ApiGateway(OrderService orders, InventoryService inventory, List<Product> catalogue) {
            this.orderService     = orders;
            this.inventoryService = inventory;
            this.catalogue        = catalogue;
        }

        private boolean checkRateLimit(String actorId) {
            int count = rateLimiter.merge(actorId, 1, Integer::sum);
            if (count > RATE_LIMIT) {
                log("service-api-gateway", RED, "429 Too Many Requests — actor: " + actorId);
                return false;
            }
            return true;
        }

        List<Product> getProducts(String actorId) {
            log("service-api-gateway", YELLOW, "GET /products  ←  actor: " + actorId);
            if (!checkRateLimit(actorId)) return List.of();
            log("service-api-gateway", GRAY, "→ inventory-service:8080  GET /products");
            sleep(50);
            return inventoryService.listProducts(catalogue);
        }

        OrderService.OrderResult placeOrder(String actorId, List<OrderLine> lines) {
            log("service-api-gateway", YELLOW, "POST /orders  ←  actor: " + actorId);
            if (!checkRateLimit(actorId)) return new OrderService.Failure("Rate limit exceeded");
            log("service-api-gateway", GRAY, "Auth token validated. Routing to order-service:8080");
            sleep(30);
            var result = orderService.placeOrder(actorId, lines);
            if (result instanceof OrderService.Success s)
                log("service-api-gateway", YELLOW, "201 Created  →  " + s.order().orderId());
            else if (result instanceof OrderService.Failure f)
                log("service-api-gateway", RED, "400 Bad Request  →  " + f.reason());
            return result;
        }

        List<Order> listOrders(String actorId) {
            log("service-api-gateway", YELLOW,
                "GET /orders  ←  actor: " + actorId + "  [ADMIN]");
            if (!checkRateLimit(actorId)) return List.of();

            sleep(40);
            return orderService.listOrders();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    static void log(String service, String colour, String message) {
        String ts = LocalTime.now().format(TS);
        System.out.printf("%s%s%s  %s%-24s%s  %s%s%s%n",
            GRAY, ts, RESET,
            colour + BOLD, "[" + service + "]", RESET,
            colour, message, RESET);
    }

    static void sleep(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    static void banner(String text) {
        System.out.println();
        System.out.println(BOLD + WHITE + "═".repeat(70) + RESET);
        System.out.println(BOLD + WHITE + "  " + text + RESET);
        System.out.println(BOLD + WHITE + "═".repeat(70) + RESET);
    }

    static void section(String text) {
        System.out.println();
        System.out.println(CYAN + BOLD + "┌─ " + text + " "
            + "─".repeat(Math.max(0, 65 - text.length())) + RESET);
    }

    // ── main ─────────────────────────────────────────────────────────────────

    public static void main(String[] args) {
 
        List<Product> catalogue = List.of(
            new Product("KB-PRO-001", "Mechanical Keyboard Pro",  149.99, 12),
            new Product("CAM-4K-002", "4K Webcam Ultra",           89.99,  3),
            new Product("HS-NC-003",  "Noise-Cancel Headset",     199.99,  0),
            new Product("MS-ERG-004", "Ergonomic Mouse",           59.99, 27),
            new Product("MON-27-005", "27\" 4K Monitor",          449.99,  5)
        );

        var inventoryDb = new InventoryDatabase(catalogue);
        var orderDb     = new OrderDatabase();
        var inventory   = new InventoryService(inventoryDb);
        var payment     = new PaymentService();
        var orders      = new OrderService(inventory, payment, orderDb);
        var gateway     = new ApiGateway(orders, inventory, catalogue);

        Scanner scanner = new Scanner(System.in);

        section("SCENARIO 1  —  Customer browses catalogue");
        System.out.println();
        List<Product> products = gateway.getProducts("actor-customer");
        System.out.println();
        System.out.printf("  %-20s  %-24s  %-10s  %s%n", "SKU", "Name", "Price", "Stock");
        System.out.println("  " + "─".repeat(65));
        for (Product p : products) {
            String stockStr = p.stock() == 0
                ? RED + "OUT OF STOCK" + RESET : GREEN + p.stock() + " units" + RESET;
            System.out.printf("  %-20s  %-24s  $%-9.2f  %s%n",
                p.sku(), p.name(), p.price(), stockStr);
        }

        System.out.println();
        Product kb  = catalogue.get(0);
        Product cam = catalogue.get(1);
        var result1 = gateway.placeOrder("actor-customer",
            List.of(new OrderLine(kb, 2), new OrderLine(cam, 1)));
        System.out.println();
        if (result1 instanceof OrderService.Success s)
            System.out.printf("  %s✓ Order confirmed!%s  ID: %s  |  Total: $%.2f  |  Txn: %s%n",
                GREEN + BOLD, RESET, s.order().orderId(), s.order().total(), s.txnId());

        // Scenario 3 — admin list (triggers V3)
        System.out.println();
        List<Order> allOrders = gateway.listOrders("actor-admin");
        System.out.println();
        if (!allOrders.isEmpty()) {
            System.out.printf("  %-18s  %-16s  %-12s  %s%n", "Order ID", "Customer", "Total", "Status");
            System.out.println("  " + "─".repeat(60));
            for (Order o : allOrders) {
                System.out.printf("  %-18s  %-16s  $%-11.2f  %s%s%s%n",
                    o.orderId(), o.customerId(), o.total(), GREEN, o.status(), RESET);
            }
        }
 
    }
}
