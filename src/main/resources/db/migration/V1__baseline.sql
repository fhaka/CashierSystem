-- Baseline schema: matches the tables Hibernate created before Flyway was introduced.
-- Existing databases are baselined at this version and skip this script.

create table backup_logs (
    created_at datetime(6) not null,
    id bigint not null auto_increment,
    message varchar(1000),
    file_path varchar(255) not null,
    status varchar(255) not null,
    primary key (id)
);

create table cashiers (
    active boolean default true not null,
    id bigint not null auto_increment,
    full_name varchar(255) not null,
    password_hash varchar(255) not null,
    username varchar(255) not null,
    role varchar(20) default 'SUPER_ADMIN' not null,
    primary key (id)
);

create table categories (
    id bigint not null auto_increment,
    name varchar(255) not null,
    primary key (id)
);

create table products (
    price decimal(10,2) not null,
    purchase_price decimal(10,2) not null,
    stock integer not null,
    tax_rate decimal(5,2) not null,
    category_id bigint,
    id bigint not null auto_increment,
    barcode varchar(255) not null,
    name varchar(255) not null,
    unit varchar(255) not null,
    primary key (id)
);

create table purchase_invoices (
    invoice_date date not null,
    total_amount decimal(12,2) not null,
    id bigint not null auto_increment,
    company varchar(255) not null,
    invoice_number varchar(255) not null,
    primary key (id)
);

create table purchase_items (
    line_total decimal(12,2) not null,
    purchase_price decimal(10,2) not null,
    quantity integer not null,
    selling_price decimal(10,2) not null,
    tax_rate decimal(5,2) not null,
    id bigint not null auto_increment,
    invoice_id bigint not null,
    product_id bigint not null,
    unit varchar(255) not null,
    primary key (id)
);

create table sale_items (
    price decimal(10,2) not null,
    price_without_tax decimal(10,2) not null,
    quantity integer not null,
    tax_amount decimal(10,2) not null,
    tax_rate decimal(5,2) not null,
    id bigint not null auto_increment,
    product_id bigint not null,
    sale_id bigint not null,
    unit varchar(255) not null,
    primary key (id)
);

create table sales (
    total_amount decimal(10,2) not null,
    cashier_id bigint,
    date datetime(6) not null,
    id bigint not null auto_increment,
    primary key (id)
);

create table shifts (
    closing_cash decimal(12,2),
    difference decimal(12,2),
    expected_cash decimal(12,2),
    opening_cash decimal(12,2) not null,
    total_sales decimal(12,2),
    cashier_id bigint not null,
    closed_at datetime(6),
    id bigint not null auto_increment,
    opened_at datetime(6) not null,
    status varchar(255) not null,
    primary key (id)
);

create table sale_logs (
    id bigint auto_increment primary key,
    sale_id bigint not null,
    message varchar(255) not null,
    created_at timestamp not null
);

alter table cashiers add constraint UK95v170u8i5fajrdpcb5kqvevd unique (username);
alter table categories add constraint UKt8o6pivur7nn124jehx7cygw5 unique (name);
alter table products add constraint UKqfr8vf85k3q1xinifvsl1eynf unique (barcode);
alter table purchase_invoices add constraint UK5liy2dbo5i8bfli5lcyoafg87 unique (invoice_number);

alter table products add constraint FKog2rp4qthbtt2lfyhfo32lsw9 foreign key (category_id) references categories (id);
alter table purchase_items add constraint FKejetwahxl1ljkx2wy53iocukj foreign key (invoice_id) references purchase_invoices (id);
alter table purchase_items add constraint FKbwtjp8gfcre77l1mxverb6up0 foreign key (product_id) references products (id);
alter table sale_items add constraint FK8g0sjiqs7tg055o06p6wawu39 foreign key (product_id) references products (id);
alter table sale_items add constraint FK7tcpbc5c5mpnm8fl2phl8ep7l foreign key (sale_id) references sales (id);
alter table sales add constraint FK4pmg6b3srya9ujaf8s38pxt9u foreign key (cashier_id) references cashiers (id);
alter table shifts add constraint FK3qfm41abgtcdm5obu0i60fn4g foreign key (cashier_id) references cashiers (id);
