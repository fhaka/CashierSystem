'use strict';

const API = window.location.origin;
let currentUser  = null;
let posProducts  = [];
let cartItems    = [];
let allProdTable = [];
let allCategories = [];
let purchaseItems = [];
let lastReceipt  = '';
let keypadBuffer = '';
let lastCheckoutData = null;
let activeShift = null;
let allUsers = [];

/*  Helpers  */
const $ = id => document.getElementById(id);
function getRates() {
  return {
    eurSale: parseFloat($('eur-sale-rate')?.value || '95.1'),
    eurBuy: parseFloat($('eur-buy-rate')?.value || '95.7'),
    usdSale: parseFloat($('usd-sale-rate')?.value || '81.7'),
    usdBuy: parseFloat($('usd-buy-rate')?.value || '82.7')
  };
}

function convertFromLek(amount, currency = $('currency-select')?.value || 'LEK') {
  const value = parseFloat(amount || 0);
  const rates = getRates();
  if (currency === 'EURO') return value / rates.eurSale;
  if (currency === 'USD') return value / rates.usdSale;
  return value;
}

function convertPaymentToLek(amount, currency) {
  const value = parseFloat(amount || 0);
  const rates = getRates();
  if (currency === 'EURO') return value * rates.eurBuy;
  if (currency === 'USD') return value * rates.usdBuy;
  return value;
}

function formatMoney(amount, currency = $('currency-select')?.value || 'LEK') {
  const value = parseFloat(amount || 0);
  if (currency === 'LEK') return value.toFixed(2) + ' LEK';
  if (currency === 'USD') return '$' + value.toFixed(2);
  return 'EUR ' + value.toFixed(2);
}

const fmt = n => formatMoney(convertFromLek(n));
const fmtLek = n => formatMoney(n, 'LEK');
const fmtTax = n => parseFloat(n || 0).toFixed(0) + '%';
const fmtDate = s => {
  if (!s) return '-';
  return formatDateTime(s);
};

function toast(msg, type = 'info') {
  const wrap = $('toast-wrap');
  const el = document.createElement('div');
  el.className = `toast t-${type}`;
  const icon = { success: 'OK', error: 'X', info: 'i' }[type] || 'i';
  const col  = { success: 'var(--green)', error: 'var(--red)', info: 'var(--blue)' }[type];
  const iconEl = document.createElement('span');
  iconEl.style.color = col;
  iconEl.style.fontWeight = '700';
  iconEl.textContent = icon;
  const textEl = document.createElement('span');
  textEl.textContent = msg;
  el.append(iconEl, textEl);
  wrap.appendChild(el);
  setTimeout(() => {
    el.classList.add('leaving');
    setTimeout(() => el.remove(), 300);
  }, 3200);
}

async function req(method, path, body, extraHeaders) {
  const opts = { method, headers: { 'Content-Type': 'application/json', 'Accept-Language': currentLang, ...(extraHeaders || {}) } };
  if (body !== undefined) opts.body = JSON.stringify(body);
  const res  = await fetch(API + path, opts);
  const json = await res.json().catch(() => ({}));
  if (res.status === 401 && currentUser) clearLocalSession();
  if (!res.ok || json.success === false) {
    const error = new Error(json.message || json.error || t('Request failed with status {status}', { status: res.status }));
    error.code = json.code;
    throw error;
  }
  return json;
}

/*
 * For actions a cashier may only do with a manager's approval (voids, price changes, refunds): if the server
 * asks for approval, show the PIN prompt and send the request again with the PIN. Managers never see it.
 */
async function reqApproved(method, path, body) {
  let pin = null;
  for (;;) {
    try {
      return await req(method, path, body, pin ? { 'X-Approval-Pin': pin } : undefined);
    } catch (e) {
      if (e.code !== 'approval.required' && e.code !== 'approval.invalidPin') throw e;
      pin = await askManagerPin(e.message);
      if (pin === null) throw Object.assign(new Error(''), { cancelled: true });
    }
  }
}

let pinResolver = null;

function askManagerPin(message) {
  $('pin-message').textContent = message || '';
  $('pin-input').value = '';
  openModal('modal-pin');
  setTimeout(() => $('pin-input').focus(), 50);
  return new Promise(resolve => { pinResolver = resolve; });
}

function answerPin(pin) {
  closeModal('modal-pin');
  const resolve = pinResolver;
  pinResolver = null;
  if (resolve) resolve(pin === null || pin === '' ? null : pin);
}

/* A cancelled PIN prompt is not an error worth a message. */
function reportError(e, fallback) {
  if (!e?.cancelled) toast(e?.message || fallback, 'error');
}

function openModal(id)  { $(id).classList.remove('hidden'); }
function closeModal(id) { $(id).classList.add('hidden'); }

function isSuperAdmin() {
  return currentUser?.role === 'SUPER_ADMIN';
}

function isOperationalManager() {
  return isSuperAdmin() || currentUser?.role === 'SUPER_CASHIER';
}

function roleLabel(role) {
  if (role === 'SUPER_ADMIN') return t('Super Admin');
  if (role === 'SUPER_CASHIER') return t('Super Cashier');
  return t('Cashier');
}

function clearLocalSession() {
  currentUser = null;
  cartItems = [];
  activeShift = null;
  try { localStorage.removeItem('cashier'); } catch {}
  $('auth-screen').classList.remove('hidden');
  $('app-screen').classList.add('hidden');
}

/*  Auth  */
function switchTab(tab) {
  $('tab-login').classList.toggle('on', tab === 'login');
  $('tab-register').classList.toggle('on', tab === 'register');
  $('form-login').classList.toggle('hidden', tab !== 'login');
  $('form-register').classList.toggle('hidden', tab !== 'register');
}

async function doLogin() {
  const username = $('l-user').value.trim();
  const password = $('l-pass').value.trim();
  if (!username || !password) { toast(t('Please fill in all fields'), 'error'); return; }
  try {
    const res = await req('POST', '/auth/login', { username, password });
    bootUser(res.data);
    toast(t('Welcome back, {name}!', { name: res.data.fullName }), 'success');
  } catch (e) { toast(e.message || t('Login failed'), 'error'); }
}

async function doRegister() {
  const fullName = $('r-name').value.trim();
  const username = $('r-user').value.trim();
  const password = $('r-pass').value.trim();
  if (!fullName || !username || !password) { toast(t('Fill in all fields'), 'error'); return; }
  try {
    const res = await req('POST', '/auth/register', { fullName, username, password });
    bootUser(res.data);
    toast(t('Account created! Welcome, {name}!', { name: res.data.fullName }), 'success');
  } catch (e) { toast(e.message || t('Registration failed'), 'error'); }
}

async function checkInitialSetup() {
  try {
    const res = await req('GET', '/auth/setup');
    const available = res.data?.registrationAvailable === true;
    $('tab-register').classList.toggle('hidden', !available);
    if (!available && !$('form-register').classList.contains('hidden')) switchTab('login');
  } catch {
    $('tab-register').classList.add('hidden');
  }
}

function bootUser(user) {
  currentUser = user;
  $('user-name').textContent   = user.fullName;
  $('user-avatar').textContent = user.fullName.charAt(0).toUpperCase();
  $('user-role').textContent = roleLabel(user.role);
  $('auth-screen').classList.add('hidden');
  $('app-screen').classList.remove('hidden');
  $('home-account-name').textContent = user.fullName;
  applyRolePermissions();
  gotoView('home');
  setNextInvoiceNumber();
  loadPosProducts();
  refreshCart();
  loadHomeData();
  loadExchangeRates();
}

async function doLogout() {
  try {
    if (currentUser) await req('POST', '/auth/logout');
  } catch {}
  clearLocalSession();
  $('l-user').value = '';
  $('l-pass').value = '';
}

function applyRolePermissions() {
  const manager = isOperationalManager();
  const superAdmin = isSuperAdmin();
  document.querySelectorAll('.manager-only').forEach(element => element.classList.toggle('hidden', !manager));
  document.querySelectorAll('.super-admin-only').forEach(element => element.classList.toggle('hidden', !superAdmin));
  document.querySelectorAll('.rate-input').forEach(input => { input.disabled = !manager; });
  const salesSubtitle = document.querySelector('#view-sales .page-sub');
  if (salesSubtitle) salesSubtitle.textContent = manager ? t('Transaction history for all cashiers') : t('Your transaction history');
  if ($('home-description')) {
    $('home-description').textContent = manager
      ? t('Start a sale, manage products, and review daily activity from one clean workspace.')
      : t('Start sales, review your transactions, and control your daily shift.');
  }
  if ($('operations-title')) {
    $('operations-title').textContent = superAdmin ? t('Shifts & Backups') : manager ? t('All Shifts') : t('My Shift');
  }
}

/*  Navigation  */
function gotoView(view) {
  if (['products', 'purchases', 'reports', 'suppliers', 'inventory', 'promotions'].includes(view) && !isOperationalManager()) {
    toast(t('Super Cashier or Super Admin access is required'), 'error');
    view = 'home';
  }
  if ((view === 'users' || view === 'audit') && !isSuperAdmin()) {
    toast(t('Super Admin access is required'), 'error');
    view = 'home';
  }
  document.querySelectorAll('.view').forEach(v => v.classList.remove('on'));
  document.querySelectorAll('.nav-btn').forEach(b => b.classList.remove('on'));
  $('view-' + view).classList.add('on');
  document.querySelector(`[data-v="${view}"]`).classList.add('on');
  if (view === 'home') loadHomeData();
  if (view === 'products') { loadCategories(); loadProdsTable(); }
  if (view === 'purchases') initPurchasePage();
  if (view === 'suppliers') loadSuppliers();
  if (view === 'customers') loadCustomers();
  if (view === 'promotions') loadPromotions();
  if (view === 'inventory') showInventoryTab(currentInventoryTab);
  if (view === 'sales')    loadSales();
  if (view === 'reports')  loadReports();
  if (view === 'users') loadUsers();
  if (view === 'operations') loadOperations();
  if (view === 'audit') initAudit();
}

async function loadHomeData() {
  updateHomeClock();
  try {
    const [dashboardRes, cartRes] = await Promise.all([
      req('GET', '/reports/dashboard'),
      req('GET', '/sales/cart')
    ]);
    const d = dashboardRes.data;
    const cart = Array.isArray(cartRes.data) ? cartRes.data : [];

    $('home-products-count').textContent = d.activeProducts;
    $('home-sales-count').textContent = d.salesToday;
    $('home-revenue').textContent = fmtLek(d.revenueToday);
    $('home-cart-count').textContent = cart.length;
    $('home-status-list').innerHTML = `
      <div class="home-status-item"><span>${t('Low Stock')}</span><strong>${t('{count} products need attention', { count: d.lowStockProducts })}</strong></div>
      <div class="home-status-item"><span>${t('Last Sale')}</span><strong>${d.lastSaleAt ? fmtDate(d.lastSaleAt) : t('No sales yet')}</strong></div>
      <div class="home-status-item"><span>${t('Cashier')}</span><strong>${esc(currentUser?.fullName || t('Cashier'))}</strong></div>
    `;
  } catch (e) {
    $('home-status-list').innerHTML = `<div class="home-status-item"><span>${t('Status')}</span><strong>${esc(e.message || t('Could not load dashboard'))}</strong></div>`;
  }
}

function updateHomeClock() {
  const now = new Date();
  if ($('home-date')) $('home-date').textContent = formatWeekdayDate(now);
  if ($('home-time')) $('home-time').textContent = formatTime(now);
}

/*  POS Products  */
async function loadPosProducts(query) {
  const url = query ? `/products/search?query=${encodeURIComponent(query)}` : '/products/in-stock';
  try {
    const res = await req('GET', url);
    posProducts = Array.isArray(res.data) ? res.data : [];
    renderGrid(posProducts);
  } catch (e) {
    toast(t('Could not load products: ') + e.message, 'error');
    $('prod-grid').innerHTML = `<div class="prod-empty"><span class="em-icon"></span><strong>${t('Failed to load')}</strong><br><span class="t-sm t-muted">${esc(e.message)}</span></div>`;
  }
}

let _searchTmr;
function onSearch(val) {
  clearTimeout(_searchTmr);
  _searchTmr = setTimeout(() => {
    if (/^\d{8,}$/.test(val.trim())) {
      addByBarcode(val.trim());
      $('pos-search').value = '';
    } else {
      loadPosProducts(val.trim() || undefined);
    }
  }, 300);
}

function renderGrid(products) {
  const g = $('prod-grid');
  if (!products.length) {
    g.innerHTML = `<div class="prod-empty"><span class="em-icon"></span><strong>${t('No products found')}</strong><br><span class="t-sm t-muted">${t('Try a different search or barcode')}</span></div>`;
    return;
  }
  g.innerHTML = products.map(p => {
    const sc = p.stock === 0 ? 'sk-oos' : p.lowStock ? 'sk-low' : 'sk-ok';
    const sl = p.stock === 0 ? t('Out of Stock') : t('{count} left', { count: fmtQty(p.stock, p.unit) });
    return `<div class="prod-card${p.stock === 0 ? ' oos' : ''}" onclick="addById(${p.id})">
      <div class="pc-cat">${esc(p.category?.name || t('None'))}</div>
      <div class="pc-name">${esc(p.name)}</div>
      <div class="pc-bc">${esc(p.barcode)}</div>
      <div class="pc-foot">
        <span class="pc-price">${fmt(p.price)}</span>
        <span class="pc-stock ${sc}">${sl}</span>
      </div>
    </div>`;
  }).join('');
}

/*  Cart  */
async function refreshCart() {
  try {
    const res = await req('GET', '/sales/cart');
    cartItems = Array.isArray(res.data) ? res.data : [];
    renderCart();
    fetchSubtotal();
  } catch {
    cartItems = [];
    renderCart();
  }
}

function renderCart() {
  const list = $('cart-list');
  const total = cartItems.length;
  $('cart-count').textContent = total;
  renderInvoiceRows();

  const hasItems = cartItems.length > 0;
  $('btn-checkout').disabled = !hasItems;
  $('btn-clear').disabled    = !hasItems;

  if (!hasItems) {
    list.innerHTML = `<div class="cart-empty-msg">
      <div class="cart-empty-icon"></div>
      <strong>${t('Cart is empty')}</strong>
      <span class="t-sm">${t('Click a product or scan a barcode')}</span>
    </div>`;
    $('cart-subtotal').textContent = '0.00 LEK';
    return;
  }

  list.innerHTML = cartItems.map(item => `
    <div class="cart-item">
      <div class="ci-info">
        <div class="ci-name">${esc(item.productName)}</div>
        <div class="ci-unit">${fmt(item.price)} / ${esc(unitLabel(item.unit))}</div>
      </div>
      <div class="ci-qty">
        <button class="qty-btn" onclick="stepQty(${item.productId},-1)"></button>
        <span class="qty-val">${fmtQty(item.quantity, item.unit)}</span>
        <button class="qty-btn" onclick="stepQty(${item.productId},1)">+</button>
      </div>
      <div class="ci-total">${fmt(item.lineTotal)}</div>
      <button class="ci-rm" onclick="removeItem(${item.productId})" title="${t('Remove')}"></button>
    </div>
  `).join('');
}

/* The till's summary: total after promotions and discounts, and the customer of the sale. */
let cartSummary = null;

async function fetchSubtotal() {
  try {
    const res = await req('GET', '/sales/cart/summary');
    renderCartSummary(res.data);
  } catch {}
}

