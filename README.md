# Supermarket Cashier System

Backend-focused Java 17+ demo using Spring Boot, Spring Data JPA, H2, JDBC, async operations, custom exceptions, generics, streams, lambdas, collections, and the Strategy pattern.

## Run

```powershell
mvn spring-boot:run
```

The API starts at `http://localhost:8080`.

Open the cashier screen:

```text
http://localhost:8080
```

H2 console:

- URL: `http://localhost:8080/h2-console`
- JDBC URL: `jdbc:h2:mem:supermarket`
- User: `sa`
- Password: empty

## Main Endpoints

```http
GET    /products
GET    /products/in-stock
GET    /products/search?query=bread
GET    /products/barcode/100000000001
GET    /products/sorted-by-price
GET    /products/lookup
GET    /products/sorted-map
GET    /products/{id}
POST   /products
PUT    /products/{id}
DELETE /products/{id}

GET    /sales/cart
POST   /sales/cart
GET    /sales/cart/subtotal
DELETE /sales/cart
POST   /sales/checkout
GET    /sales
GET    /sales/logs
GET    /sales/logs/{id}
POST   /sales/logs
PUT    /sales/logs/{id}
DELETE /sales/logs/{id}

POST   /auth/register
POST   /auth/login
```

## Example Requests

Create product:

```json
{
  "name": "Apples",
  "barcode": "200000000001",
  "price": 2.50,
  "stock": 40,
  "categoryName": "Food"
}
```

Add to cart:

```json
{
  "productId": 1,
  "quantity": 3
}
```

Add to cart by barcode:

```json
{
  "barcode": "100000000001",
  "quantity": 3
}
```

Register cashier:

```json
{
  "fullName": "Cashier One",
  "username": "cashier",
  "password": "1234"
}
```

Create JDBC sale log:

```json
{
  "saleId": 1,
  "message": "Manual JDBC log entry"
}
```

## Requirements Mapping

- R1 Collections: `ArrayList<CartItem>`, `HashMap<Long, Product>`, `TreeMap<String, Product>`
- R2 Generics: `ApiResponse<T>`, `FilterUtil<T>`
- R3 Lambdas: sorting and filtering products
- R4 Streams: cart subtotal and checkout calculations
- R5 Concurrency: async JDBC sale logging with `@Async` and `CompletableFuture`
- R6 JDBC: full CRUD in `JdbcLogRepository` through `/sales/logs`
- R7 JPA: `Product`, `Category`, `Sale`, `SaleItem`
- R8 REST API: product, cart, checkout, sales history endpoints
- R9 Design Pattern: `PricingStrategy`, `TaxStrategy`, `DiscountStrategy`
- R10 Exceptions: `ProductNotFoundException`, `InsufficientStockException`, global handler
