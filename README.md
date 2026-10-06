# Haka Market POS

Supermarket cashier system: selling at the till with a barcode scanner, shifts and cash reconciliation, products and stock, supplier purchase invoices, reports, users with roles, thermal receipt printing and automatic backups.

Built with Java 17+, Spring Boot 3, Spring Data JPA, MySQL and Flyway. The cashier screen is plain HTML/CSS/JS served by the same application.

## Requirements

- Java 17 or newer
- Maven 3.9+
- MySQL 8 (not needed for the demo mode below)

## Quick try (no MySQL)

Starts with an in-memory database and demo products. Everything is lost when it stops.

```powershell
mvn spring-boot:run -Dspring-boot.run.profiles=h2
```

Open `http://localhost:8081`. On first start, register the first account: it becomes the **Super Admin**.

## Shop installation (MySQL)

1. Create the database and a dedicated MySQL user. Edit the password in the script first:

   ```powershell
   mysql -u root -p < docs/mysql-setup.sql
   ```

2. Create `src/main/resources/application-local.properties` (it is git-ignored, never commit it):

   ```properties
   spring.datasource.username=haka_pos
   spring.datasource.password=the-password-from-step-1
   ```

   Alternatively set the environment variables `MYSQL_USER`, `MYSQL_PASSWORD` and optionally `MYSQL_DATABASE`.

3. Start the app:

   ```powershell
   mvn spring-boot:run
   ```

   The app listens on port **8081** on all network interfaces, so other tills on the shop network can open `http://<server-ip>:8081`.

### Database schema

The schema is managed by **Flyway** migrations in `src/main/resources/db/migration`. They run automatically on startup.

- A new, empty database is built from `V1__baseline.sql` onwards.
- A database created by older versions of the app (before Flyway) is detected and marked as version 1 without touching existing data. Only newer migrations are applied.
- Never edit a migration that has already run in a shop. Add a new `V<next>__description.sql` file instead.

Hibernate does not change tables (`ddl-auto=none`). The tests run with `ddl-auto=validate`, so the build fails if the entities and migrations ever differ.

## Roles

| Role | Can do |
|------|--------|
| Cashier | Sell, open/close own shift, see own sales |
| Super Cashier | Everything a cashier can, plus products, purchase invoices, reports, change prices at the till |
| Super Admin | Everything, plus users and backups |

## Selling

- **Weighed products** (`kg`) accept quantities with up to 3 decimals (0.350 kg); products sold by piece (`pcs`) need whole numbers. Stock follows the same rule.
- **Two tills, one last item:** checkout locks the product rows until the sale is saved, so stock can never be sold twice.
- **Carts** are stored in the database and survive a restart. *Park cart* puts a customer aside; *Parked carts* lets any till resume it (the till's own cart must be empty).
- **Invoice numbers** (`000001`, `000002`, ...) come from a counter locked inside the sale, so they are consecutive with no gaps. Sales made before this version got their id as number.
- **Discount:** above `pos.discount.threshold` the total gets `pos.discount.percent` off (`pos.discount.enabled=false` turns it off). The discount is spread over the lines and VAT is calculated on what was actually paid; the receipt shows VAT per rate.
- **Profit** uses the cost price saved on each sale line, so later cost changes do not rewrite history.
- **Products are deactivated, not deleted.** A deactivated product disappears from the till, stays in old sales, and can be reactivated from the Products screen.

## Security

- **Passwords** are stored with BCrypt. Accounts from older versions (SHA-256) keep working and are upgraded automatically at their next sign-in. The minimum length is 4 characters.
- **Sessions** are stored in the database (only a hash of the token), so restarting the server does not sign the tills out. A till is signed out after `pos.session.idle-timeout` (default 12 h) without activity.
- **The browser** keeps the session in an `HttpOnly`, `SameSite=Strict` cookie that page scripts cannot read. API clients can still send the token in the `X-Auth-Token` header.
- **Wrong passwords:** after `pos.login.max-attempts` (default 5) the account is locked for `pos.login.lock-duration` (default 5 minutes). A Super Admin can unlock it at once by saving the user in the Users screen.
- **Access changes:** changing a user's password or role, or disabling them, signs that user out on every till.
- **CORS** is off by default (the screen is served by the app). Other front-ends must be listed in `pos.cors.allowed-origins`.

## Language

The app is in **Albanian by default**. Each till can switch to English from the login screen or the sidebar; the choice is remembered on that computer. The server answers in the same language (the browser sends it in `Accept-Language`), including error messages and the printed receipt.

- Screen texts: `src/main/resources/static/i18n.js`. The English text is the key; add the Albanian translation next to it. Missing translations are reported in the browser console.
- Server messages and receipt labels: `messages.properties` (Albanian) and `messages_en.properties` (English).
- Dates are formatted in Albanian by the app itself, because many browsers ship without Albanian locale data.
- Receipt printers use code page CP437, which has `ë` and `ç` but not `Ë`; a capital `Ë` is printed as `E`.

## Receipt printer

Set the Windows printer name in `application.properties`:

```properties
receipt.printer.name=Ocom Printer
```

If empty, the default Windows printer is used. Receipts are sent as raw ESC/POS with an automatic paper cut. The printer can also be chosen at checkout.

## Backups

A backup runs every day at 23:00 (Europe/Tirane) and can be started manually by a Super Admin. Files are written to `backups/` next to the app. They contain sales data and password hashes: keep them private. The folder is git-ignored.

## Tests

```powershell
mvn test
```

Integration tests start the whole application on an in-memory database built by the real Flyway migrations, and cover login and roles, the full sale flow (cart, checkout, stock, shift balance), purchase invoices and the discount rule.

## API

All endpoints except `/auth/*` need the header `X-Auth-Token` with the token returned by login. Every response has the shape `{ "success": true|false, "message": "...", "data": ... }`.

| Area | Endpoints |
|------|-----------|
| Auth | `GET /auth/setup`, `POST /auth/register` (first account only), `POST /auth/login`, `POST /auth/logout` |
| Products | `GET /products`, `/products/in-stock`, `/products/search?query=`, `/products/barcode/{barcode}`, `/products/{id}`; `POST /products`, `PUT /products/{id}`, `DELETE /products/{id}` |
| Categories | `GET /categories` |
| Cart & sales | `GET/POST/DELETE /sales/cart`, `PUT /sales/cart/{productId}`, `GET /sales/cart/subtotal`, `POST /sales/checkout`, `GET /sales` |
| Shifts | `GET /shifts`, `GET /shifts/open`, `POST /shifts/open`, `POST /shifts/{id}/close` |
| Purchases | `GET /purchases`, `POST /purchases` |
| Reports | `GET /reports/sales` |
| Users | `GET /users`, `POST /users`, `PUT /users/{id}` |
| Printer | `GET /printer/printers`, `POST /printer/receipt`, `POST /printer/test-cut` |
| Backups | `GET /backups`, `POST /backups/run` |
| Sale logs (JDBC) | `GET/POST /sales/logs`, `GET/PUT/DELETE /sales/logs/{id}` |

## Course requirements

The mapping of the original course requirements (R1–R10) to the code is in `Requirenments_Fulfilled.txt`.