function renderCartSummary(summary) {
  cartSummary = summary;
  if (!summary) return;
  $('cart-subtotal').textContent = fmt(summary.totalAmount);
  const lines = [];
  if (parseFloat(summary.discountAmount) > 0) {
    lines.push(`<div class="balance-line"><span>${t('Subtotal')}</span><strong>${fmt(summary.subtotal)}</strong></div>`);
  }
  (summary.promotions || []).forEach(p => lines.push(
    `<div class="balance-line promo"><span>${esc(p.promotionName)}</span><strong>-${fmt(p.discount)}</strong></div>`));
  if (parseFloat(summary.manualDiscount) > 0) {
    lines.push(`<div class="balance-line promo"><span>${t('Manual discount')} ${parseFloat(summary.manualDiscountPercent)}%</span><strong>-${fmt(summary.manualDiscount)}</strong></div>`);
  }
  if (parseFloat(summary.otherDiscount) > 0) {
    lines.push(`<div class="balance-line promo"><span>${t('Discount')}</span><strong>-${fmt(summary.otherDiscount)}</strong></div>`);
  }
  $('cart-breakdown').innerHTML = lines.join('');
  const c = summary.customer;
  $('cart-customer').classList.toggle('hidden', !c);
  $('cart-customer').innerHTML = c ? `<span><strong>${esc(c.fullName)}</strong> · ${t('{points} points', { points: c.points })}${
    parseFloat(c.balance) !== 0 ? ' · ' + t('Debt') + ' ' + fmtLek(c.balance) : ''}</span>
    <button class="btn btn-ghost btn-sm" onclick="setCartCustomer(null)">x</button>` : '';
}

async function askManualDiscount() {
  if (!cartItems.length) { toast(t('Cart is empty'), 'error'); return; }
  const value = prompt(t('Discount on the whole cart (%). 0 removes it.'), cartSummary?.manualDiscountPercent || '');
  if (value === null) return;
  const percent = parseFloat(String(value).replace(',', '.'));
  if (Number.isNaN(percent)) { toast(t('Enter a valid number'), 'error'); return; }
  try {
    const res = await reqApproved('PUT', '/sales/cart/discount', { percent });
    renderCartSummary(res.data);
  } catch (e) { reportError(e, t('Save failed')); }
}

/* Customer of the sale: scan the loyalty card, or search by name or phone. */
let pickerTimer;

function openCustomerPicker() {
  $('picker-query').value = '';
  $('picker-results').innerHTML = '';
  openModal('modal-customer-picker');
  setTimeout(() => $('picker-query').focus(), 50);
}

function searchPickerCustomers() {
  clearTimeout(pickerTimer);
  pickerTimer = setTimeout(async () => {
    const query = $('picker-query').value.trim();
    if (query.length < 2) { $('picker-results').innerHTML = ''; return; }
    try {
      const res = await req('GET', `/customers?query=${encodeURIComponent(query)}`);
      $('picker-results').innerHTML = (res.data || []).filter(c => c.active).slice(0, 10).map(c => `
        <tr onclick="setCartCustomer(${c.id})"><td><strong>${esc(c.fullName)}</strong></td><td class="td-m">${esc(c.cardNumber)}</td>
          <td>${esc(c.phone || '')}</td><td>${t('{points} points', { points: c.points })}</td></tr>`).join('')
        || `<tr><td class="no-data">${t('No customer found.')}</td></tr>`;
    } catch {}
  }, 250);
}

async function pickByCard() {
  const card = $('picker-query').value.trim();
  if (!card) return;
  try {
    const res = await req('PUT', '/sales/cart/customer', { cardNumber: card });
    renderCartSummary(res.data);
    closeModal('modal-customer-picker');
  } catch (e) { searchPickerCustomers(); toast(e.message, 'error'); }
}

async function setCartCustomer(customerId) {
  try {
    const res = await req('PUT', '/sales/cart/customer', customerId ? { customerId: String(customerId) } : {});
    renderCartSummary(res.data);
    closeModal('modal-customer-picker');
  } catch (e) { toast(e.message, 'error'); }
}

/* Exchange rates live on the server. Managers change them here; cashiers only see them. */
let exchangeRates = {};
let rateSaveTimer;

async function loadExchangeRates() {
  try {
    const res = await req('GET', '/exchange-rates');
    exchangeRates = {};
    (res.data || []).forEach(rate => { exchangeRates[rate.currency] = rate; });
    if (exchangeRates.EUR) { $('eur-sale-rate').value = exchangeRates.EUR.sellRate; $('eur-buy-rate').value = exchangeRates.EUR.buyRate; }
    if (exchangeRates.USD) { $('usd-sale-rate').value = exchangeRates.USD.sellRate; $('usd-buy-rate').value = exchangeRates.USD.buyRate; }
    refreshCurrencyDisplay();
  } catch (e) { toast(e.message || t('Could not load exchange rates'), 'error'); }
}

function onRateChanged() {
  refreshCurrencyDisplay();
  if (!isOperationalManager()) return;
  clearTimeout(rateSaveTimer);
  rateSaveTimer = setTimeout(async () => {
    const rates = getRates();
    try {
      await req('PUT', '/exchange-rates', [
        { currency: 'EUR', buyRate: rates.eurBuy, sellRate: rates.eurSale },
        { currency: 'USD', buyRate: rates.usdBuy, sellRate: rates.usdSale }
      ]);
      await loadExchangeRates();
      toast(t('Exchange rates saved'), 'success');
    } catch (e) {
      toast(e.message || t('Could not save exchange rates'), 'error');
      loadExchangeRates();
    }
  }, 600);
}

function refreshCurrencyDisplay() {
  renderCart();
  renderGrid(posProducts);
  renderProdsTable(allProdTable);
  if (salesPage) { renderSalesTable(); renderStats(); }
  if ($('view-reports')?.classList.contains('on')) applyReportFilters();
}

async function addById(productId) {
  try {
    await req('POST', '/sales/cart', { productId, quantity: 1 });
    await refreshCart();
    toast(t('Added to cart'), 'success');
  } catch (e) { toast(e.message || t('Could not add item'), 'error'); }
}

async function addByBarcode(barcode) {
  try {
    await req('POST', '/sales/cart', { barcode, quantity: 1 });
    await refreshCart();
    toast(t('Item scanned & added'), 'success');
  } catch (e) {
    toast(e.message || t('Product is not registered or does not exist'), 'error');
  }
}

function renderInvoiceRows() {
  const tbody = $('invoice-rows');
  if (!tbody) return;
  if (!cartItems.length) {
    tbody.innerHTML = `<tr class="invoice-empty"><td colspan="11">${t('Scan a barcode or use the keypad to add products to the invoice.')}</td></tr>`;
    return;
  }
  tbody.innerHTML = cartItems.map((item, index) => {
    const priceCell = true
      ? `<input class="input pos-edit-cell" type="number" min="0.01" step="0.01" value="${Number(item.price || 0).toFixed(2)}" onchange="updateCartInline(${item.productId}, 'price', this.value)" />`
      : `<span class="td-p">${fmt(item.price)}</span>`;
    return `
    <tr>
      <td class="td-m">${String(index + 1).padStart(4, '0')}</td>
      <td class="invoice-code-cell">${esc(item.barcode || item.productId)}</td>
      <td><strong>${esc(item.productName)}</strong></td>
      <td class="td-p">${fmt(item.unitPriceWithoutTax)}</td>
      <td class="td-m">${fmtTax(item.taxRate)}</td>
      <td class="td-p">${fmt(item.taxAmount)}</td>
      <td>${priceCell}</td>
      <td><input class="input pos-edit-cell pos-qty-cell" type="number" min="0" step="${item.unit === 'kg' ? '0.001' : '1'}" value="${fmtQty(item.quantity, item.unit)}" onchange="updateCartInline(${item.productId}, 'quantity', this.value)" /></td>
      <td class="td-p">${fmt(item.lineTotal)}</td>
      <td class="td-m">${esc(unitLabel(item.unit))}</td>
      <td class="td-a">
        <button class="btn btn-secondary btn-sm btn-icon" title="${t('Decrease')}" onclick="stepQty(${item.productId},-1)">-</button>
        <button class="btn btn-secondary btn-sm btn-icon" title="${t('Increase')}" onclick="stepQty(${item.productId},1)">+</button>
        <button class="btn btn-danger btn-sm btn-icon" title="${t('Remove')}" onclick="removeItem(${item.productId})">x</button>
      </td>
    </tr>
  `;
  }).join('');
}

function updateKeypadDisplay() {
  const display = $('keypad-display');
  if (display) display.textContent = keypadBuffer || '0';
}

function keyTap(value) {
  keypadBuffer = (keypadBuffer + value).slice(0, 32);
  updateKeypadDisplay();
}

function keyBack() {
  keypadBuffer = keypadBuffer.slice(0, -1);
  updateKeypadDisplay();
}

function keyClear() {
  keypadBuffer = '';
  updateKeypadDisplay();
}

function keyEnter() {
  const code = keypadBuffer.trim() || $('pos-search')?.value.trim();
  if (!code) {
    toast(t('Enter or scan a barcode first'), 'error');
    return;
  }
  addByBarcode(code);
  keypadBuffer = '';
  if ($('pos-search')) $('pos-search').value = '';
  updateKeypadDisplay();
}

/* Quantities: pieces are whole numbers, kilograms have up to 3 decimals. "0,350" is accepted too. */
function parseQuantity(value) {
  const n = parseFloat(String(value ?? '').trim().replace(',', '.'));
  return Number.isFinite(n) ? n : NaN;
}

function fmtQty(quantity, unit) {
  const n = parseFloat(quantity || 0);
  return unit === 'kg' ? n.toFixed(3) : String(Math.round(n * 1000) / 1000);
}

/* Lines with pieces count as their quantity, weighed lines count as one item. */
function itemCount(items) {
  return (items || []).reduce((sum, item) => sum + (item.unit === 'kg' ? 1 : parseFloat(item.quantity || 0)), 0);
}

function promptQuantity() {
  if (!cartItems.length) {
    toast(t('Add an item before changing quantity'), 'error');
    return;
  }
  const last = cartItems[cartItems.length - 1];
  const value = prompt(t('Quantity for ') + last.productName, fmtQty(last.quantity, last.unit));
  if (value === null) return;
  const qty = parseQuantity(value);
  if (Number.isNaN(qty) || qty < 0) { toast(t('Enter a valid quantity'), 'error'); return; }
  setQuantity(last.productId, qty);
}

/* The +/- buttons: one piece, or 100 g for weighed products. */
function stepQty(productId, direction) {
  const item = cartItems.find(i => i.productId === productId);
  if (!item) return;
  const step = item.unit === 'kg' ? 0.1 : 1;
  setQuantity(productId, parseFloat(item.quantity) + direction * step);
}

async function setQuantity(productId, quantity) {
  const rounded = Math.max(0, Math.round(quantity * 1000) / 1000);
  try {
    const res = await reqApproved('PUT', `/sales/cart/${productId}`, { quantity: rounded });
    cartItems = Array.isArray(res.data) ? res.data : [];
    renderCart();
    fetchSubtotal();
  } catch (e) {
    reportError(e, t('Could not update qty'));
    refreshCart();
  }
}

async function updateCartInline(productId, field, value) {
  const item = cartItems.find(cartItem => cartItem.productId === productId);
  if (!item) return;
  const body = { quantity: item.quantity };
  if (field === 'quantity') {
    const quantity = parseQuantity(value);
    if (Number.isNaN(quantity) || quantity <= 0) {
      toast(t('Enter a valid quantity'), 'error');
      renderInvoiceRows();
      return;
    }
    body.quantity = quantity;
  }
  if (field === 'price') {
    const price = parseFloat(value || '0');
    if (Number.isNaN(price) || price <= 0) {
      toast(t('Price must be greater than zero'), 'error');
      renderInvoiceRows();
      return;
    }
    body.price = price;
  }
  try {
    const res = await reqApproved('PUT', `/sales/cart/${productId}`, body);
    cartItems = Array.isArray(res.data) ? res.data : [];
    renderCart();
    fetchSubtotal();
    toast(t('Invoice line updated'), 'success');
  } catch (e) {
    reportError(e, t('Could not update invoice line'));
    refreshCart();
  }
}

async function removeItem(productId) {
  await setQuantity(productId, 0);
}

/* Parked carts: put the current customer aside and serve the next one. */
async function parkCart() {
  if (!cartItems.length) { toast(t('Cart is empty'), 'error'); return; }
  const label = prompt(t('Note to recognise this cart (optional)'), '');
  if (label === null) return;
  try {
    await req('POST', '/sales/cart/park', { label });
    cartItems = [];
    renderCart();
    toast(t('Cart parked'), 'success');
  } catch (e) { toast(e.message || t('Could not park cart'), 'error'); }
}

async function openParkedCarts() {
  const tbody = $('parked-tbody');
  tbody.innerHTML = `<tr><td colspan="6" class="no-data">${t('Loading...')}</td></tr>`;
  openModal('modal-parked');
  try {
    const res = await req('GET', '/sales/carts/parked');
    const carts = Array.isArray(res.data) ? res.data : [];
    tbody.innerHTML = carts.length ? carts.map(cart => `
      <tr>
        <td><strong>${esc(cart.label || '-')}</strong></td>
        <td>${esc(cart.cashierName)}</td>
        <td class="td-m">${cart.lines}</td>
        <td class="td-p">${fmt(cart.total)}</td>
        <td>${fmtDate(cart.parkedAt)}</td>
        <td><button class="btn btn-primary btn-sm" onclick="resumeCart(${cart.id})">${t('Resume')}</button></td>
      </tr>`).join('') : `<tr><td colspan="6" class="no-data">${t('No parked carts.')}</td></tr>`;
  } catch (e) {
    tbody.innerHTML = `<tr><td colspan="6" class="no-data" style="color:var(--red)">${esc(e.message)}</td></tr>`;
  }
}

async function resumeCart(cartId) {
  try {
    const res = await req('POST', `/sales/carts/${cartId}/resume`);
    cartItems = Array.isArray(res.data) ? res.data : [];
    renderCart();
    fetchSubtotal();
    closeModal('modal-parked');
    toast(t('Cart resumed'), 'success');
  } catch (e) { toast(e.message || t('Could not resume cart'), 'error'); }
}

async function clearCart() {
  try {
    await reqApproved('DELETE', '/sales/cart');
    cartItems = [];
    renderCart();
    fetchSubtotal();
    toast(t('Cart cleared'), 'info');
  } catch (e) { reportError(e, t('Could not clear cart')); }
}

/*  Checkout  */
/* Checkout opens the payment window. The sale is only saved when the payment covers the total. */
let paymentTotal = 0;

async function doCheckout() {
  if (!cartItems.length) { toast(t('Cart is empty'), 'error'); return; }
  try {
    const res = await req('GET', '/sales/cart/summary');
    renderCartSummary(res.data);
    paymentTotal = parseFloat(res.data?.totalAmount || 0);
    const customer = res.data?.customer;
    $('pay-customer-fields').classList.toggle('hidden', !customer);
    if (customer) {
      $('pay-points-available').textContent = `(${t('max {amount}', { amount: fmtLek(res.data.pointsValue || 0) })})`;
      $('pay-credit-available').textContent = customer.creditAvailable == null
        ? `(${t('not allowed')})` : `(${t('max {amount}', { amount: fmtLek(customer.creditAvailable) })})`;
      $('pay-credit').disabled = customer.creditAvailable == null;
    }
    const discount = parseFloat(res.data?.discountAmount || 0);
    $('pay-total').textContent = fmtLek(paymentTotal);
    const foreign = ['EUR', 'USD'].filter(c => exchangeRates[c])
      .map(c => `${(paymentTotal / exchangeRates[c].buyRate).toFixed(2)} ${c}`).join(' / ');
    $('pay-total-foreign').textContent = (discount > 0 ? t('Discount') + ': ' + fmtLek(discount) + ' | ' : '') + foreign;
    ['pay-cash-lek', 'pay-cash-foreign', 'pay-card', 'pay-points', 'pay-credit'].forEach(id => { $(id).value = ''; });
    updatePaymentSummary();
    openModal('modal-payment');
    setTimeout(() => $('pay-cash-lek').focus(), 50);
  } catch (e) { toast(e.message || t('Checkout failed'), 'error'); }
}

