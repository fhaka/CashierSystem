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

## Till keyboard and customer display

- **Search:** scan a barcode (the scanner's Enter adds it) or type part of a product name; the matches appear under the field, the arrow keys choose and Enter adds. `3*` before a barcode or name sells 3. Typing anywhere on the till goes into the search field.
- **Keys:** F1 list of shortcuts, F2 search, F3 customer, F4 quantity of the selected line, F6 discount, F7 park cart, F8 parked carts, F9 clear the invoice, F10 pay. Arrow keys select a line, `+`/`-` add or take one, Delete removes it. In the payment window F10 fills in the exact cash and a second F10 finishes the sale; F9 puts it all on card; banknote buttons (500 ... 10000) fill the cash. On the receipt Enter prints and Esc closes.
- **Customer display** (*Ekrani i klientit*): opens a second window for the screen facing the customer, showing the lines, the total, and after payment the change and the shop's message. Drag it to the second monitor and press F11 for full screen. It works in the same browser as the till and needs no login.

## Shop settings and receipts

- **Settings** (*Cilësimet*, administrator): shop name, address, city, NIPT, phone, email and a message for the bottom of the receipt, shown on every receipt, refund and X/Z report; paper width (58 mm = 32 characters, 80 mm = 42 or 48), the receipt printer, and automatic printing after each sale. A preview shows the result before saving. A new installation works with defaults until this is filled in.
- **Receipts** are laid out for the paper width with amounts aligned on the right.
- **Reprints** (*Printo kuponin* in the sales log) give the receipt exactly as it was printed, marked `*** KOPJE ***` at the top and bottom, and every reprint is written to the audit log. Sales made before this version get their receipt rebuilt from the sale.

## Payments, shifts and reports

- **Checkout opens the payment window first.** The customer can pay with LEK cash, foreign cash (EUR/USD) and card, in any combination. The sale is only saved when the payment covers the total; change is given in LEK cash. Card payments are in LEK and cannot exceed the total. Payments are printed on the receipt.
- **Exchange rates** are stored on the server and edited by managers in the till's rate fields; cashiers see them read-only. Foreign cash is converted with the *buy* rate; prices are shown in foreign currency with the *sell* rate.
- **Every sale belongs to the shift** of the cashier who made it.
- **Cash in / out:** money put into or taken out of the drawer outside of sales is recorded with a reason on the Operations screen.
- **Closing a shift** is blind: the cashier enters the counted cash before seeing what was expected. Expected cash = opening cash + cash received − change given + cash in − cash out. Card payments are not expected in the drawer.
- **X report** (one shift) and **Z report** (one day, all tills): sales, discounts, VAT per rate, payments per method and currency, change, cash in/out and totals per cashier. Both can be printed on the receipt printer. Managers can print an X report while the shift is open; cashiers see their own after closing it.

## Reports and analysis

- **Reports** (*Raportet*, managers) are calculated on the server for any period (today, this week, this month, last month, this year or custom dates): revenue after refunds, VAT, cost of goods, gross profit and margin, number of sales, average sale, discounts and refunds.
- **Charts:** revenue by day (by month for long periods), net sales vs VAT, payment methods, busy hours, sales per cashier and per category, best sellers.
- **Products table:** quantity, revenue, profit and margin of every product sold in the period, as best sellers or slow movers. **Dead stock:** products in stock that did not sell at all in the period, sorted by the money tied up in them.
- **Profit** = revenue without VAT − cost without VAT. The cost is the purchase price saved on each sale line; set `pos.reports.cost-includes-vat=false` if your purchase prices are entered without VAT.
- **Exports:** the analysis as Excel (one sheet per table) or PDF, all sales of a period as Excel, and the Z report as PDF.
- **Z report by email:** set `pos.report.email.to` (comma-separated addresses) and the `spring.mail.*` settings of your mail server (see `application.properties`); the Z report is then sent every evening at 23:30 (`pos.report.email.cron`) with the PDF attached, and the *Send by email* button appears on the Reports screen.
- **Sales log** (*Regjistri i shitjeve*) is searched and paged on the server (50 per page) by period, invoice number and cashier, with the total of everything that matches. Cashiers only see their own sales.
- **Home screen** shows today's sales and revenue (a cashier's own), active products and low stock.

## Promotions and customers

- **Promotions** (*Promocionet*, managers): a percentage off, or *buy X get Y free* (pieces only), for one product or a whole category, optionally limited to dates, days of the week and hours (happy hour). The till applies them by itself; when several match a line, the best one wins. They show on screen and on the receipt.
- **Discount order:** promotions on each line, then the manual discount on the whole cart, then the automatic large-purchase discount. Every discount is attached to a line, so VAT is calculated on what was paid.
- **Manual discount** (*Zbritje %* at the till): a percentage off the whole cart. Cashiers need a manager's PIN above `pos.discount.cashier-limit-percent` (default 0, so always).
- **Customers** (*Klientët*): loyalty card number (scannable; generated if left empty), name, phone, email, notes. Attach the customer to the sale with *Klienti* (scan the card or search by name or phone).
- **Loyalty points:** one point per `pos.loyalty.lek-per-point` LEK paid (default 100); points pay part of a sale at `pos.loyalty.point-value` LEK each (default 1). A refund takes back the points it earned.
- **Buying on credit ("në borxh"):** customers with a credit limit (set by managers) can pay all or part of a sale *on account*, within the limit. Debt payments are taken in the customer's screen; cash goes into the current shift's drawer. Refunds of such sales can go back onto the account. Each customer has a full history of points and debt.
- Shift closing and X/Z reports show sales on account and points used; neither counts as cash in the drawer.