function readPayments() {
  const amount = id => parseFloat(String($(id).value || '').replace(',', '.')) || 0;
  const currency = $('pay-foreign-currency').value;
  const payments = [];
  if (amount('pay-cash-lek') > 0) payments.push({ method: 'CASH', currency: 'LEK', amount: amount('pay-cash-lek') });
  if (amount('pay-cash-foreign') > 0) payments.push({ method: 'CASH', currency, amount: amount('pay-cash-foreign') });
  if (amount('pay-card') > 0) payments.push({ method: 'CARD', currency: 'LEK', amount: amount('pay-card') });
  if (amount('pay-points') > 0) payments.push({ method: 'POINTS', currency: 'LEK', amount: amount('pay-points') });
  if (amount('pay-credit') > 0) payments.push({ method: 'CREDIT', currency: 'LEK', amount: amount('pay-credit') });
  return payments;
}

function paymentLabel(method) {
  return t({ CARD: 'Card', CREDIT: 'On account', POINTS: 'Points' }[method] || 'Cash');
}

/* Same conversion as the server: foreign cash at the buy rate, rounded to cents. */
function paymentInLek(payment) {
  const rate = payment.currency === 'LEK' ? 1 : (exchangeRates[payment.currency]?.buyRate || 0);
  return Math.round(payment.amount * rate * 100) / 100;
}

function updatePaymentSummary() {
  const payments = readPayments();
  const paid = payments.reduce((sum, p) => sum + paymentInLek(p), 0);
  const card = payments.filter(p => p.method !== 'CASH').reduce((sum, p) => sum + p.amount, 0);
  const rest = Math.round((paymentTotal - paid) * 100) / 100;
  $('pay-paid').textContent = fmtLek(paid);
  const restEl = $('pay-rest');
  restEl.classList.toggle('is-missing', rest > 0);
  restEl.classList.toggle('is-change', rest <= 0);
  $('pay-rest-label').textContent = rest > 0 ? t('Still to pay') : t('Change');
  restEl.textContent = fmtLek(Math.abs(rest));
  $('pay-confirm').disabled = rest > 0 || card > paymentTotal + 0.001 || !payments.length;
}

function payExactCash() {
  ['pay-points', 'pay-credit'].forEach(id => { $(id).value = ''; });
  $('pay-cash-lek').value = paymentTotal.toFixed(2);
  $('pay-cash-foreign').value = '';
  $('pay-card').value = '';
  updatePaymentSummary();
}

function payAllByCard() {
  ['pay-points', 'pay-credit'].forEach(id => { $(id).value = ''; });
  $('pay-card').value = paymentTotal.toFixed(2);
  $('pay-cash-lek').value = '';
  $('pay-cash-foreign').value = '';
  updatePaymentSummary();
}

async function confirmPayment() {
  const btn = $('pay-confirm');
  if (btn.disabled) return;
  btn.disabled = true;
  try {
    const res = await req('POST', '/sales/checkout', { payments: readPayments() });
    const data = res.data || {};
    lastCheckoutData = data;
    lastReceipt = data.printableReceipt || buildFallbackReceipt(data);
    closeModal('modal-payment');
    $('receipt-txt').textContent = lastReceipt;
    openModal('modal-receipt');
    loadReceiptPrinters();
    cartItems = [];
    renderCart();
    fetchSubtotal();
    setNextInvoiceNumber();
    toast(parseFloat(data.changeAmount) > 0
      ? t('Sale completed. Change: {amount}', { amount: fmtLek(data.changeAmount) })
      : t('Checkout complete!'), 'success');
  } catch (e) {
    toast(e.message || t('Checkout failed'), 'error');
    updatePaymentSummary();
  }
}

async function loadCategories(selectedName) {
  try {
    const res = await req('GET', '/categories');
    allCategories = Array.isArray(res.data) ? res.data : [];
  } catch {
    allCategories = [
      { name: 'Food' }, { name: 'Drinks' }, { name: 'Household' }, { name: 'General' }
    ];
  }
  renderCategorySelect(selectedName);
}

function renderCategorySelect(selectedName) {
  const select = $('pm-cat');
  if (!select) return;
  const names = allCategories.map(category => category.name).filter(Boolean);
  if (!names.includes('General')) names.push('General');
  select.innerHTML = names.map(name => `<option value="${esc(name)}">${esc(name)}</option>`).join('');
  select.value = selectedName && names.includes(selectedName) ? selectedName : names[0];
}

async function setNextInvoiceNumber() {
  try {
    const res = await req('GET', '/sales/next-invoice-number');
    if ($('invoice-no')) $('invoice-no').value = res.data || '';
  } catch {
    if ($('invoice-no')) $('invoice-no').value = '';
  }
}

/*  Purchase Invoices  */
function initPurchasePage() {
  loadCategories();
  loadSupplierOptions();
  if (!$('purchase-date')?.value) {
    $('purchase-date').value = new Date().toISOString().slice(0, 10);
  }
  renderPurchaseRows();
}

function handlePurchaseScanKey(event) {
  if (event.key !== 'Enter') return;
  event.preventDefault();
  scanPurchaseField();
}

function scanPurchaseField() {
  const query = $('purchase-scan')?.value.trim();
  if (!query) {
    toast(t('Enter or scan a barcode first'), 'error');
    return;
  }
  addPurchaseByBarcode(query);
  $('purchase-scan').value = '';
}

async function addPurchaseByBarcode(barcode) {
  try {
    const res = await req('GET', `/products/barcode/${encodeURIComponent(barcode)}`);
    addPurchaseProduct(res.data);
    toast(t('Product added to purchase invoice'), 'success');
  } catch (e) {
    toast(t('Product not registered. Complete product registration first.'), 'error');
    openProdModal(null, barcode);
  }
}

function addPurchaseProduct(product) {
  const existing = purchaseItems.find(item => item.productId === product.id);
  if (existing) {
    existing.quantity += 1;
  } else {
    purchaseItems.push({
      productId: product.id,
      barcode: product.barcode,
      name: product.name,
      category: product.category?.name || 'General',
      unit: product.unit || 'pcs',
      quantity: 1,
      purchasePrice: parseFloat(product.purchasePrice || 0),
      taxRate: parseFloat(product.taxRate || 20),
      sellingPrice: parseFloat(product.price || 0),
      expiryDate: ''
    });
  }
  renderPurchaseRows();
}

function renderPurchaseRows() {
  const tbody = $('purchase-rows');
  if (!tbody) return;
  if (!purchaseItems.length) {
    tbody.innerHTML = `<tr class="invoice-empty"><td colspan="12">${t('Scan products to build the supplier invoice.')}</td></tr>`;
    updatePurchaseTotal();
    return;
  }
  tbody.innerHTML = purchaseItems.map((item, index) => `
    <tr>
      <td class="td-m">${String(index + 1).padStart(4, '0')}</td>
      <td class="invoice-code-cell">${esc(item.barcode)}</td>
      <td><strong>${esc(item.name)}</strong></td>
      <td><span class="badge b-blue">${esc(item.category)}</span></td>
      <td>
        <select class="input purchase-cell" onchange="updatePurchaseItem(${index}, 'unit', this.value)">
          <option value="pcs" ${item.unit === 'pcs' ? 'selected' : ''}>pcs</option>
          <option value="kg" ${item.unit === 'kg' ? 'selected' : ''}>kg</option>
        </select>
      </td>
      <td><input class="input purchase-cell" type="number" min="0" step="${item.unit === 'kg' ? '0.001' : '1'}" value="${item.quantity}" onchange="updatePurchaseItem(${index}, 'quantity', this.value)" /></td>
      <td><input class="input purchase-cell" type="number" min="0" step="0.01" value="${item.purchasePrice}" onchange="updatePurchaseItem(${index}, 'purchasePrice', this.value)" /></td>
      <td>
        <select class="input purchase-cell" onchange="updatePurchaseItem(${index}, 'taxRate', this.value)">
          <option value="20" ${Number(item.taxRate) === 20 ? 'selected' : ''}>20%</option>
          <option value="0" ${Number(item.taxRate) === 0 ? 'selected' : ''}>0%</option>
        </select>
      </td>
      <td><input class="input purchase-cell" type="number" min="0" step="0.01" value="${item.sellingPrice}" onchange="updatePurchaseItem(${index}, 'sellingPrice', this.value)" /></td>
      <td><input class="input purchase-cell" type="date" value="${esc(item.expiryDate || '')}" onchange="updatePurchaseItem(${index}, 'expiryDate', this.value)" /></td>
      <td class="td-p">${fmtLek(getPurchaseLineTotal(item))}</td>
      <td class="td-a">
        <button class="btn btn-secondary btn-sm btn-icon" title="${t('Edit product')}" onclick="openProdModalById(${item.productId})">E</button>
        <button class="btn btn-danger btn-sm btn-icon" title="${t('Remove')}" onclick="removePurchaseItem(${index})">x</button>
      </td>
    </tr>
  `).join('');
  updatePurchaseTotal();
}

function updatePurchaseItem(index, field, value) {
  const item = purchaseItems[index];
  if (!item) return;
  if (['quantity', 'purchasePrice', 'taxRate', 'sellingPrice'].includes(field)) {
    item[field] = field === 'quantity' ? parseQuantity(value) : parseFloat(value || '0');
  } else {
    item[field] = value;
  }
  renderPurchaseRows();
}

function removePurchaseItem(index) {
  purchaseItems.splice(index, 1);
  renderPurchaseRows();
}

function getPurchaseLineTotal(item) {
  return parseFloat(item.purchasePrice || 0) * (parseQuantity(item.quantity) || 0);
}

function updatePurchaseTotal() {
  const total = purchaseItems.reduce((sum, item) => sum + getPurchaseLineTotal(item), 0);
  const itemsInInvoice = itemCount(purchaseItems);
  if ($('purchase-total')) $('purchase-total').textContent = fmtLek(total);
  if ($('purchase-lines-count')) $('purchase-lines-count').textContent = purchaseItems.length;
  if ($('purchase-items-count')) $('purchase-items-count').textContent = itemsInInvoice;
}

function clearPurchaseInvoice() {
  purchaseItems = [];
  if ($('purchase-invoice-no')) $('purchase-invoice-no').value = '';
  if ($('purchase-company')) $('purchase-company').value = '';
  if ($('purchase-date')) $('purchase-date').value = new Date().toISOString().slice(0, 10);
  if ($('purchase-scan')) $('purchase-scan').value = '';
  renderPurchaseRows();
}

async function savePurchaseInvoice() {
  const body = {
    invoiceNumber: $('purchase-invoice-no')?.value.trim(),
    company: $('purchase-company')?.value.trim(),
    invoiceDate: $('purchase-date')?.value,
    items: purchaseItems.map(item => ({
      productId: item.productId,
      quantity: parseQuantity(item.quantity),
      purchasePrice: parseFloat(item.purchasePrice || 0),
      sellingPrice: parseFloat(item.sellingPrice || 0),
      taxRate: parseFloat(item.taxRate || 0),
      unit: item.unit || 'pcs',
      expiryDate: item.expiryDate || null
    }))
  };
  if (!body.invoiceNumber || !body.company || !body.invoiceDate || !body.items.length) {
    toast(t('Fill invoice number, company, date, and at least one product'), 'error');
    return;
  }
  try {
    await req('POST', '/purchases', body);
    toast(t('Purchase invoice saved and stock updated'), 'success');
    clearPurchaseInvoice();
    loadPosProducts();
    loadProdsTable();
  } catch (e) {
    toast(e.message || t('Could not save purchase invoice'), 'error');
  }
}

function buildFallbackReceipt(data) {
  const lines = [
    t('SALES RECEIPT'),
    '='.repeat(32),
    `${t('Invoice no.')}: ${data.invoiceNumber || data.saleId || ''}`,
    `${t('Date')}: ${fmtDate(data.date)}`,
    '-'.repeat(32),
  ];
  (data.items || []).forEach(i => {
    lines.push(`${(i.productName || i.product?.name || '').padEnd(20)} x${i.quantity} ${unitLabel(i.unit || i.product?.unit)}`);
    lines.push(`  @ ${fmt(i.unitPrice || i.price)} = ${fmt(i.lineTotal)}`);
  });
  lines.push('-'.repeat(32));
  lines.push(`${t('Subtotal')}: ${fmt(data.subtotal ?? data.totalAmount)}`);
  lines.push(`${t('TOTAL')}: ${fmt(data.totalAmount)}`);
  (data.payments || []).forEach(p => {
    lines.push(`${paymentLabel(p.method)}: ${parseFloat(p.amount).toFixed(2)} ${p.currency}`);
  });
  if (parseFloat(data.changeAmount) > 0) lines.push(`${t('Change')}: ${fmtLek(data.changeAmount)}`);
  lines.push('='.repeat(32));
  lines.push(t('Thank you for shopping!'));
  return lines.join('\n');
}

async function printReceipt() {
  await printText(lastReceipt);
}

/* Sends any text (receipt, X or Z report) to the chosen receipt printer. */
async function printText(text) {
  const printerName = $('receipt-printer')?.value || '';
  try {
    await req('POST', '/printer/receipt', { receiptText: text, printerName });
    toast(printerName ? t('Receipt sent to {printer}', { printer: printerName }) : t('Receipt sent to default printer'), 'success');
  } catch (e) {
    toast(e.message || t('Could not print receipt'), 'error');
  }
}

async function loadReceiptPrinters() {
  const select = $('receipt-printer');
  if (!select) return;
  const current = select.value;
  select.innerHTML = `<option value="">${t('Default Windows printer')}</option>`;
  try {
    const res = await req('GET', '/printer/printers');
    (res.data || []).forEach(name => {
      const option = document.createElement('option');
      option.value = name;
      option.textContent = name;
      select.appendChild(option);
    });
    if ([...select.options].some(option => option.value === current)) {
      select.value = current;
    }
  } catch (e) {
    toast(t('Could not load printer list'), 'error');
  }
}

/*  Products Table  */
function updateFinalPricePreview() {
  const priceInput = $('pm-price');
  if (!priceInput || priceInput.value) return;
  const purchase = parseFloat($('pm-purchase')?.value || '0');
  if (purchase > 0) {
    priceInput.value = purchase.toFixed(2);
  }
}

async function loadProdsTable() {
  $('prod-tbody').innerHTML = `<tr><td colspan="9" class="no-data">${t('Loading...')}</td></tr>`;
  try {
    const res = await req('GET', '/products');
    allProdTable = Array.isArray(res.data) ? res.data : [];
    renderProdsTable(allProdTable);
    $('tb-count').textContent = t('{count} products', { count: allProdTable.length });
  } catch (e) {
    $('prod-tbody').innerHTML = `<tr><td colspan="10" class="no-data" style="color:var(--red)">${t('Error')}: ${esc(e.message)}</td></tr>`;
  }
}

function filterTable(q) {
  const lq = q.toLowerCase();
  const filtered = allProdTable.filter(p =>
    p.name.toLowerCase().includes(lq) ||
    (p.barcode || '').includes(lq) ||
    (p.category?.name || '').toLowerCase().includes(lq)
  );
  renderProdsTable(filtered);
  $('tb-count').textContent = t('{shown} of {total} products', { shown: filtered.length, total: allProdTable.length });
}

function renderProdsTable(products) {
  const tbody = $('prod-tbody');
  if (!products.length) {
    tbody.innerHTML = `<tr><td colspan="10" class="no-data">${t('No products found')}</td></tr>`; return;
  }
  tbody.innerHTML = products.map(p => {
    const sc = p.stock === 0 ? 'b-red' : p.lowStock ? 'b-amber' : 'b-green';
    const inactive = p.active === false;
    return `<tr class="${inactive ? 'row-inactive' : ''}">
      <td class="td-m t-muted">#${p.id}</td>
      <td><strong>${esc(p.name)}</strong>${inactive ? ` <span class="badge b-red">${t('Inactive')}</span>` : ''}</td>
      <td class="td-m">${esc(p.barcode)}</td>
      <td><span class="badge b-blue">${esc(p.category?.name || t('None'))}</span></td>
      <td><span class="badge b-muted">${esc(unitLabel(p.unit))}</span></td>
      <td class="td-p">${fmt(p.purchasePrice)}</td>
      <td class="td-m">${fmtTax(p.taxRate)}</td>
      <td class="td-p">${fmt(p.price)}</td>
      <td><span class="badge ${sc}">${fmtQty(p.stock, p.unit)}</span></td>
      <td class="td-a">
        <button class="btn btn-secondary btn-sm" onclick="openAdjust(${p.id})">${t('Stock')}</button>
        <button class="btn btn-secondary btn-sm btn-icon" title="${t('Edit')}" onclick="openProdModalById(${p.id})">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
            <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
          </svg>
        </button>
        ${inactive ? `<button class="btn btn-secondary btn-sm" onclick="activateProd(${p.id})">${t('Reactivate')}</button>` : `<button class="btn btn-danger btn-sm btn-icon" title="${t('Deactivate')}" onclick="confirmDelete(${p.id})">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <polyline points="3 6 5 6 21 6"/>
            <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/>
          </svg>
        </button>`}
      </td>
    </tr>`;
  }).join('');
}

async function openProdModalById(productId) {
  const localProduct = allProdTable.find(product => product.id === productId) || posProducts.find(product => product.id === productId);
  if (localProduct) {
    openProdModal(localProduct);
    return;
  }
  try {
    const res = await req('GET', `/products/${productId}`);
    openProdModal(res.data);
  } catch (e) {
    toast(e.message || t('Could not load product'), 'error');
  }
}

function openProdModal(prod, barcodePrefill) {
  const editing = !!prod;
  loadCategories(prod?.category?.name || 'General');
  $('pm-title').textContent   = editing ? t('Edit Product') : t('Add Product');
  $('pm-submit').textContent  = editing ? t('Update Product') : t('Save Product');
  $('pm-id').value     = prod?.id || '';
  $('pm-name').value   = prod?.name || '';
  $('pm-barcode').value= prod?.barcode || barcodePrefill || '';
  $('pm-purchase').value = prod?.purchasePrice || '';
  $('pm-tax').value    = prod?.taxRate ?? '20';
  $('pm-unit').value   = prod?.unit || 'pcs';
  $('pm-price').value  = prod?.price || '';
  $('pm-stock').value  = prod?.stock ?? '';
  $('pm-min').value    = prod?.minStock ?? '';
  $('pm-reorder').value = prod?.reorderQuantity ?? '';
  openModal('modal-prod');
}

async function submitProd() {
  const id   = $('pm-id').value;
  const body = {
    name:         $('pm-name').value.trim(),
    barcode:      $('pm-barcode').value.trim(),
    purchasePrice: parseFloat($('pm-purchase').value),
    taxRate:      parseFloat($('pm-tax').value),
    unit:         $('pm-unit').value,
    price:        parseFloat($('pm-price').value),
    stock:        parseQuantity($('pm-stock').value),
    categoryName: $('pm-cat').value,
    minStock:     $('pm-min').value === '' ? null : parseQuantity($('pm-min').value),
    reorderQuantity: $('pm-reorder').value === '' ? null : parseQuantity($('pm-reorder').value),
  };
  if (!body.name || !body.barcode || isNaN(body.purchasePrice) || isNaN(body.taxRate) || isNaN(body.price) || isNaN(body.stock)) {
    toast(t('Please fill in all required fields'), 'error'); return;
  }
  try {
    if (id) {
      await req('PUT', `/products/${id}`, body);
      toast(t('Product updated'), 'success');
    } else {
      await req('POST', '/products', body);
      toast(t('Product added'), 'success');
    }
    closeModal('modal-prod');
    loadProdsTable();
    loadPosProducts();
    if ($('view-purchases')?.classList.contains('on')) {
      toast(t('Now scan the barcode again to add it to the purchase invoice'), 'info');
    }
  } catch (e) { toast(e.message || t('Save failed'), 'error'); }
}

function confirmDelete(id) {
  const name = allProdTable.find(product => product.id === id)?.name || '';
  $('confirm-msg').textContent = t('Deactivate "{name}"? It will no longer be sold, but stays in old sales and can be reactivated.', { name });
  $('confirm-ok').onclick = () => deleteProd(id);
  openModal('modal-confirm');
}

async function activateProd(id) {
  try {
    await req('POST', `/products/${id}/activate`);
    toast(t('Product reactivated'), 'success');
    loadProdsTable();
    loadPosProducts();
  } catch (e) { toast(e.message || t('Save failed'), 'error'); }
}

async function deleteProd(id) {
  try {
    await req('DELETE', `/products/${id}`);
    toast(t('Product deactivated'), 'success');
    closeModal('modal-confirm');
    loadProdsTable();
    loadPosProducts();
  } catch (e) { toast(e.message || t('Delete failed'), 'error'); }
}

/*  Sales Log: searched and paged on the server  */
const SALES_PAGE_SIZE = 50;
let salesPage = null;
let salesPageNumber = 0;