## Inventory and suppliers

- **Suppliers** (*Furnitorët*): name, NIPT, phone, email, address, notes. A purchase invoice is linked to its supplier (type the name; a new name creates the supplier). Payments to suppliers are recorded, and each supplier shows invoiced, paid and **owed**.
- **Minimum stock / reorder quantity** per product. *Inventari → Porositë* lists products at or below their minimum, grouped by the supplier they were last bought from, with a suggested quantity (the reorder quantity, or enough to reach twice the minimum) and estimated cost.
- **Stock adjustments** (*Produktet → Gjendja*): damaged, expired, lost, internal use or other (with a note), with history per product. Stock can never go negative.
- **Stock count** (*Inventari → Numërimi*): start a count, scan each product and type what is on the shelf, check the differences, then apply. Only counted products change; each difference is recorded as a count correction.
- **Expiry dates** can be entered per line of a purchase invoice. *Inventari → Skadimet* lists deliveries expiring soon that are probably still on the shelf (estimated: older deliveries are assumed sold first).
- **Scale labels:** EAN-13 barcodes starting with `pos.scale.prefixes` (default 21–29) are read as `PP IIIII VVVVV C`; the product is registered with the first 7 digits, and VVVVV is the weight in grams (`pos.scale.mode=WEIGHT`) or the price in LEK (`PRICE`). The check digit is verified.
- **CSV import/export** (*Produktet*): export opens in Excel (`;`, UTF-8). Import matches products by barcode, creates or updates them, accepts `;` or `,` and decimal commas, and reports bad lines by number. Required columns: `barcode`, `name`, `price`; optional: `category`, `unit`, `purchase_price`, `tax_rate`, `stock`, `min_stock`, `reorder_quantity`.

## Approvals, refunds and audit log

- **Manager PIN:** each Super Cashier / Super Admin can have a personal approval PIN (4–8 digits, set in the Users screen; two managers cannot share one). When a cashier does something sensitive, the till asks for a manager's PIN; the server checks it and records who approved. Managers approve their own actions. After 5 wrong PINs, approvals on that till pause for 5 minutes.
- **What needs approval for cashiers:** lowering a quantity, removing a line or clearing the cart (voids, `pos.approval.voids=true`), changing a price at the till, and refunds.
- **Refunds:** *Sales Log → Refund* (or *Refund* in a sale's details) finds the sale by invoice number and shows what can still be returned. The customer gets back what they actually paid for those items, including their share of any discount. Stock goes back on the shelf, the money leaves the current shift (cash from the drawer, or recorded as a card refund), and a refund receipt `R000001` is printed. X and Z reports show refunds and net sales, with VAT corrected.
- **Audit log** (Super Admin): sign-ins (also failed ones), locked accounts, wrong PINs, voids, price changes, refunds, product/user/exchange-rate changes, purchases, shifts and cash in/out, with who did it, who approved it and the details. Filter by date and action. Entries are never changed or deleted.

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

If empty, the default Windows printer is used. The printer chosen on the Settings screen takes its place. Receipts are sent as raw ESC/POS with an automatic paper cut. The printer can also be chosen on each receipt.

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
| Cart & sales | `GET/POST/DELETE /sales/cart`, `PUT /sales/cart/{productId}`, `GET /sales/cart/subtotal`, `POST /sales/checkout`, `GET /sales?from&to&invoice&cashierName&page&size`, `GET /sales/{id}` |
| Shifts | `GET /shifts`, `GET /shifts/open`, `POST /shifts/open`, `POST /shifts/{id}/close` |
| Purchases | `GET /purchases`, `POST /purchases` |
| Reports | `GET /reports/dashboard`, `/reports/analytics?from&to` (+ `.xlsx`, `.pdf`), `/reports/sales.xlsx?from&to`, `/reports/daily?date` (+ `.pdf`), `POST /reports/daily/email?date`, `GET /reports/email-settings` |
| Users | `GET /users`, `POST /users`, `PUT /users/{id}` |
| Settings | `GET /settings/shop`, `PUT /settings/shop` (administrator), `POST /settings/shop/preview`; reprint: `GET /sales/{id}/receipt` |
| Printer | `GET /printer/printers`, `POST /printer/receipt`, `POST /printer/test-cut` |
| Backups | `GET /backups`, `POST /backups/run` |
| Sale logs (JDBC) | `GET/POST /sales/logs`, `GET/PUT/DELETE /sales/logs/{id}` |

## Course requirements

The mapping of the original course requirements (R1–R10) to the code is in `Requirenments_Fulfilled.txt`.