/* yyyy-MM-dd of a date in local time (toISOString would give the UTC day). */
function isoDay(date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

/* From/to days of a period choice; custom uses the two date inputs. */
function periodRange(period, fromInput, toInput) {
  const now = new Date();
  const today = isoDay(now);
  const daysAgo = n => isoDay(new Date(now.getFullYear(), now.getMonth(), now.getDate() - n));
  switch (period) {
    case 'today': return { from: today, to: today };
    case 'yesterday': return { from: daysAgo(1), to: daysAgo(1) };
    case 'week': return { from: daysAgo((now.getDay() + 6) % 7), to: today };
    case 'last30': return { from: daysAgo(29), to: today };
    case 'month': return { from: isoDay(new Date(now.getFullYear(), now.getMonth(), 1)), to: today };
    case 'lastMonth': return {
      from: isoDay(new Date(now.getFullYear(), now.getMonth() - 1, 1)),
      to: isoDay(new Date(now.getFullYear(), now.getMonth(), 0))
    };
    case 'year': return { from: `${now.getFullYear()}-01-01`, to: today };
    default: return { from: fromInput || today, to: toInput || today };
  }
}

function salesQuery(page) {
  const range = periodRange($('sales-period')?.value || 'last30', $('sales-from')?.value, $('sales-to')?.value);
  const params = new URLSearchParams({ from: range.from, to: range.to, page, size: SALES_PAGE_SIZE });
  const invoice = ($('sales-invoice-filter')?.value || '').trim();
  const cashier = ($('sales-cashier-filter')?.value || '').trim();
  if (invoice) params.set('invoice', invoice);
  if (cashier && isOperationalManager()) params.set('cashierName', cashier);
  return { params, range };
}

function onSalesPeriodChange() {
  const custom = $('sales-period').value === 'custom';
  $('sales-from').classList.toggle('hidden', !custom);
  $('sales-to').classList.toggle('hidden', !custom);
  loadSales();
}

let _salesFilterTmr;
function applySalesFilters() {
  clearTimeout(_salesFilterTmr);
  _salesFilterTmr = setTimeout(() => loadSales(), 300);
}

async function loadSales(page = 0) {
  $('sales-tbody').innerHTML = `<tr><td colspan="8" class="no-data">${t('Loading...')}</td></tr>`;
  try {
    const res = await req('GET', '/sales?' + salesQuery(page).params);
    salesPage = res.data;
    salesPageNumber = page;
    renderSalesTable();
    renderStats();
  } catch (e) {
    salesPage = null;
    $('sales-tbody').innerHTML = `<tr><td colspan="8" class="no-data" style="color:var(--red)">${t('Error')}: ${esc(e.message)}</td></tr>`;
    $('sales-pager').innerHTML = '';
  }
}

function renderStats() {
  const grid = $('stats-grid');
  if (!salesPage || !salesPage.totalCount) { grid.classList.add('hidden'); return; }
  const total = parseFloat(salesPage.totalAmount || 0);
  grid.classList.remove('hidden');
  grid.innerHTML = `
    <div class="stat-card sc-amber"><div class="sc-label">${t('Total Revenue')}</div><div class="sc-value">${fmt(total)}</div></div>
    <div class="stat-card sc-green"><div class="sc-label">${t('Transactions')}</div><div class="sc-value">${salesPage.totalCount}</div></div>
    <div class="stat-card sc-blue"><div class="sc-label">${t('Avg Sale')}</div><div class="sc-value">${fmt(total / salesPage.totalCount)}</div></div>
  `;
}

function renderSalesTable() {
  const tbody = $('sales-tbody');
  const rows = salesPage?.rows || [];
  if (!rows.length) {
    tbody.innerHTML = `<tr><td colspan="8" class="no-data">${t('No sales found for the selected filters.')}</td></tr>`;
    $('sales-pager').innerHTML = '';
    return;
  }
  tbody.innerHTML = rows.map(sale => `
    <tr class="sale-row" onclick="viewSale(${sale.id})">
      <td class="td-m"><strong>${esc(sale.invoiceNumber || sale.id)}</strong></td>
      <td>${fmtDate(sale.date)}</td>
      <td>${esc(sale.cashierName || t('Unknown cashier'))}</td>
      <td>${esc(sale.customerName || '')}</td>
      <td><span class="badge b-muted">${t('{count} lines', { count: sale.lines })}</span></td>
      <td>${(sale.paymentMethods || []).map(m => esc(paymentLabel(m))).join(', ')}</td>
      <td class="td-p">${fmt(sale.totalAmount)}</td>
      <td>
        <button class="btn btn-secondary btn-sm" onclick="event.stopPropagation();viewSale(${sale.id})">${t('View Details')}</button>
      </td>
    </tr>
  `).join('');
  const pages = Math.max(1, Math.ceil(salesPage.totalCount / salesPage.size));
  $('sales-pager').innerHTML = `
    <button class="btn btn-secondary btn-sm" ${salesPageNumber === 0 ? 'disabled' : ''} onclick="loadSales(${salesPageNumber - 1})">${t('Previous')}</button>
    <span>${t('Page {page} of {pages}', { page: salesPageNumber + 1, pages })}</span>
    <button class="btn btn-secondary btn-sm" ${salesPageNumber + 1 >= pages ? 'disabled' : ''} onclick="loadSales(${salesPageNumber + 1})">${t('Next')}</button>
  `;
}

async function fetchSale(saleId) {
  try {
    return (await req('GET', `/sales/${saleId}`)).data;
  } catch (e) {
    toast(e.message || t('Sale not found'), 'error');
    return null;
  }
}

async function viewSale(saleId) {
  const sale = await fetchSale(saleId);
  if (!sale) return;

  $('sale-modal-title').textContent = t('Invoice {number}', { number: sale.invoiceNumber || sale.id });
  const items = (sale.items || []);
  const itemRows = items.map(i => `
    <tr>
      <td>${esc(i.productName || i.product?.name || '')}</td>
      <td class="td-m">${esc(i.barcode || i.product?.barcode || '')}</td>
      <td class="td-m">${esc(unitLabel(i.unit || i.product?.unit))}</td>
      <td class="td-m" style="text-align:center">${fmtQty(i.quantity, i.unit)}</td>
      <td class="td-p">${fmt(i.priceWithoutTax || i.unitPriceWithoutTax || i.price)}</td>
      <td class="td-m">${fmtTax(i.taxRate)}</td>
      <td class="td-p">${fmt(i.taxAmount)}</td>
      <td class="td-p">${fmt(i.unitPrice || i.price)}</td>
      <td class="td-p" style="text-align:right">${fmt(i.lineTotal || (i.quantity * (i.unitPrice || i.price || 0)))}</td>
    </tr>
  `).join('');

  $('sale-modal-body').innerHTML = `
    <div class="sale-detail-meta">${t('Date')}: <strong>${fmtDate(sale.date)}</strong> | ${t('Cashier')}: <strong>${esc(sale.cashier?.fullName || t('Unknown cashier'))}</strong></div>
    <div class="tbl-wrap sale-detail-table" style="margin-bottom:14px;">
      <table>
        <thead><tr><th>${t('Product')}</th><th>${t('Barcode')}</th><th>${t('Unit')}</th><th style="text-align:center">${t('Qty')}</th><th>${t('Net Price')}</th><th>${t('Tax %')}</th><th>${t('Tax')}</th><th>${t('Final Price')}</th><th style="text-align:right">${t('Total')}</th></tr></thead>
        <tbody>${itemRows || `<tr><td colspan="9" class="no-data">${t('No items')}</td></tr>`}</tbody>
      </table>
    </div>
    <div class="detail-total-row">
      <span class="lbl">${t('Total Amount')}</span>
      <span class="amt">${fmt(sale.totalAmount)}</span>
    </div>
    ${(sale.payments || []).map(p => `<div class="balance-line"><span>${paymentLabel(p.method)}</span><strong>${parseFloat(p.amount).toFixed(2)} ${esc(p.currency)}${p.currency !== 'LEK' ? ` (${fmtLek(p.amountLek)})` : ''}</strong></div>`).join('')}
    ${parseFloat(sale.changeAmount) > 0 ? `<div class="balance-line"><span>${t('Change')}</span><strong>${fmtLek(sale.changeAmount)}</strong></div>` : ''}
    <div class="modal-foot" style="margin-top:18px;">
      <button class="btn btn-secondary" onclick="closeModal('modal-sale')">${t('Close')}</button>
      <button class="btn btn-danger" onclick="closeModal('modal-sale'); openRefund('${esc(sale.invoiceNumber || '')}')">${t('Refund')}</button>
      <button class="btn btn-primary" onclick="printSale(${sale.id})">
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <polyline points="6 9 6 2 18 2 18 9"/>
          <path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2"/>
          <rect x="6" y="14" width="12" height="8"/>
        </svg>
        ${t('Print Receipt')}
      </button>
    </div>
  `;
  openModal('modal-sale');
}

async function printSale(saleId) {
  const sale = await fetchSale(saleId);
  if (!sale) return;
  lastReceipt = buildFallbackReceipt(sale);
  printReceipt();
}

/* Downloads a file the server makes (Excel, PDF); errors come back as JSON and are shown as a message. */
async function downloadFile(path) {
  try {
    const res = await fetch(API + path, { headers: { 'Accept-Language': currentLang } });
    if (!res.ok) {
      const json = await res.json().catch(() => ({}));
      throw new Error(json.message || t('Request failed with status {status}', { status: res.status }));
    }
    const name = (res.headers.get('Content-Disposition') || '').match(/filename="([^"]+)"/)?.[1] || 'raport';
    const url = URL.createObjectURL(await res.blob());
    const link = document.createElement('a');
    link.href = url;
    link.download = name;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (e) { toast(e.message || t('Download failed'), 'error'); }
}

function exportSalesExcel() {
  const range = salesQuery(0).range;
  downloadFile(`/reports/sales.xlsx?from=${range.from}&to=${range.to}`);
}

/*  Reports: calculated on the server for the chosen period  */
let analytics = null;
let reportProductMode = 'top';

function reportRange() {
  return periodRange($('report-period')?.value || 'month', $('report-from')?.value, $('report-to')?.value);
}

function onReportPeriodChange() {
  const custom = $('report-period').value === 'custom';
  $('report-from').classList.toggle('hidden', !custom);
  $('report-to').classList.toggle('hidden', !custom);
  loadReports();
}

async function loadReports() {
  if ($('z-date') && !$('z-date').value) $('z-date').value = isoDay(new Date());
  const range = reportRange();
  $('report-kpis').innerHTML = `<div class="report-kpi"><span>${t('Loading reports...')}</span></div>`;
  try {
    const res = await req('GET', `/reports/analytics?from=${range.from}&to=${range.to}`);
    analytics = res.data;
    applyReportFilters();
  } catch (e) {
    $('report-kpis').innerHTML = '';
    toast(t('Could not load reports: ') + e.message, 'error');
  }
  loadEmailSettings();
}

async function loadEmailSettings() {
  try {
    const res = await req('GET', '/reports/email-settings');
    $('z-email-btn').classList.toggle('hidden', !res.data.configured);
  } catch {}
}

/* Draws everything from the loaded analysis (also after a currency or language change). */
function applyReportFilters() {
  if (!analytics) return;
  renderReportKpis();
  renderReportCharts();
  renderReportProducts();
  renderDeadStock();
}

function renderReportKpis() {
  const k = analytics.kpis;
  $('report-kpis').innerHTML = `
    <div class="report-kpi"><span>${t('Revenue')}</span><strong>${fmtLek(k.revenue)}</strong></div>
    <div class="report-kpi"><span>${t('Gross Profit')}</span><strong>${fmtLek(k.profit)}</strong><small>${t('Margin')} ${parseFloat(k.marginPercent || 0).toFixed(1)}%</small></div>
    <div class="report-kpi"><span>${t('Tax Amount')}</span><strong>${fmtLek(k.vat)}</strong></div>
    <div class="report-kpi"><span>${t('Cost of goods')}</span><strong>${fmtLek(k.cost)}</strong></div>
    <div class="report-kpi"><span>${t('Transactions')}</span><strong>${k.salesCount}</strong></div>
    <div class="report-kpi"><span>${t('Average Sale')}</span><strong>${fmtLek(k.averageSale)}</strong></div>
    <div class="report-kpi"><span>${t('Discounts')}</span><strong>${fmtLek(k.discounts)}</strong></div>
    <div class="report-kpi"><span>${t('Refunds')}</span><strong>${fmtLek(k.refunds)}</strong><small>${t('{count} refunds', { count: k.refundsCount })}</small></div>
  `;
  $('report-range-label').textContent = analytics.from === analytics.to
    ? formatDay(analytics.from)
    : `${formatDay(analytics.from)} - ${formatDay(analytics.to)}`;
}

function formatDay(iso) {
  const [y, m, d] = iso.split('-');
  return `${d}.${m}.${y}`;
}

/* Long periods are drawn by month, short ones by day. */
function revenueSeries() {
  const days = analytics.byDay;
  if (days.length <= 62) return days.map(d => ({ label: formatDay(d.day).slice(0, 5), value: parseFloat(d.revenue) }));
  const months = new Map();
  days.forEach(d => {
    const key = d.day.slice(0, 7);
    months.set(key, (months.get(key) || 0) + parseFloat(d.revenue));
  });
  return Array.from(months.entries()).map(([key, value]) => ({ label: formatMonthYear(new Date(key + '-01T00:00:00')), value }));
}

function renderReportCharts() {
  const k = analytics.kpis;
  drawLineChart('chart-revenue', revenueSeries(), t('Income'));
  drawDonutChart('chart-tax', [
    { label: t('Net sales'), value: parseFloat(k.revenueWithoutVat) },
    { label: t('Tax'), value: parseFloat(k.vat) }
  ]);
  drawDonutChart('chart-payments', analytics.byPayment
    .filter(p => parseFloat(p.revenue) > 0)
    .map(p => ({ label: paymentLabel(p.name), value: parseFloat(p.revenue) })));
  drawBarChart('chart-cashiers', analytics.byCashier.slice(0, 6).map(c => ({ label: c.name, value: parseFloat(c.revenue) })), t('Revenue'));
  drawBarChart('chart-categories', analytics.byCategory.slice(0, 8).map(c => ({ label: c.name, value: parseFloat(c.revenue) })), t('Revenue'));
  // At least the usual opening hours, wider if there were sales earlier or later.
  const open = analytics.byHour.filter(h => h.salesCount > 0).map(h => h.hour);
  const hours = open.length ? analytics.byHour.slice(Math.min(8, ...open), Math.max(21, ...open) + 1) : [];
  drawBarChart('chart-hours', hours.map(h => ({ label: String(h.hour).padStart(2, '0'), value: h.salesCount })), t('Sales by hour'));
  drawHorizontalBarChart('chart-products', analytics.products.slice(0, 8)
    .map(p => ({ label: p.name, value: Math.round(parseFloat(p.revenue)) })), t('Revenue'));
}

function setReportProductMode(mode) {
  reportProductMode = mode;
  document.querySelectorAll('[data-product-mode]').forEach(b => b.classList.toggle('on', b.dataset.productMode === mode));
  renderReportProducts();
}

function renderReportProducts() {
  const tbody = $('report-products-tbody');
  let products = analytics.products.filter(p => parseFloat(p.quantity) > 0);
  if (reportProductMode === 'slow') {
    products = products.slice().sort((a, b) => parseFloat(a.revenue) - parseFloat(b.revenue));
  }
  products = products.slice(0, 50);
  if (!products.length) {
    tbody.innerHTML = `<tr><td colspan="7" class="no-data">${t('No products sold yet')}</td></tr>`;
    return;
  }
  tbody.innerHTML = products.map(p => `
    <tr>
      <td>${esc(p.name)}</td>
      <td class="td-m">${esc(p.barcode || '')}</td>
      <td>${esc(p.category || '')}</td>
      <td class="td-m">${fmtQty(p.quantity, p.unit)} ${esc(unitLabel(p.unit))}</td>
      <td class="td-p">${fmtLek(p.revenue)}</td>
      <td class="td-p">${fmtLek(p.profit)}</td>
      <td class="td-m">${parseFloat(p.marginPercent || 0).toFixed(1)}%</td>
    </tr>
  `).join('');
}

function renderDeadStock() {
  const tbody = $('report-dead-tbody');
  const rows = analytics.deadStock;
  $('dead-stock-total').textContent = fmtLek(rows.reduce((s, r) => s + parseFloat(r.value || 0), 0));
  if (!rows.length) {
    tbody.innerHTML = `<tr><td colspan="5" class="no-data">${t('Every product in stock sold in this period')}</td></tr>`;
    return;
  }
  tbody.innerHTML = rows.map(r => `
    <tr>
      <td>${esc(r.name)}</td>
      <td class="td-m">${esc(r.barcode || '')}</td>
      <td>${esc(r.category || '')}</td>
      <td class="td-m">${fmtQty(r.stock, r.unit)} ${esc(unitLabel(r.unit))}</td>
      <td class="td-p">${fmtLek(r.value)}</td>
    </tr>
  `).join('');
}

function exportReport(type) {
  const range = reportRange();
  downloadFile(`/reports/analytics.${type}?from=${range.from}&to=${range.to}`);
}

function downloadZReportPdf() {
  downloadFile(`/reports/daily.pdf?date=${$('z-date').value || isoDay(new Date())}`);
}

async function emailZReport() {
  try {
    const res = await req('POST', `/reports/daily/email?date=${$('z-date').value || isoDay(new Date())}`);
    toast(t('Report sent to {to}', { to: res.data.sentTo.join(', ') }), 'success');
  } catch (e) { toast(e.message, 'error'); }
}

const REPORT_CHART_HEIGHTS = {
  'chart-revenue': 240,
  'chart-tax': 240,
  'chart-payments': 240,
  'chart-cashiers': 260,
  'chart-categories': 260,
  'chart-products': 260,
  'chart-hours': 220
};

function setupCanvas(id) {
  let canvas = $(id);
  if (!canvas) return null;
  const dpr = window.devicePixelRatio || 1;

  const displayHeight = REPORT_CHART_HEIGHTS[id] || 240;
  const cleanCanvas = canvas.cloneNode(false);
  cleanCanvas.id = id;
  cleanCanvas.setAttribute('height', String(displayHeight));
  cleanCanvas.style.height = displayHeight + 'px';
  cleanCanvas.style.width = '100%';
  canvas.replaceWith(cleanCanvas);
  canvas = cleanCanvas;

  canvas.style.height = displayHeight + 'px';
  const rect = canvas.getBoundingClientRect();
  canvas.width = Math.round(Math.max(320, rect.width) * dpr);
  canvas.height = Math.round(displayHeight * dpr);
  const ctx = canvas.getContext('2d');
  ctx.scale(dpr, dpr);
  return { canvas, ctx, width: canvas.width / dpr, height: displayHeight };
}

function chartColors() {
  const style = getComputedStyle(document.documentElement);
  return {
    blue: style.getPropertyValue('--amber').trim() || '#2563EB',
    green: style.getPropertyValue('--green').trim() || '#10B981',
    purple: style.getPropertyValue('--blue').trim() || '#6366F1',
    red: style.getPropertyValue('--red').trim() || '#EF4444',
    muted: style.getPropertyValue('--muted').trim() || '#64748B',
    border: style.getPropertyValue('--border').trim() || '#C7D7FE',
    text: style.getPropertyValue('--text').trim() || '#172033'
  };
}

function drawEmptyChart(ctx, width, height, message) {
  const c = chartColors();
  ctx.clearRect(0, 0, width, height);
  ctx.fillStyle = c.muted;
  ctx.font = '600 14px Barlow, sans-serif';
  ctx.textAlign = 'center';
  ctx.fillText(message, width / 2, height / 2);
}

function drawLineChart(id, data, label) {
  const setup = setupCanvas(id);
  if (!setup) return;
  const { ctx, width, height } = setup;
  const c = chartColors();
  ctx.clearRect(0, 0, width, height);
  if (!data.length) { drawEmptyChart(ctx, width, height, t('No income data yet')); return; }
  const pad = 34;
  const max = Math.max(...data.map(d => d.value), 1);
  const step = data.length > 1 ? (width - pad * 2) / (data.length - 1) : 0;

  ctx.strokeStyle = c.border;
  ctx.lineWidth = 1;
  for (let i = 0; i < 4; i++) {
    const y = pad + ((height - pad * 2) / 3) * i;
    ctx.beginPath(); ctx.moveTo(pad, y); ctx.lineTo(width - pad, y); ctx.stroke();
  }

  ctx.strokeStyle = c.blue;
  ctx.lineWidth = 3;
  ctx.beginPath();
  data.forEach((d, i) => {
    const x = data.length === 1 ? width / 2 : pad + i * step;
    const y = height - pad - (d.value / max) * (height - pad * 2);
    if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
  });
  ctx.stroke();

  data.forEach((d, i) => {
    const x = data.length === 1 ? width / 2 : pad + i * step;
    const y = height - pad - (d.value / max) * (height - pad * 2);
    ctx.fillStyle = c.blue;
    ctx.beginPath(); ctx.arc(x, y, 4, 0, Math.PI * 2); ctx.fill();
    if (i % Math.ceil(data.length / 6) === 0) {
      ctx.fillStyle = c.muted;
      ctx.font = '600 11px Barlow, sans-serif';
      ctx.textAlign = 'center';
      ctx.fillText(shortLabel(d.label), x, height - 8);
    }
  });

  ctx.fillStyle = c.text;
  ctx.font = '700 12px Barlow, sans-serif';
  ctx.textAlign = 'left';
  ctx.fillText(label + ': ' + fmtLek(data.reduce((s, d) => s + d.value, 0)), pad, 16);
}

function drawBarChart(id, data, label) {
  const setup = setupCanvas(id);
  if (!setup) return;
  const { ctx, width, height } = setup;
  const c = chartColors();
  ctx.clearRect(0, 0, width, height);
  if (!data.length) { drawEmptyChart(ctx, width, height, t('No data for this chart')); return; }
  const pad = 34;
  const max = Math.max(...data.map(d => d.value), 1);
  const gap = 10;
  const barW = Math.min(64, Math.max(18, (width - pad * 2 - gap * (data.length - 1)) / data.length));
  const left = (width - data.length * barW - gap * (data.length - 1)) / 2;
  data.forEach((d, i) => {
    const x = left + i * (barW + gap);
    const h = (d.value / max) * (height - pad * 2);
    const y = height - pad - h;
    ctx.fillStyle = i % 2 ? c.green : c.blue;
    roundRect(ctx, x, y, barW, h, 6);
    ctx.fill();
    ctx.fillStyle = c.muted;
    ctx.font = '600 11px Barlow, sans-serif';
    ctx.textAlign = 'center';
    ctx.fillText(shortLabel(d.label, Math.max(3, Math.floor((barW + gap) / 7))), x + barW / 2, height - 9);
  });
  ctx.fillStyle = c.text;
  ctx.font = '700 12px Barlow, sans-serif';
  ctx.textAlign = 'left';
  ctx.fillText(label, pad, 16);
}

function drawHorizontalBarChart(id, data, label) {
  const setup = setupCanvas(id);
  if (!setup) return;
  const { ctx, width, height } = setup;
  const c = chartColors();
  ctx.clearRect(0, 0, width, height);
  if (!data.length) { drawEmptyChart(ctx, width, height, t('No products sold yet')); return; }
  const max = Math.max(...data.map(d => d.value), 1);
  const left = 104;
  const top = 30;
  const rowH = Math.min(28, (height - top - 16) / data.length);
  data.forEach((d, i) => {
    const y = top + i * rowH;
    const w = (d.value / max) * (width - left - 34);
    ctx.fillStyle = c.muted;
    ctx.font = '600 11px Barlow, sans-serif';
    ctx.textAlign = 'right';
    ctx.fillText(shortLabel(d.label, 12), left - 10, y + 15);
    ctx.fillStyle = i % 2 ? c.purple : c.blue;
    roundRect(ctx, left, y, w, Math.max(8, rowH - 9), 5);
    ctx.fill();
    ctx.fillStyle = c.text;
    ctx.textAlign = 'left';
    ctx.fillText(String(d.value), left + w + 7, y + 15);
  });
  ctx.fillStyle = c.text;
  ctx.font = '700 12px Barlow, sans-serif';
  ctx.textAlign = 'left';
  ctx.fillText(label, 12, 16);
}

function drawDonutChart(id, data, emptyMessage) {
  const setup = setupCanvas(id);
  if (!setup) return;
  const { ctx, width, height } = setup;
  const c = chartColors();
  ctx.clearRect(0, 0, width, height);
  const total = data.reduce((s, d) => s + d.value, 0);
  if (!total) { drawEmptyChart(ctx, width, height, emptyMessage || t('No data for this chart')); return; }
  const colors = [c.green, c.blue, c.purple, c.red];
  const cx = width / 2;
  const legendTop = height - 14 - data.length * 20;
  const cy = legendTop / 2 + 4;
  const radius = Math.min(width / 3, legendTop / 2 - 16);
  let start = -Math.PI / 2;
  data.forEach((d, i) => {
    const angle = (d.value / total) * Math.PI * 2;
    ctx.beginPath();
    ctx.arc(cx, cy, radius, start, start + angle);
    ctx.lineWidth = 24;
    ctx.strokeStyle = colors[i % colors.length];
    ctx.stroke();
    start += angle;
  });
  ctx.fillStyle = c.text;
  ctx.font = '700 18px Barlow, sans-serif';
  ctx.textAlign = 'center';
  ctx.fillText(fmtLek(total), cx, cy + 5);
  ctx.font = '600 12px Barlow, sans-serif';
  data.forEach((d, i) => {
    ctx.fillStyle = colors[i % colors.length];
    ctx.fillRect(26, legendTop + i * 20, 10, 10);
    ctx.fillStyle = c.muted;
    ctx.textAlign = 'left';
    ctx.fillText(`${d.label}: ${fmtLek(d.value)}`, 44, legendTop + 9 + i * 20);
  });
}

function roundRect(ctx, x, y, w, h, r) {
  const radius = Math.min(r, Math.abs(h) / 2, Math.abs(w) / 2);
  ctx.beginPath();
  ctx.moveTo(x + radius, y);
  ctx.lineTo(x + w - radius, y);
  ctx.quadraticCurveTo(x + w, y, x + w, y + radius);
  ctx.lineTo(x + w, y + h - radius);
  ctx.quadraticCurveTo(x + w, y + h, x + w - radius, y + h);
  ctx.lineTo(x + radius, y + h);
  ctx.quadraticCurveTo(x, y + h, x, y + h - radius);
  ctx.lineTo(x, y + radius);
  ctx.quadraticCurveTo(x, y, x + radius, y);
}

function shortLabel(label, max = 10) {
  const text = String(label || '');
  return text.length > max ? text.slice(0, max - 1) + '.' : text;
}

/*  User management  */
async function loadUsers() {
  if (!isSuperAdmin()) return;
  const tbody = $('users-tbody');
  if (tbody) tbody.innerHTML = `<tr><td colspan="6" class="no-data">${t('Loading users...')}</td></tr>`;
  try {
    const res = await req('GET', '/users');
    allUsers = Array.isArray(res.data) ? res.data : [];
    renderUsers(allUsers);
  } catch (e) {
    if (tbody) tbody.innerHTML = `<tr><td colspan="6" class="no-data" style="color:var(--red)">${esc(e.message)}</td></tr>`;
  }
}

function filterUsers(query) {
  const value = String(query || '').trim().toLowerCase();
  renderUsers(allUsers.filter(user =>
    user.fullName.toLowerCase().includes(value) ||
    user.username.toLowerCase().includes(value) ||
    user.role.toLowerCase().includes(value)
  ));
}

function renderUsers(users) {
  const tbody = $('users-tbody');
  if (!tbody) return;
  $('users-count').textContent = t('{count} users', { count: users.length });
  if (!users.length) {
    tbody.innerHTML = `<tr><td colspan="6" class="no-data">${t('No users found.')}</td></tr>`;
    return;
  }
  tbody.innerHTML = users.map(user => `
    <tr>
      <td class="td-m">#${user.id}</td>
      <td><strong>${esc(user.fullName)}</strong></td>
      <td class="td-m">${esc(user.username)}</td>
      <td><span class="badge ${user.role === 'SUPER_ADMIN' ? 'b-blue' : user.role === 'SUPER_CASHIER' ? 'b-green' : 'b-muted'}">${esc(roleLabel(user.role))}</span></td>
      <td><span class="badge ${user.active ? 'b-green' : 'b-red'}">${user.active ? t('Active') : t('Disabled')}</span>${user.hasApprovalPin ? ` <span class="badge b-blue">${t('PIN')}</span>` : ''}</td>
      <td><button class="btn btn-secondary btn-sm" onclick="openUserModal(${user.id})">${t('Edit')}</button></td>
    </tr>
  `).join('');
}

function openUserModal(userId) {
  if (!isSuperAdmin()) return;
  const user = allUsers.find(item => item.id === userId);
  $('um-title').textContent = user ? t('Edit User') : t('Add User');
  $('um-submit').textContent = user ? t('Update User') : t('Save User');
  $('um-id').value = user?.id || '';
  $('um-name').value = user?.fullName || '';
  $('um-username').value = user?.username || '';
  $('um-password').value = '';
  $('um-password').placeholder = user ? t('Leave empty to keep current password') : t('Minimum 4 characters');
  $('um-role').value = user?.role || 'CASHIER';
  $('um-active').value = String(user?.active ?? true);
  $('um-pin').value = '';
  $('um-pin').placeholder = user?.hasApprovalPin ? t('Leave empty to keep the current PIN') : t('4 to 8 digits');
  updatePinField();
  openModal('modal-user');
}

/* Only managers approve, so only they have a PIN. */
function updatePinField() {
  $('um-pin-group').classList.toggle('hidden', $('um-role').value === 'CASHIER');
}

async function submitUser() {
  const id = $('um-id').value;
  const body = {
    fullName: $('um-name').value.trim(),
    username: $('um-username').value.trim(),
    password: $('um-password').value,
    role: $('um-role').value,
    active: $('um-active').value === 'true',
    approvalPin: $('um-role').value === 'CASHIER' ? '' : $('um-pin').value.trim()
  };
  if (!body.fullName || !body.username || (!id && body.password.length < 4)) {
    toast(t('Name, username, and a password of at least 4 characters are required'), 'error');
    return;
  }
  try {
    if (id) await req('PUT', `/users/${id}`, body);
    else await req('POST', '/users', body);
    closeModal('modal-user');
    toast(id ? t('User updated') : t('User created'), 'success');
    loadUsers();
  } catch (e) {
    toast(e.message || t('Could not save user'), 'error');
  }
}

/*  Suppliers  */
let allSuppliers = [];
let currentSupplierId = null;

async function loadSupplierOptions() {
  try {
    const res = await req('GET', '/suppliers');
    allSuppliers = res.data || [];
    $('supplier-options').innerHTML = allSuppliers.filter(s => s.active)
      .map(s => `<option value="${esc(s.name)}"></option>`).join('');
  } catch {}
}

async function loadSuppliers() {
  const tbody = $('suppliers-tbody');
  tbody.innerHTML = `<tr><td colspan="7" class="no-data">${t('Loading...')}</td></tr>`;
  try {
    const res = await req('GET', '/suppliers');
    allSuppliers = res.data || [];
    tbody.innerHTML = allSuppliers.length ? allSuppliers.map(s => `
      <tr class="${s.active ? '' : 'row-inactive'}">
        <td><strong>${esc(s.name)}</strong></td>
        <td class="td-m">${esc(s.taxNumber || '')}</td>
        <td>${esc(s.phone || '')}</td>
        <td class="td-p">${fmtLek(s.totalInvoiced)}</td>
        <td class="td-p">${fmtLek(s.totalPaid)}</td>
        <td class="td-p"><strong class="${parseFloat(s.balance) > 0 ? 'diff-minus' : ''}">${fmtLek(s.balance)}</strong></td>
        <td class="td-a">
          <button class="btn btn-secondary btn-sm" onclick="openSupplierDetail(${s.id})">${t('Details')}</button>
          <button class="btn btn-secondary btn-sm" onclick="openSupplierModal(${s.id})">${t('Edit')}</button>
        </td>
      </tr>`).join('') : `<tr><td colspan="7" class="no-data">${t('No suppliers yet.')}</td></tr>`;
  } catch (e) {
    tbody.innerHTML = `<tr><td colspan="7" class="no-data" style="color:var(--red)">${esc(e.message)}</td></tr>`;
  }
}

function openSupplierModal(id) {
  const s = allSuppliers.find(x => x.id === id);
  $('sm-title').textContent = s ? t('Edit supplier') : t('Add supplier');
  $('sm-id').value = s?.id || '';
  $('sm-name').value = s?.name || '';
  $('sm-tax').value = s?.taxNumber || '';
  $('sm-phone').value = s?.phone || '';
  $('sm-email').value = s?.email || '';
  $('sm-address').value = s?.address || '';
  $('sm-notes').value = s?.notes || '';
  openModal('modal-supplier');
}

async function submitSupplier() {
  const id = $('sm-id').value;
  const body = { name: $('sm-name').value.trim(), taxNumber: $('sm-tax').value, phone: $('sm-phone').value,
    email: $('sm-email').value, address: $('sm-address').value, notes: $('sm-notes').value, active: true };
  try {
    if (id) await req('PUT', `/suppliers/${id}`, body);
    else await req('POST', '/suppliers', body);
    closeModal('modal-supplier');
    toast(t('Supplier saved'), 'success');
    loadSuppliers();
  } catch (e) { toast(e.message, 'error'); }
}

async function openSupplierDetail(id) {
  currentSupplierId = id;
  try {
    const res = await req('GET', `/suppliers/${id}`);
    const d = res.data;
    const s = d.supplier;
    $('sd-title').textContent = s.name;
    $('sd-meta').textContent = [s.taxNumber && `NIPT ${s.taxNumber}`, s.phone, s.email, s.address].filter(Boolean).join(' | ');
    $('sd-kpis').innerHTML = `
      <div class="report-kpi"><span>${t('Invoiced')}</span><strong>${fmtLek(s.totalInvoiced)}</strong></div>
      <div class="report-kpi"><span>${t('Paid')}</span><strong>${fmtLek(s.totalPaid)}</strong></div>
      <div class="report-kpi"><span>${t('Owed')}</span><strong>${fmtLek(s.balance)}</strong></div>`;
    $('sd-invoices').innerHTML = d.invoices.map(i => `
      <tr><td class="td-m">${esc(i.invoiceNumber)}</td><td>${esc(i.invoiceDate)}</td><td class="td-p">${fmtLek(i.totalAmount)}</td></tr>`).join('')
      || `<tr><td colspan="3" class="no-data">-</td></tr>`;
    $('sd-payments').innerHTML = d.payments.map(p => `
      <tr><td>${esc(p.paidOn)}</td><td>${esc(p.method === 'CASH' ? t('Cash') : t('Bank'))}</td><td class="td-p">${fmtLek(p.amount)}</td><td>${esc(p.note || '')}</td></tr>`).join('')
      || `<tr><td colspan="4" class="no-data">-</td></tr>`;
    $('sp-amount').value = '';
    $('sp-note').value = '';
    $('sp-date').value = new Date().toISOString().slice(0, 10);
    openModal('modal-supplier-detail');
  } catch (e) { toast(e.message, 'error'); }
}

async function addSupplierPayment() {
  try {
    await req('POST', `/suppliers/${currentSupplierId}/payments`, {
      amount: parseFloat($('sp-amount').value || '0'), paidOn: $('sp-date').value || null,
      method: $('sp-method').value, note: $('sp-note').value
    });
    toast(t('Payment saved'), 'success');
    openSupplierDetail(currentSupplierId);
    loadSuppliers();
  } catch (e) { toast(e.message, 'error'); }
}

/*  Inventory  */
let currentInventoryTab = 'reorder';
let currentCount = null;

function showInventoryTab(tab) {
  currentInventoryTab = tab;
  document.querySelectorAll('.inv-tab').forEach(b => b.classList.toggle('on', b.dataset.tab === tab));
  document.querySelectorAll('.inv-panel').forEach(p => p.classList.toggle('hidden', p.id !== 'inv-' + tab));
  if (tab === 'reorder') loadReorder();
  if (tab === 'expiring') loadExpiring();
  if (tab === 'count') loadCount();
}

async function loadReorder() {
  const box = $('reorder-groups');
  box.innerHTML = `<p class="no-data">${t('Loading...')}</p>`;
  try {
    const res = await req('GET', '/inventory/reorder');
    const groups = res.data || [];
    box.innerHTML = groups.length ? groups.map(g => `
      <div class="reorder-group">
        <h3>${esc(g.supplierName || t('No supplier yet'))} — ${t('estimated {amount}', { amount: fmtLek(g.estimatedTotal) })}</h3>
        <div class="tbl-wrap"><table>
          <thead><tr><th>${t('Product')}</th><th>${t('Barcode')}</th><th>${t('Stock')}</th><th>${t('Minimum stock')}</th><th>${t('To order')}</th><th>${t('Last cost')}</th><th>${t('Total')}</th></tr></thead>
          <tbody>${g.lines.map(l => `
            <tr><td><strong>${esc(l.productName)}</strong></td><td class="td-m">${esc(l.barcode)}</td>
              <td><span class="badge b-amber">${fmtQty(l.stock, l.unit)}</span></td><td class="td-m">${fmtQty(l.minStock, l.unit)}</td>
              <td><strong>${fmtQty(l.suggestedQuantity, l.unit)} ${esc(unitLabel(l.unit))}</strong></td>
              <td class="td-p">${fmtLek(l.lastPurchasePrice)}</td><td class="td-p">${fmtLek(l.estimatedCost)}</td></tr>`).join('')}
          </tbody></table></div>
      </div>`).join('') : `<p class="no-data">${t('Nothing to reorder: every product is above its minimum stock.')}</p>`;
  } catch (e) { box.innerHTML = `<p class="no-data" style="color:var(--red)">${esc(e.message)}</p>`; }
}

async function loadExpiring() {
  const tbody = $('expiring-tbody');
  tbody.innerHTML = `<tr><td colspan="6" class="no-data">${t('Loading...')}</td></tr>`;
  try {
    const res = await req('GET', `/inventory/expiring?days=${$('expiry-days').value}`);
    const lines = res.data || [];
    tbody.innerHTML = lines.length ? lines.map(l => `
      <tr>
        <td><strong>${esc(l.productName)}</strong></td>
        <td>${esc(l.expiryDate)}</td>
        <td><span class="badge ${l.daysLeft < 0 ? 'b-red' : l.daysLeft <= 3 ? 'b-amber' : 'b-blue'}">${l.daysLeft < 0 ? t('Expired') : l.daysLeft}</span></td>
        <td>${fmtQty(l.estimatedOnShelf, l.unit)} ${esc(unitLabel(l.unit))}</td>
        <td>${esc(l.supplierName || '')}</td>
        <td class="td-m">${esc(l.invoiceNumber)}</td>
      </tr>`).join('') : `<tr><td colspan="6" class="no-data">${t('Nothing expires in this period.')}</td></tr>`;
  } catch (e) { tbody.innerHTML = `<tr><td colspan="6" class="no-data" style="color:var(--red)">${esc(e.message)}</td></tr>`; }
}

async function loadCount() {
  try {
    const res = await req('GET', '/inventory/counts/current');
    renderCount(res.data || null);
  } catch (e) { toast(e.message, 'error'); }
}

function renderCount(count) {
  currentCount = count && count.status === 'OPEN' ? count : null;
  const open = !!currentCount;
  $('count-start').classList.toggle('hidden', open);
  $('count-apply').classList.toggle('hidden', !open);
  $('count-cancel').classList.toggle('hidden', !open);
  $('count-entry').classList.toggle('hidden', !open);
  $('count-status').textContent = open
    ? t('Count started {date} by {name}: {lines} products counted, {diff} with a difference',
        { date: formatDateTime(count.startedAt), name: count.startedByName, lines: count.lines.length, diff: count.linesWithDifference })
    : t('No count in progress. Start one, scan each product and type how many are on the shelf.');
  $('count-tbody').innerHTML = open ? count.lines.map(l => {
    const diff = parseFloat(l.difference);
    return `<tr>
      <td><strong>${esc(l.productName)}</strong></td><td class="td-m">${esc(l.barcode)}</td>
      <td>${fmtQty(l.countedQuantity, l.unit)}</td><td>${fmtQty(l.currentStock, l.unit)}</td>
      <td class="${diff < 0 ? 'diff-minus' : diff > 0 ? 'diff-plus' : ''}">${diff > 0 ? '+' : ''}${fmtQty(diff, l.unit)}</td>
    </tr>`;
  }).join('') : '';
  if (open) setTimeout(() => $('count-barcode').focus(), 50);
}

async function startCount() {
  try {
    const res = await req('POST', '/inventory/counts', {});
    renderCount(res.data);
  } catch (e) { toast(e.message, 'error'); }
}

async function saveCountLine() {
  if (!currentCount) return;
  const barcode = $('count-barcode').value.trim();
  const qty = parseQuantity($('count-qty').value);
  if (!barcode || Number.isNaN(qty)) { toast(t('Scan a product and enter the quantity'), 'error'); return; }
  try {
    const res = await req('PUT', `/inventory/counts/${currentCount.id}/lines`, { barcode, countedQuantity: qty });
    $('count-barcode').value = '';
    $('count-qty').value = '';
    renderCount(res.data);
  } catch (e) { toast(e.message, 'error'); }
}

async function applyCount() {
  if (!currentCount || !confirm(t('Set the stock of the {count} counted products to what was found?', { count: currentCount.lines.length }))) return;
  try {
    const res = await req('POST', `/inventory/counts/${currentCount.id}/apply`);
    toast(t('Stock count applied'), 'success');
    renderCount(res.data);
  } catch (e) { toast(e.message, 'error'); }
}

async function cancelCount() {
  if (!currentCount || !confirm(t('Cancel this count? Nothing will change.'))) return;
  try {
    const res = await req('POST', `/inventory/counts/${currentCount.id}/cancel`);
    renderCount(res.data);
  } catch (e) { toast(e.message, 'error'); }
}

/* Stock adjustment from the product list */
const ADJUST_REASONS = { DAMAGED: 'Damaged', EXPIRED: 'Expired', LOST: 'Lost / stolen', INTERNAL_USE: 'Internal use',
  COUNT_CORRECTION: 'Stock count', OTHER: 'Other' };

async function openAdjust(productId) {
  const p = allProdTable.find(x => x.id === productId);
  if (!p) return;
  $('adj-product').value = productId;
  $('adj-meta').textContent = `${p.name} — ${t('Stock')}: ${fmtQty(p.stock, p.unit)} ${unitLabel(p.unit)}`;
  $('adj-qty').value = '';
  $('adj-note').value = '';
  $('adj-qty').step = p.unit === 'kg' ? '0.001' : '1';
  $('adj-history').innerHTML = '';
  openModal('modal-adjust');
  try {
    const res = await req('GET', `/inventory/adjustments?productId=${productId}`);
    $('adj-history').innerHTML = (res.data || []).map(a => `
      <tr><td class="td-m">${formatDateTime(a.createdAt)}</td>
        <td class="${parseFloat(a.quantityChange) < 0 ? 'diff-minus' : 'diff-plus'}">${parseFloat(a.quantityChange) > 0 ? '+' : ''}${fmtQty(a.quantityChange, p.unit)}</td>
        <td>${esc(t(ADJUST_REASONS[a.reason] || a.reason))}${a.note ? ' - ' + esc(a.note) : ''}</td>
        <td>${fmtQty(a.stockAfter, p.unit)}</td><td>${esc(a.cashierName)}</td></tr>`).join('')
      || `<tr><td colspan="5" class="no-data">-</td></tr>`;
  } catch {}
}

async function submitAdjustment() {
  const qty = parseQuantity($('adj-qty').value);
  if (Number.isNaN(qty) || qty <= 0) { toast(t('Enter a valid quantity'), 'error'); return; }
  try {
    await req('POST', '/inventory/adjustments', {
      productId: parseInt($('adj-product').value, 10),
      quantityChange: qty * parseInt($('adj-direction').value, 10),
      reason: $('adj-reason').value,
      note: $('adj-note').value
    });
    closeModal('modal-adjust');
    toast(t('Stock adjusted'), 'success');
    loadProdsTable();
    loadPosProducts();
  } catch (e) { toast(e.message, 'error'); }
}

/* Products to and from Excel (CSV) */
function exportProducts() {
  const link = document.createElement('a');
  link.href = '/products/export';
  link.download = 'products.csv';
  link.click();
}

async function importProducts(input) {
  const file = input.files[0];
  input.value = '';
  if (!file) return;
  try {
    const res = await fetch('/products/import', {
      method: 'POST', body: await file.text(),
      headers: { 'Content-Type': 'text/csv; charset=utf-8', 'Accept-Language': currentLang }
    });
    const json = await res.json();
    if (!res.ok || json.success === false) throw new Error(json.message || t('Import failed'));
    const r = json.data;
    const summary = t('{created} created, {updated} updated, {errors} lines with errors',
      { created: r.created, updated: r.updated, errors: r.errors.length });
    if (r.errors.length) {
      alert(summary + '\n\n' + r.errors.map(e => `${t('Line')} ${e.line}${e.barcode ? ' (' + e.barcode + ')' : ''}: ${e.message}`).join('\n'));
    }
    toast(summary, r.errors.length ? 'info' : 'success');
    loadProdsTable();
    loadPosProducts();
  } catch (e) { toast(e.message, 'error'); }
}

/*  Customers  */
let allCustomers = [];
let currentCustomerId = null;
let customerFilterTimer;

function loadCustomers() {
  clearTimeout(customerFilterTimer);
  customerFilterTimer = setTimeout(async () => {
    const tbody = $('customers-tbody');
    const query = $('customer-filter').value.trim();
    try {
      const res = await req('GET', `/customers${query ? '?query=' + encodeURIComponent(query) : ''}`);
      allCustomers = res.data || [];
      tbody.innerHTML = allCustomers.length ? allCustomers.map(c => `
        <tr class="${c.active ? '' : 'row-inactive'}">
          <td class="td-m">${esc(c.cardNumber)}</td><td><strong>${esc(c.fullName)}</strong></td><td>${esc(c.phone || '')}</td>
          <td>${c.points}</td>
          <td class="td-p"><strong class="${parseFloat(c.balance) > 0 ? 'diff-minus' : ''}">${fmtLek(c.balance)}</strong></td>
          <td class="td-p">${c.creditLimit == null ? '-' : fmtLek(c.creditLimit)}</td>
          <td class="td-a">
            <button class="btn btn-secondary btn-sm" onclick="openCustomerDetail(${c.id})">${t('Details')}</button>
            <button class="btn btn-secondary btn-sm" onclick="openCustomerModal(${c.id})">${t('Edit')}</button>
          </td>
        </tr>`).join('') : `<tr><td colspan="7" class="no-data">${t('No customer found.')}</td></tr>`;
    } catch (e) { tbody.innerHTML = `<tr><td colspan="7" class="no-data" style="color:var(--red)">${esc(e.message)}</td></tr>`; }
  }, 200);
}

/* attach: after saving a new customer from the till, put them on the sale. */
function openCustomerModal(id, attach) {
  const c = allCustomers.find(x => x.id === id);
  $('cm-title').textContent = c ? t('Edit customer') : t('Add customer');
  $('cm-id').value = c?.id || '';
  $('cm-attach').value = attach ? '1' : '';
  $('cm-name').value = c?.fullName || '';
  $('cm-phone').value = c?.phone || '';
  $('cm-card').value = c?.cardNumber || '';
  $('cm-email').value = c?.email || '';
  $('cm-notes').value = c?.notes || '';
  $('cm-limit').value = c?.creditLimit ?? '';
  openModal('modal-customer');
}

async function submitCustomer() {
  const id = $('cm-id').value;
  const body = { fullName: $('cm-name').value.trim(), phone: $('cm-phone').value, cardNumber: $('cm-card').value,
    email: $('cm-email').value, notes: $('cm-notes').value, active: true };
  if (isOperationalManager()) body.creditLimit = $('cm-limit').value === '' ? 0 : parseFloat($('cm-limit').value);
  try {
    const res = id ? await req('PUT', `/customers/${id}`, body) : await req('POST', '/customers', body);
    closeModal('modal-customer');
    toast(t('Customer saved'), 'success');
    if ($('cm-attach').value) await setCartCustomer(res.data.id);
    if ($('view-customers')?.classList.contains('on')) loadCustomers();
  } catch (e) { toast(e.message, 'error'); }
}

const CUSTOMER_TX_LABELS = { SALE: 'Sale', REFUND: 'Refund', PAYMENT: 'Payment' };

async function openCustomerDetail(id) {
  currentCustomerId = id;
  try {
    const res = await req('GET', `/customers/${id}`);
    const c = res.data.customer;
    $('cd-title').textContent = c.fullName;
    $('cd-meta').textContent = [t('Card') + ' ' + c.cardNumber, c.phone, c.email].filter(Boolean).join(' | ');
    $('cd-kpis').innerHTML = `
      <div class="report-kpi"><span>${t('Points')}</span><strong>${c.points}</strong></div>
      <div class="report-kpi"><span>${t('Debt')}</span><strong>${fmtLek(c.balance)}</strong></div>
      <div class="report-kpi"><span>${t('Credit limit')}</span><strong>${c.creditLimit == null ? '-' : fmtLek(c.creditLimit)}</strong></div>`;
    $('cd-transactions').innerHTML = (res.data.transactions || []).map(tx => `
      <tr><td class="td-m">${formatDateTime(tx.createdAt)}</td><td>${esc(t(CUSTOMER_TX_LABELS[tx.type] || tx.type))}</td>
        <td class="td-m">${esc(tx.invoiceNumber || tx.refundNumber || '')}</td>
        <td>${tx.pointsChange > 0 ? '+' : ''}${tx.pointsChange}</td>
        <td class="${parseFloat(tx.balanceChange) > 0 ? 'diff-minus' : parseFloat(tx.balanceChange) < 0 ? 'diff-plus' : ''}">${fmtLek(tx.balanceChange)}</td>
        <td>${esc(tx.note || '')}</td><td>${esc(tx.cashierName)}</td></tr>`).join('')
      || `<tr><td colspan="7" class="no-data">-</td></tr>`;
    $('cp-amount').value = '';
    $('cp-note').value = '';
    openModal('modal-customer-detail');
  } catch (e) { toast(e.message, 'error'); }
}

async function receiveCustomerPayment() {
  try {
    await req('POST', `/customers/${currentCustomerId}/payments`, {
      amount: parseFloat($('cp-amount').value || '0'), method: $('cp-method').value, note: $('cp-note').value
    });
    toast(t('Payment saved'), 'success');
    openCustomerDetail(currentCustomerId);
    loadCustomers();
  } catch (e) { toast(e.message, 'error'); }
}

/*  Promotions  */
let allPromotions = [];
const DAY_NAMES = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

async function loadPromotions() {
  const tbody = $('promotions-tbody');
  try {
    const res = await req('GET', '/promotions');
    allPromotions = res.data || [];
    tbody.innerHTML = allPromotions.length ? allPromotions.map(p => {
      const offer = p.type === 'PERCENT' ? `-${parseFloat(p.discountPercent)}%` : t('Buy {buy}, get {free} free', { buy: p.buyQuantity, free: p.freeQuantity });
      const when = [
        p.startsOn || p.endsOn ? `${p.startsOn || '…'} → ${p.endsOn || '…'}` : '',
        p.daysOfWeek ? p.daysOfWeek.split(',').map(d => t(DAY_NAMES[d - 1])).join(', ') : '',
        p.startTime || p.endTime ? `${(p.startTime || '00:00').slice(0, 5)}-${(p.endTime || '24:00').slice(0, 5)}` : ''
      ].filter(Boolean).join(' | ') || t('Always');
      return `<tr class="${p.active ? '' : 'row-inactive'}">
        <td><strong>${esc(p.name)}</strong></td><td>${esc(offer)}</td>
        <td>${esc(p.productName || (t('Category') + ': ' + (p.categoryName || '')))}</td><td>${esc(when)}</td>
        <td><span class="badge ${p.active ? 'b-green' : 'b-muted'}">${p.active ? t('Active') : t('Inactive')}</span></td>
        <td class="td-a"><button class="btn btn-secondary btn-sm" onclick="openPromotionModal(${p.id})">${t('Edit')}</button></td>
      </tr>`;
    }).join('') : `<tr><td colspan="6" class="no-data">${t('No promotions yet.')}</td></tr>`;
  } catch (e) { tbody.innerHTML = `<tr><td colspan="6" class="no-data" style="color:var(--red)">${esc(e.message)}</td></tr>`; }
}

async function openPromotionModal(id) {
  const p = allPromotions.find(x => x.id === id);
  await loadCategories();
  $('pr-category').innerHTML = allCategories.filter(c => c.id).map(c => `<option value="${c.id}">${esc(c.name)}</option>`).join('');
  $('pr-days').innerHTML = DAY_NAMES.map((d, i) => `<label><input type="checkbox" value="${i + 1}"
      ${p?.daysOfWeek?.split(',').includes(String(i + 1)) ? 'checked' : ''} /> ${esc(t(d))}</label>`).join('');
  $('pr-title').textContent = p ? t('Edit promotion') : t('Add promotion');
  $('pr-id').value = p?.id || '';
  $('pr-name').value = p?.name || '';
  $('pr-type').value = p?.type || 'PERCENT';
  $('pr-percent').value = p?.discountPercent ?? '';
  $('pr-buy').value = p?.buyQuantity ?? 2;
  $('pr-free').value = p?.freeQuantity ?? 1;
  $('pr-scope').value = p?.categoryId ? 'category' : 'product';
  $('pr-barcode').value = p?.productId ? (allProdTable.find(x => x.id === p.productId)?.barcode || '') : '';
  if (p?.categoryId) $('pr-category').value = p.categoryId;
  $('pr-starts').value = p?.startsOn || '';
  $('pr-ends').value = p?.endsOn || '';
  $('pr-start-time').value = (p?.startTime || '').slice(0, 5);
  $('pr-end-time').value = (p?.endTime || '').slice(0, 5);
  $('pr-active').checked = p ? p.active : true;
  $('pr-barcode').dataset.productId = p?.productId || '';
  updatePromotionFields();
  openModal('modal-promotion');
}

function updatePromotionFields() {
  const percent = $('pr-type').value === 'PERCENT';
  $('pr-percent-group').classList.toggle('hidden', !percent);
  $('pr-buy-group').classList.toggle('hidden', percent);
  const product = $('pr-scope').value === 'product';
  $('pr-barcode-group').classList.toggle('hidden', !product);
  $('pr-category-group').classList.toggle('hidden', product);
}

async function submitPromotion() {
  const id = $('pr-id').value;
  const product = $('pr-scope').value === 'product';
  const barcode = $('pr-barcode').value.trim();
  const keptProductId = $('pr-barcode').dataset.productId;
  const body = {
    name: $('pr-name').value.trim(), type: $('pr-type').value,
    discountPercent: $('pr-percent').value === '' ? null : parseFloat($('pr-percent').value),
    buyQuantity: parseInt($('pr-buy').value, 10) || null, freeQuantity: parseInt($('pr-free').value, 10) || null,
    barcode: product && barcode ? barcode : null,
    productId: product && !barcode && keptProductId ? parseInt(keptProductId, 10) : null,
    categoryId: product ? null : parseInt($('pr-category').value, 10),
    startsOn: $('pr-starts').value || null, endsOn: $('pr-ends').value || null,
    daysOfWeek: [...document.querySelectorAll('#pr-days input:checked')].map(i => i.value).join(',') || null,
    startTime: $('pr-start-time').value || null, endTime: $('pr-end-time').value || null,
    active: $('pr-active').checked
  };
  try {
    if (id) await req('PUT', `/promotions/${id}`, body);
    else await req('POST', '/promotions', body);
    closeModal('modal-promotion');
    toast(t('Promotion saved'), 'success');
    loadPromotions();
  } catch (e) { toast(e.message, 'error'); }
}

/*  Refunds  */
let refundSale = null;

function openRefund(invoiceNumber) {
  refundSale = null;
  $('refund-invoice').value = invoiceNumber || '';
  $('refund-reason').value = '';
  $('refund-method').value = 'CASH';
  $('refund-body').classList.add('hidden');
  openModal('modal-refund');
  if (invoiceNumber) findRefundSale();
  else setTimeout(() => $('refund-invoice').focus(), 50);
}

async function findRefundSale() {
  const number = $('refund-invoice').value.trim();
  if (!number) return;
  try {
    const res = await req('GET', `/sales/by-invoice/${encodeURIComponent(number)}`);
    refundSale = res.data;
    $('refund-sale-meta').innerHTML = `${t('Invoice {number}', { number: esc(refundSale.invoiceNumber) })} | ${fmtDate(refundSale.date)} | ${esc(refundSale.cashierName || '')} | <strong>${fmtLek(refundSale.totalAmount)}</strong>`;
    $('refund-lines').innerHTML = refundSale.lines.map((line, index) => `
      <tr>
        <td>${esc(line.productName)}</td>
        <td class="td-m">${fmtQty(line.soldQuantity, line.unit)} ${esc(unitLabel(line.unit))}</td>
        <td class="td-m">${fmtQty(line.refundedQuantity, line.unit)}</td>
        <td class="td-p">${fmtLek(line.unitPricePaid)}</td>
        <td><input class="input refund-qty" type="number" min="0" max="${line.refundableQuantity}"
             step="${line.unit === 'kg' ? '0.001' : '1'}" value="0" data-index="${index}"
             ${parseFloat(line.refundableQuantity) > 0 ? '' : 'disabled'} oninput="updateRefundTotal()" /></td>
      </tr>`).join('');
    $('refund-body').classList.remove('hidden');
    updateRefundTotal();
  } catch (e) {
    $('refund-body').classList.add('hidden');
    toast(e.message, 'error');
  }
}

/* Preview only: the server calculates the exact amount from what the customer paid for each line. */
function updateRefundTotal() {
  if (!refundSale) return;
  const total = [...document.querySelectorAll('.refund-qty')].reduce((sum, input) => {
    const line = refundSale.lines[input.dataset.index];
    const qty = Math.min(parseQuantity(input.value) || 0, parseFloat(line.refundableQuantity));
    return sum + (line.lineTotal * qty / line.soldQuantity);
  }, 0);
  $('refund-total').textContent = fmtLek(total);
}

async function submitRefund() {
  if (!refundSale) return;
  const lines = [...document.querySelectorAll('.refund-qty')]
    .map(input => ({ saleItemId: refundSale.lines[input.dataset.index].saleItemId, quantity: parseQuantity(input.value) || 0 }))
    .filter(line => line.quantity > 0);
  try {
    const res = await reqApproved('POST', `/sales/${refundSale.saleId}/refunds`, {
      lines, method: $('refund-method').value, reason: $('refund-reason').value.trim()
    });
    closeModal('modal-refund');
    lastReceipt = res.data.printableReceipt;
    $('receipt-txt').textContent = lastReceipt;
    openModal('modal-receipt');
    loadReceiptPrinters();
    toast(t('Refund {number} completed: {amount}', { number: res.data.refundNumber, amount: fmtLek(res.data.totalAmount) }), 'success');
    if ($('view-sales')?.classList.contains('on')) loadSales();
  } catch (e) { reportError(e, t('Refund failed')); }
}

/*  Audit log  */
const AUDIT_LABELS = {
  LOGIN: 'Signed in', LOGOUT: 'Signed out', LOGIN_FAILED: 'Failed sign-in', ACCOUNT_LOCKED: 'Account locked',
  APPROVAL_FAILED: 'Wrong manager PIN', PRICE_OVERRIDE: 'Price changed at till', CART_LINE_VOID: 'Line voided',
  CART_CLEARED: 'Cart cleared', REFUND: 'Refund', PRODUCT_CREATED: 'Product created', PRODUCT_UPDATED: 'Product changed',
  PRODUCT_DEACTIVATED: 'Product deactivated', PRODUCT_ACTIVATED: 'Product reactivated', PURCHASE_SAVED: 'Purchase invoice saved',
  EXCHANGE_RATE_UPDATED: 'Exchange rate changed', USER_CREATED: 'User created', USER_UPDATED: 'User changed',
  SHIFT_OPENED: 'Shift opened', SHIFT_CLOSED: 'Shift closed', CASH_IN: 'Cash in', CASH_OUT: 'Cash out',
  SUPPLIER_CREATED: 'Supplier created', SUPPLIER_UPDATED: 'Supplier changed', SUPPLIER_PAYMENT: 'Supplier paid',
  STOCK_ADJUSTED: 'Stock adjusted', COUNT_STARTED: 'Stock count started', COUNT_APPLIED: 'Stock count applied',
  COUNT_CANCELLED: 'Stock count cancelled', PRODUCTS_IMPORTED: 'Products imported',
  PROMOTION_CREATED: 'Promotion created', PROMOTION_UPDATED: 'Promotion changed', MANUAL_DISCOUNT: 'Manual discount',
  CUSTOMER_CREATED: 'Customer created', CUSTOMER_CREDIT_LIMIT: 'Credit limit changed', CUSTOMER_PAYMENT: 'Debt payment'
};

function auditActionLabel(action) {
  return AUDIT_LABELS[action] ? t(AUDIT_LABELS[action]) : action;
}

function initAudit() {
  const select = $('audit-action');
  const current = select.value;
  select.innerHTML = `<option value="">${t('All actions')}</option>` +
    Object.keys(AUDIT_LABELS).map(a => `<option value="${a}">${esc(auditActionLabel(a))}</option>`).join('');
  select.value = current;
  const today = new Date().toISOString().slice(0, 10);
  if (!$('audit-to').value) $('audit-to').value = today;
  if (!$('audit-from').value) $('audit-from').value = new Date(Date.now() - 6 * 86400000).toISOString().slice(0, 10);
  loadAudit();
}

async function loadAudit() {
  const tbody = $('audit-tbody');
  tbody.innerHTML = `<tr><td colspan="5" class="no-data">${t('Loading...')}</td></tr>`;
  const params = new URLSearchParams({ from: $('audit-from').value, to: $('audit-to').value });
  if ($('audit-action').value) params.set('action', $('audit-action').value);
  try {
    const res = await req('GET', `/audit?${params}`);
    const events = res.data || [];
    tbody.innerHTML = events.length ? events.map(e => `
      <tr>
        <td class="td-m">${formatDateTime(e.createdAt)}</td>
        <td>${esc(e.cashierName || '-')}</td>
        <td><span class="badge ${['LOGIN_FAILED', 'ACCOUNT_LOCKED', 'APPROVAL_FAILED'].includes(e.action) ? 'b-red' : 'b-blue'}">${esc(auditActionLabel(e.action))}</span></td>
        <td class="audit-details">${esc(e.details || '')}</td>
        <td>${esc(e.approvedByName || '')}</td>
      </tr>`).join('') : `<tr><td colspan="5" class="no-data">${t('Nothing recorded in this period.')}</td></tr>`;
  } catch (e) {
    tbody.innerHTML = `<tr><td colspan="5" class="no-data" style="color:var(--red)">${esc(e.message)}</td></tr>`;
  }
}

/*  Shifts and backups  */
let summaryShiftId = null;

async function loadOperations() {
  if (!currentUser?.cashierId) return;
  try {
    const requests = [
      req('GET', `/shifts/open?cashierId=${encodeURIComponent(currentUser.cashierId)}`),
      req('GET', '/shifts')
    ];
    if (isSuperAdmin()) requests.push(req('GET', '/backups'));
    const [openRes, shiftsRes, backupsRes] = await Promise.all(requests);
    activeShift = openRes.data || null;
    renderActiveShift(activeShift);
    renderShiftRows(shiftsRes.data || []);
    if (isSuperAdmin()) renderBackupRows(backupsRes?.data || []);
    loadCashMovements();
    // Managers follow the open shift live; cashiers only see the summary after closing (blind close).
    if (activeShift && isOperationalManager()) loadShiftSummary(activeShift.id);
    else if (activeShift) $('shift-summary').classList.add('hidden');
  } catch (e) {
    toast(e.message || t('Could not load operations'), 'error');
  }
}

async function loadShiftSummary(shiftId) {
  try {
    const res = await req('GET', `/shifts/${shiftId}/report`);
    const r = res.data;
    renderShiftSummary({ id: shiftId, totalSales: r.totalSales, cashSales: r.cashSales, cardSales: r.cardSales,
      cashIn: r.cashIn, cashOut: r.cashOut, cashRefunds: r.cashRefunds, cardRefunds: r.cardRefunds, creditSales: r.creditSales,
      expectedCash: r.expectedCash, closingCash: null, difference: null });
  } catch {}
}

function renderShiftSummary(shift) {
  summaryShiftId = shift.id;
  const show = v => (v === null || v === undefined ? '-' : fmtLek(v));
  $('shift-summary').classList.remove('hidden');
  $('shift-summary-sub').textContent = t('Shift #{id}', { id: shift.id });
  $('sum-total').textContent = show(shift.totalSales);
  $('sum-cash').textContent = show(shift.cashSales);
  $('sum-card').textContent = show(shift.cardSales);
  $('sum-in').textContent = show(shift.cashIn);
  $('sum-out').textContent = show(shift.cashOut);
  $('sum-credit').textContent = show(shift.creditSales);
  $('sum-refunds').textContent = shift.cashRefunds == null && shift.cardRefunds == null
    ? '-' : fmtLek(parseFloat(shift.cashRefunds || 0) + parseFloat(shift.cardRefunds || 0));
  $('sum-expected').textContent = show(shift.expectedCash);
  $('sum-closing').textContent = show(shift.closingCash);
  $('sum-difference').textContent = show(shift.difference);
}

async function printShiftReport() {
  if (!summaryShiftId) return;
  try {
    const res = await req('GET', `/shifts/${summaryShiftId}/report`);
    await printText(res.data.printableText);
  } catch (e) { toast(e.message || t('Could not print receipt'), 'error'); }
}

async function loadCashMovements() {
  const list = $('cash-movements-list');
  if (!activeShift) { list.innerHTML = ''; return; }
  try {
    const res = await req('GET', `/shifts/${activeShift.id}/cash-movements`);
    list.innerHTML = (res.data || []).map(m => `
      <div class="balance-line">
        <span>${formatTime(m.createdAt)} · ${esc(m.type === 'IN' ? t('Cash in') : t('Cash out'))} · ${esc(m.reason)}</span>
        <strong>${m.type === 'IN' ? '+' : '-'}${fmtLek(m.amount)}</strong>
      </div>`).join('');
  } catch { list.innerHTML = ''; }
}

async function addCashMovement() {
  if (!activeShift?.id) { toast(t('Open a shift first'), 'error'); return; }
  const body = {
    type: $('cash-type').value,
    amount: parseFloat($('cash-amount').value || '0'),
    reason: $('cash-reason').value.trim()
  };
  try {
    await req('POST', `/shifts/${activeShift.id}/cash-movements`, body);
    $('cash-amount').value = '';
    $('cash-reason').value = '';
    toast(t('Cash movement saved'), 'success');
    loadOperations();
  } catch (e) { toast(e.message || t('Save failed'), 'error'); }
}

/* Z report: everything sold and received on one day, on all tills. */
let zReportText = '';

async function loadZReport() {
  const date = $('z-date').value || isoDay(new Date());
  try {
    const res = await req('GET', `/reports/daily?date=${encodeURIComponent(date)}`);
    zReportText = res.data.printableText;
    $('z-report-text').textContent = zReportText;
    $('z-report-text').classList.remove('hidden');
  } catch (e) { toast(e.message || t('Could not load reports: '), 'error'); }
}

async function printZReport() {
  if (!zReportText) await loadZReport();
  if (zReportText) await printText(zReportText);
}

async function openShift() {
  const openingCash = parseFloat($('shift-opening-cash')?.value || '0');
  if (!currentUser?.cashierId) {
    toast(t('Login again before opening a shift'), 'error');
    return;
  }
  try {
    const res = await req('POST', '/shifts/open', { cashierId: currentUser.cashierId, openingCash });
    activeShift = res.data;
    toast(t('Shift opened'), 'success');
    loadOperations();
  } catch (e) {
    toast(e.message || t('Could not open shift'), 'error');
  }
}

async function closeShift() {
  if (!activeShift?.id) {
    toast(t('No open shift to close'), 'error');
    return;
  }
  const closingCash = parseFloat($('shift-closing-cash')?.value || '0');
  try {
    const res = await req('POST', `/shifts/${activeShift.id}/close`, { closingCash });
    activeShift = null;
    renderShiftSummary(res.data);
    toast(t('Shift closed'), 'success');
    loadOperations();
  } catch (e) {
    toast(e.message || t('Could not close shift'), 'error');
  }
}

async function runBackup() {
  try {
    const res = await req('POST', '/backups/run');
    toast(res.data?.status === 'SUCCESS' ? t('Backup created') : t('Backup failed'), res.data?.status === 'SUCCESS' ? 'success' : 'error');
    loadOperations();
  } catch (e) {
    toast(e.message || t('Could not run backup'), 'error');
  }
}

function renderActiveShift(shift) {
  $('shift-status').textContent = shift ? statusLabel('OPEN') : t('No open shift');
  $('shift-opened').textContent = shift ? fmtDate(shift.openedAt) : '-';
  $('shift-opening').textContent = fmtLek(shift?.openingCash || 0);
}

function renderShiftRows(shifts) {
  const tbody = $('shifts-tbody');
  if (!tbody) return;
  if (!shifts.length) {
    tbody.innerHTML = `<tr><td colspan="12" class="no-data">${t('No shifts yet.')}</td></tr>`;
    return;
  }
  tbody.innerHTML = shifts.map(shift => `
    <tr>
      <td>#${shift.id}</td>
      <td>${esc(shift.cashier?.fullName || t('Cashier'))}</td>
      <td><span class="badge ${shift.status === 'OPEN' ? 'b-green' : 'b-blue'}">${esc(statusLabel(shift.status))}</span></td>
      <td>${fmtDate(shift.openedAt)}</td>
      <td>${shift.closedAt ? fmtDate(shift.closedAt) : '-'}</td>
      <td>${fmtLek(shift.openingCash || 0)}</td>
      <td>${shift.status === 'OPEN' ? '-' : fmtLek(shift.totalSales || 0)}</td>
      <td>${shift.cashSales == null ? '-' : fmtLek(shift.cashSales)}</td>
      <td>${shift.cardSales == null ? '-' : fmtLek(shift.cardSales)}</td>
      <td>${shift.expectedCash == null ? '-' : fmtLek(shift.expectedCash)}</td>
      <td>${shift.closingCash == null ? '-' : fmtLek(shift.closingCash)}</td>
      <td>${shift.status === 'OPEN' ? '-' : fmtLek(shift.difference || 0)}</td>
    </tr>
  `).join('');
}

function renderBackupRows(backups) {
  const tbody = $('backups-tbody');
  if (!tbody) return;
  if (!backups.length) {
    tbody.innerHTML = `<tr><td colspan="5" class="no-data">${t('No backups yet.')}</td></tr>`;
    $('backup-status').textContent = t('No backup yet');
    return;
  }
  const latest = backups[0];
  $('backup-status').textContent = `${statusLabel(latest.status)} - ${fmtDate(latest.createdAt)}`;
  tbody.innerHTML = backups.map(backup => `
    <tr>
      <td>#${backup.id}</td>
      <td>${fmtDate(backup.createdAt)}</td>
      <td><span class="badge ${backup.status === 'SUCCESS' ? 'b-green' : 'b-red'}">${esc(statusLabel(backup.status))}</span></td>
      <td class="td-m">${esc(backup.filePath || '')}</td>
      <td>${esc(backup.message || '')}</td>
    </tr>
  `).join('');
}

/*  XSS escape  */
function esc(str) {
  return String(str ?? '').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&#39;');
}

/*  Keyboard shortcuts  */
document.addEventListener('keydown', e => {
  if (e.key === 'Escape') {
    document.querySelectorAll('.overlay:not(.hidden)').forEach(m => m.classList.add('hidden'));
  }
});

$('pin-input').addEventListener('keydown', e => {
  if (e.key === 'Enter') answerPin($('pin-input').value);
  if (e.key === 'Escape') answerPin(null);
});

document.querySelectorAll('.pay-input').forEach(input => {
  input.addEventListener('keydown', e => { if (e.key === 'Enter') confirmPayment(); });
});

/*  Close modal on backdrop click  */
document.querySelectorAll('.overlay').forEach(overlay => {
  overlay.addEventListener('click', e => {
    if (e.target === overlay) overlay.classList.add('hidden');
  });
});

/*  Enter key on auth inputs  */
['l-user','l-pass'].forEach(id => {
  $(id)?.addEventListener('keydown', e => { if (e.key === 'Enter') doLogin(); });
});
['r-name','r-user','r-pass'].forEach(id => {
  $(id)?.addEventListener('keydown', e => { if (e.key === 'Enter') doRegister(); });
});

/*  Init  */
/*  Language  */
function onLanguageChanged() {
  if (!currentUser) return;
  $('user-role').textContent = roleLabel(currentUser.role);
  updateHomeClock();
  applyRolePermissions();
  const activeView = document.querySelector('.view.on')?.id?.replace('view-', '') || 'home';
  gotoView(activeView);
  refreshCurrencyDisplay();
}

(async function init() {
  applyLanguage();
  await checkInitialSetup();
  try {
    // The session cookie is invisible to this script: ask the server who is signed in on this till.
    const res = await req('GET', '/auth/me');
    bootUser(res.data);
  } catch {
    clearLocalSession();
  }
})();

