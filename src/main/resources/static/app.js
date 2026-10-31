'use strict';

const API = window.location.origin;
let currentUser  = null;
let cartItems    = [];
let allProdTable = [];
let allCategories = [];
let purchaseItems = [];
let lastReceipt  = '';
let keypadBuffer = '';
let shopSettings = null;
let activeTab = 1;
let selectedLine = -1;
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
  // The till has three tabs (one customer each): cart requests say which one.
  if (/^\/sales\/(cart|checkout|carts\/\d+\/resume)/.test(path)) opts.headers['X-Cart-Slot'] = String(activeTab);
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
  cartSummary = null;
  activeTab = 1;
  selectedLine = -1;
  activeShift = null;
  sendToDisplay();
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
    showShopName(res.data?.shopName);
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
  loadShopSettings();
  loadSystemInfo();
  refreshTillInfo();
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
  if (['products', 'purchases', 'reports', 'suppliers', 'inventory', 'promotions', 'kartela', 'journal', 'expenses', 'accounting'].includes(view) && !isOperationalManager()) {
    toast(t('Super Cashier or Super Admin access is required'), 'error');
    view = 'home';
  }
  if (['users', 'audit', 'settings'].includes(view) && !isSuperAdmin()) {
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
  if (view === 'settings') loadSettingsForm();
  if (view === 'kartela') initKartela();
  if (view === 'journal') initJournal();
  if (view === 'expenses') initExpenses();
  if (view === 'accounting') initAccounting();
  if (view === 'pos') { refreshTillInfo(); setTimeout(focusSearch, 50); }
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

/*  Till search  */
/*
 * The search field takes a barcode (scanner or keyboard, then Enter), "3*barcode" for three pieces, or part of
 * a product name: matches appear in a list; arrows choose, Enter adds.
 */
let searchResults = [];
let searchIndex = -1;
let _searchTmr;

function onSearch(val) {
  clearTimeout(_searchTmr);
  const text = val.trim();
  if (!text || /^(\d+([.,]\d+)?\*)?\d*$/.test(text)) { hideSearchResults(); return; }
  _searchTmr = setTimeout(async () => {
    try {
      const res = await req('GET', `/products/search?query=${encodeURIComponent(text)}`);
      if ($('pos-search').value.trim() !== text) return;
      searchResults = (Array.isArray(res.data) ? res.data : []).filter(p => p.active !== false).slice(0, 12);
      searchIndex = searchResults.length ? 0 : -1;
      renderSearchResults();
    } catch (e) { toast(e.message, 'error'); }
  }, 250);
}

function renderSearchResults() {
  const box = $('search-results');
  if (!searchResults.length) {
    box.innerHTML = `<div class="sr-empty">${t('No products found')}</div>`;
  } else {
    box.innerHTML = searchResults.map((p, i) => `
      <div class="sr-item ${i === searchIndex ? 'on' : ''}" onmousedown="event.preventDefault(); pickSearchResult(${i})">
        <span class="sr-main"><span class="sr-name">${esc(p.name)}</span><span class="sr-code">${esc(p.barcode || '')}</span></span>
        <span class="sr-stock ${parseFloat(p.stock) > 0 ? '' : 'out'}">${fmtQty(p.stock, p.unit)} ${esc(unitLabel(p.unit))}</span>
        <span class="sr-price">${fmt(p.price)}</span>
      </div>`).join('');
  }
  box.classList.remove('hidden');
}

function hideSearchResults() {
  searchResults = [];
  searchIndex = -1;
  $('search-results')?.classList.add('hidden');
}

async function pickSearchResult(index) {
  const product = searchResults[index];
  if (!product) return;
  const quantity = pendingQuantity();
  hideSearchResults();
  $('pos-search').value = '';
  await addToCart({ productId: product.id, quantity });
  focusSearch();
}

/* "3*" typed before a name search sells that many. */
function pendingQuantity() {
  const match = $('pos-search').value.trim().match(/^(\d+(?:[.,]\d+)?)\*/);
  return match ? parseQuantity(match[1]) : 1;
}

function onSearchKey(event) {
  const open = !$('search-results').classList.contains('hidden') && searchResults.length;
  if (open && (event.key === 'ArrowDown' || event.key === 'ArrowUp')) {
    event.preventDefault();
    event.stopPropagation();
    searchIndex = (searchIndex + (event.key === 'ArrowDown' ? 1 : -1) + searchResults.length) % searchResults.length;
    renderSearchResults();
    return;
  }
  if (event.key === 'Escape' && open) {
    event.stopPropagation();
    hideSearchResults();
    return;
  }
  if (event.key !== 'Enter') return;
  event.preventDefault();
  clearTimeout(_searchTmr);
  const text = event.target.value.trim();
  const code = text.match(/^(?:(\d+(?:[.,]\d+)?)\*)?(\d{3,})$/);
  if (code) {
    hideSearchResults();
    event.target.value = '';
    addByBarcode(code[2], code[1] ? parseQuantity(code[1]) : 1);
  } else if (open && searchIndex >= 0) {
    pickSearchResult(searchIndex);
  }
}

function focusSearch() {
  if ($('view-pos').classList.contains('on')) $('pos-search')?.focus();
}

/* Invoice number, cashier and shift shown at the top of the till. */
async function refreshTillInfo() {
  if ($('till-cashier')) $('till-cashier').textContent = currentUser?.fullName || '-';
  try {
    const res = await req('GET', '/shifts/open');
    $('till-shift').textContent = res.data
      ? t('Open since {time}', { time: formatTime(new Date(res.data.openedAt)) })
      : t('No open shift');
    $('till-shift').classList.toggle('t-red', !res.data);
  } catch {
    $('till-shift').textContent = '-';
  }
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
  $('cart-count').textContent = cartItems.length;
  loadTillTabs();
  if (selectedLine >= cartItems.length) selectedLine = cartItems.length - 1;
  renderInvoiceRows();
  const hasItems = cartItems.length > 0;
  $('btn-checkout').disabled = !hasItems;
  $('btn-clear').disabled = !hasItems;
  if (!hasItems) $('cart-subtotal').textContent = fmt(0);
  sendToDisplay();
}

/*  Till tabs: three customers at once; switching never touches the other tabs  */
let _tabsTmr;
function loadTillTabs() {
  clearTimeout(_tabsTmr);
  _tabsTmr = setTimeout(async () => {
    try {
      const res = await req('GET', '/sales/cart/tabs');
      renderTillTabs(res.data || []);
    } catch {}
  }, 150);
}

function renderTillTabs(tabs) {
  const bar = $('till-tabs');
  if (!bar) return;
  bar.innerHTML = tabs.map(tab => {
    const busy = tab.lines > 0;
    return `<button class="till-tab ${tab.tab === activeTab ? 'on' : ''} ${busy ? 'busy' : ''}" onclick="switchTillTab(${tab.tab})">
      <strong>${t('Customer {n}', { n: tab.tab })}</strong>
      <span>${busy ? `${linesText(tab.lines)} · ${fmt(tab.total)}` : t('empty')}</span>
      ${tab.customer ? `<small>${esc(tab.customer)}</small>` : ''}
      <kbd>Alt+${tab.tab}</kbd>
    </button>`;
  }).join('');
}

async function switchTillTab(tab) {
  if (tab === activeTab) { focusSearch(); return; }
  activeTab = tab;
  selectedLine = -1;
  cartSummary = null;
  hideSearchResults();
  $('pos-search').value = '';
  $('cart-breakdown').innerHTML = '';
  $('cart-customer').classList.add('hidden');
  await refreshCart();
  focusSearch();
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
  sendToDisplay();
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
  if ($('view-products')?.classList.contains('on')) renderProdsTable();
  if (salesPage) { renderSalesTable(); renderStats(); }
  if ($('view-reports')?.classList.contains('on')) applyReportFilters();
}

/* Adds to the cart; the new or changed line becomes the selected one. */
async function addToCart(body) {
  try {
    const res = await req('POST', '/sales/cart', body);
    cartItems = Array.isArray(res.data) ? res.data : [];
    const index = body.productId ? cartItems.findIndex(i => i.productId === body.productId && !i.packageId) : cartItems.length - 1;
    selectedLine = index >= 0 ? index : cartItems.length - 1;
    renderCart();
    fetchSubtotal();
    return true;
  } catch (e) {
    toast(e.message || t('Could not add item'), 'error');
    return false;
  }
}

async function addByBarcode(barcode, quantity = 1) {
  if (await addToCart({ barcode, quantity })) {
    const index = cartItems.findIndex(i => i.barcode === barcode);
    if (index >= 0) { selectedLine = index; renderInvoiceRows(); }
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
      ? `<input class="input pos-edit-cell" type="number" min="0.01" step="0.01" value="${Number(item.price || 0).toFixed(2)}" onchange="updateCartInline(${item.lineId}, 'price', this.value)" />`
      : `<span class="td-p">${fmt(item.price)}</span>`;
    return `
    <tr class="${index === selectedLine ? 'selected' : ''}" onclick="selectLine(${index})">
      <td class="td-m">${String(index + 1).padStart(4, '0')}</td>
      <td class="invoice-code-cell">${esc(item.barcode || item.productId)}</td>
      <td><strong>${esc(item.productName)}</strong>${item.packageName ? `<div class="wh-sub">${esc(item.packageName)} · ${t('{count} pieces', { count: item.piecesPerUnit })}</div>` : ''}</td>
      <td class="td-p">${fmt(item.unitPriceWithoutTax)}</td>
      <td class="td-m">${fmtTax(item.taxRate)}</td>
      <td class="td-p">${fmt(item.taxAmount)}</td>
      <td>${priceCell}</td>
      <td><input class="input pos-edit-cell pos-qty-cell" type="number" min="0" step="${item.unit === 'kg' ? '0.001' : '1'}" value="${fmtQty(item.quantity, item.unit)}" onchange="updateCartInline(${item.lineId}, 'quantity', this.value)" /></td>
      <td class="td-p">${fmt(item.lineTotal)}</td>
      <td class="td-m">${esc(item.packageName ? t('box') : unitLabel(item.unit))}</td>
      <td class="td-a">
        <button class="btn btn-secondary btn-sm btn-icon" title="${t('Decrease')}" onclick="stepQty(${item.lineId},-1)">-</button>
        <button class="btn btn-secondary btn-sm btn-icon" title="${t('Increase')}" onclick="stepQty(${item.lineId},1)">+</button>
        <button class="btn btn-danger btn-sm btn-icon" title="${t('Remove')}" onclick="removeItem(${item.lineId})">x</button>
      </td>
    </tr>
  `;
  }).join('');
}

function selectLine(index) {
  if (index < 0 || index >= cartItems.length) return;
  selectedLine = index;
  renderInvoiceRows();
  document.querySelector('#invoice-rows tr.selected')?.scrollIntoView({ block: 'nearest' });
}

function selectedItem() {
  return cartItems[selectedLine] || cartItems[cartItems.length - 1];
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
  const item = selectedItem();
  const value = prompt(t('Quantity for ') + item.productName, fmtQty(item.quantity, item.unit));
  if (value === null) return;
  const qty = parseQuantity(value);
  if (Number.isNaN(qty) || qty < 0) { toast(t('Enter a valid quantity'), 'error'); return; }
  setQuantity(item.lineId, qty);
}

/* The +/- buttons: one piece (or box), or 100 g for weighed products. */
function stepQty(lineId, direction) {
  const item = cartItems.find(i => i.lineId === lineId);
  if (!item) return;
  const step = item.unit === 'kg' ? 0.1 : 1;
  setQuantity(lineId, parseFloat(item.quantity) + direction * step);
}

async function setQuantity(lineId, quantity) {
  const rounded = Math.max(0, Math.round(quantity * 1000) / 1000);
  try {
    const res = await reqApproved('PUT', `/sales/cart/lines/${lineId}`, { quantity: rounded });
    cartItems = Array.isArray(res.data) ? res.data : [];
    renderCart();
    fetchSubtotal();
  } catch (e) {
    reportError(e, t('Could not update qty'));
    refreshCart();
  }
}

async function updateCartInline(lineId, field, value) {
  const item = cartItems.find(cartItem => cartItem.lineId === lineId);
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
    const res = await reqApproved('PUT', `/sales/cart/lines/${lineId}`, body);
    cartItems = Array.isArray(res.data) ? res.data : [];
    renderCart();
    fetchSubtotal();
    toast(t('Invoice line updated'), 'success');
  } catch (e) {
    reportError(e, t('Could not update invoice line'));
    refreshCart();
  }
}

async function removeItem(lineId) {
  await setQuantity(lineId, 0);
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
    sendToDisplay({ type: 'paying', total: paymentTotal });
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

/* A banknote: the cash field gets that amount (pressed twice, twice the amount). */
function payNote(amount) {
  const field = $('pay-cash-lek');
  const current = parseFloat(String(field.value || '').replace(',', '.')) || 0;
  field.value = (current >= paymentTotal || current === 0 ? amount : current + amount).toFixed(2);
  updatePaymentSummary();
  field.focus();
}

/* F5 in the payment window: exact cash if nothing was entered, then finish the sale. */
function paymentShortcut() {
  if (!readPayments().length) payExactCash();
  confirmPayment();
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
    closeModal('modal-payment');
    sendToDisplay({ type: 'paid', total: data.totalAmount, paid: data.paidAmount, change: data.changeAmount });
    cartItems = [];
    selectedLine = -1;
    cartSummary = null;
    renderInvoiceRows();
    $('cart-count').textContent = 0;
    $('btn-checkout').disabled = true;
    $('btn-clear').disabled = true;
    $('cart-subtotal').textContent = fmt(0);
    $('cart-breakdown').innerHTML = '';
    $('cart-customer').classList.add('hidden');
    loadTillTabs();
    setNextInvoiceNumber();
    showReceipt(data.printableReceipt);
    if (shopSettings?.autoPrint) printReceipt();
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
    if ($('invoice-no')) $('invoice-no').textContent = res.data || '-';
  } catch {
    if ($('invoice-no')) $('invoice-no').textContent = '-';
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
    const res = await req('GET', `/products/scan/${encodeURIComponent(barcode)}`);
    addPurchaseProduct(res.data.product, res.data.packageInfo);
    toast(t('Product added to purchase invoice'), 'success');
  } catch (e) {
    toast(t('New product: register it, then it is added to this invoice.'), 'info');
    openProdModal(null, barcode, true);
  }
}

/* A line of single pieces, or of boxes (box given): then quantity counts boxes and the purchase price is per box. */
function addPurchaseProduct(product, box = null) {
  const existing = purchaseItems.find(item => item.productId === product.id && (item.packageId || null) === (box?.id || null));
  if (existing) {
    existing.quantity += 1;
  } else {
    purchaseItems.push({
      productId: product.id,
      packageId: box?.id || null,
      packageName: box?.name || null,
      pieces: box?.pieces || null,
      barcode: box?.barcode || product.barcode,
      name: product.name,
      category: product.category?.name || 'General',
      unit: product.unit || 'pcs',
      quantity: 1,
      purchasePrice: parseFloat(product.purchasePrice || 0) * (box?.pieces || 1),
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
      <td><strong>${esc(item.name)}</strong>${item.packageId ? `<div class="wh-sub">${esc(item.packageName)} · ${t('{count} pieces', { count: item.pieces })} · ${t('price per box')}</div>` : ''}</td>
      <td><span class="badge b-blue">${esc(item.category)}</span></td>
      <td>${item.packageId ? `<span class="badge b-muted">${t('box')}</span>` : `
        <select class="input purchase-cell" onchange="updatePurchaseItem(${index}, 'unit', this.value)">
          <option value="pcs" ${item.unit === 'pcs' ? 'selected' : ''}>pcs</option>
          <option value="kg" ${item.unit === 'kg' ? 'selected' : ''}>kg</option>
        </select>`}
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
  const itemsInInvoice = itemCount(purchaseItems.map(item => ({ ...item, quantity: parseQuantity(item.quantity) * (item.pieces || 1) })));
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
      packageId: item.packageId || null,
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
    loadProdsTable();
  } catch (e) {
    toast(e.message || t('Could not save purchase invoice'), 'error');
  }
}

/* Shows a receipt (or report) text with the print button; the chosen printer is the shop's receipt printer. */
function showReceipt(text) {
  lastReceipt = text || '';
  $('receipt-txt').textContent = lastReceipt;
  openModal('modal-receipt');
  loadReceiptPrinters();
}

function closeReceipt() {
  closeModal('modal-receipt');
  focusSearch();
}

async function printReceipt() {
  await printText(lastReceipt);
}

/* Sends any text (receipt, X or Z report) to the chosen receipt printer. */
async function printText(text) {
  const printerName = $('receipt-printer')?.value || shopSettings?.receiptPrinter || '';
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
    const wanted = current || shopSettings?.receiptPrinter || '';
    if ([...select.options].some(option => option.value === wanted)) {
      select.value = wanted;
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

/*  Magazina (warehouse): products, stock, prices, boxes and shelf labels  */
let warehouseOverview = null;
let warehouseFilter = 'all';
let warehouseChangedToday = new Set();
const warehouseSelected = new Set();
const DEAD_STOCK_DAYS = 60;

async function loadProdsTable() {
  $('prod-tbody').innerHTML = `<tr><td colspan="9" class="no-data">${t('Loading...')}</td></tr>`;
  try {
    const [overview, products, changed] = await Promise.all([
      req('GET', '/warehouse/overview'),
      req('GET', '/warehouse/products'),
      req('GET', '/warehouse/labels/changed-today')
    ]);
    warehouseOverview = overview.data;
    allProdTable = Array.isArray(products.data) ? products.data : [];
    warehouseChangedToday = new Set(changed.data || []);
    [...warehouseSelected].forEach(id => { if (!allProdTable.some(p => p.id === id)) warehouseSelected.delete(id); });
    renderWarehouseKpis();
    fillWarehouseCategories();
    renderProdsTable();
    if (productCard && $('view-kartela').classList.contains('on')) loadKartela(productCard.product.id, true);
  } catch (e) {
    $('prod-tbody').innerHTML = `<tr><td colspan="9" class="no-data" style="color:var(--red)">${t('Error')}: ${esc(e.message)}</td></tr>`;
  }
}

function renderWarehouseKpis() {
  const o = warehouseOverview;
  if (!o) return;
  const tile = (filter, label, value, tone = '') => `
    <button class="wh-kpi ${tone} ${filter && filter === warehouseFilter ? 'on' : ''}" ${filter ? `onclick="setWarehouseFilter('${filter}')"` : 'disabled'}>
      <span>${label}</span><strong>${value}</strong>
    </button>`;
  $('wh-kpis').innerHTML =
    tile('all', t('Active products'), o.activeProducts) +
    tile(null, t('Stock value (cost)'), fmtLek(o.stockValueAtCost)) +
    tile(null, t('Stock value (selling price)'), fmtLek(o.stockValueAtPrice)) +
    tile('low', t('Below minimum stock'), o.lowStock, o.lowStock ? 'warn' : '') +
    tile('out', t('Out of stock'), o.outOfStock, o.outOfStock ? 'bad' : '') +
    tile('dead', t('No sales in 60 days'), o.deadStock) +
    tile('changed', t('Price changed today'), o.pricesChangedToday, o.pricesChangedToday ? 'warn' : '');
}

function setWarehouseFilter(filter) {
  warehouseFilter = filter;
  $('wh-stock-filter').value = filter;
  renderWarehouseKpis();
  renderProdsTable();
}

function fillWarehouseCategories() {
  const select = $('wh-category');
  const current = select.value;
  const names = [...new Set(allProdTable.map(p => p.category).filter(Boolean))].sort((a, b) => a.localeCompare(b));
  select.innerHTML = `<option value="">${t('All categories')}</option>` + names.map(n => `<option>${esc(n)}</option>`).join('');
  select.value = names.includes(current) ? current : '';
}

/* Whole boxes in a stock of pieces: "= 3 boxes" next to the stock. */
function boxesText(p) {
  if (!p.biggestPackagePieces || parseFloat(p.stock) < p.biggestPackagePieces) return '';
  return '= ' + t('{count} x {box}', { count: Math.floor(parseFloat(p.stock) / p.biggestPackagePieces), box: p.biggestPackage });
}

function warehouseRows() {
  const q = ($('wh-search')?.value || '').trim().toLowerCase();
  const category = $('wh-category')?.value || '';
  const deadBefore = Date.now() - DEAD_STOCK_DAYS * 86400000;
  const rows = allProdTable.filter(p => {
    if (warehouseFilter === 'inactive') { if (p.active) return false; }
    else if (!p.active) return false;
    const stock = parseFloat(p.stock);
    if (warehouseFilter === 'low' && !(p.minStock != null && stock <= parseFloat(p.minStock))) return false;
    if (warehouseFilter === 'out' && stock > 0) return false;
    if (warehouseFilter === 'dead' && !(stock > 0 && (!p.lastSaleAt || new Date(p.lastSaleAt).getTime() < deadBefore))) return false;
    if (warehouseFilter === 'changed' && !warehouseChangedToday.has(p.id)) return false;
    if (category && p.category !== category) return false;
    return !q || p.name.toLowerCase().includes(q) || (p.barcode || '').includes(q);
  });
  const by = {
    name: (a, b) => a.name.localeCompare(b.name),
    stock: (a, b) => parseFloat(a.stock) - parseFloat(b.stock),
    value: (a, b) => parseFloat(b.stockValue) - parseFloat(a.stockValue),
    margin: (a, b) => parseFloat(a.marginPercent) - parseFloat(b.marginPercent),
    sold: (a, b) => parseFloat(b.soldLast30Days) - parseFloat(a.soldLast30Days)
  }[$('wh-sort')?.value || 'name'];
  return rows.sort(by);
}

function renderProdsTable() {
  const tbody = $('prod-tbody');
  const rows = warehouseRows();
  $('tb-count').textContent = t('{shown} of {total} products', { shown: rows.length, total: allProdTable.length });
  if (!rows.length) {
    tbody.innerHTML = `<tr><td colspan="9" class="no-data">${t('No products found')}</td></tr>`;
  } else {
    tbody.innerHTML = rows.map(p => {
      const stock = parseFloat(p.stock);
      const tone = stock <= 0 ? 'b-red' : (p.minStock != null && stock <= parseFloat(p.minStock)) ? 'b-amber' : 'b-green';
      const margin = parseFloat(p.marginPercent);
      return `<tr class="wh-row ${p.active ? '' : 'row-inactive'}" onclick="openProductCard(${p.id})">
        <td class="wh-check" onclick="event.stopPropagation()"><input type="checkbox" ${warehouseSelected.has(p.id) ? 'checked' : ''} onchange="toggleWarehouseSelect(${p.id}, this.checked)" /></td>
        <td><strong>${esc(p.name)}</strong>${p.active ? '' : ` <span class="badge b-red">${t('Inactive')}</span>`}
          <div class="wh-sub">${esc(p.barcode)}${p.packages ? ` · <span class="badge b-muted">${t('{count} boxes', { count: p.packages })}</span>` : ''}</div></td>
        <td><span class="badge b-blue">${esc(p.category || t('None'))}</span></td>
        <td><span class="badge ${tone}">${fmtQty(p.stock, p.unit)} ${esc(unitLabel(p.unit))}</span><div class="wh-sub">${esc(boxesText(p))}</div></td>
        <td class="td-p">${fmt(p.price)}</td>
        <td class="td-p">${fmt(p.purchasePrice)}</td>
        <td class="${margin < 10 ? 't-red' : ''}">${margin.toFixed(1)}%</td>
        <td class="td-p">${fmtLek(p.stockValue)}</td>
        <td class="td-m">${fmtQty(p.soldLast30Days, p.unit)}</td>
      </tr>`;
    }).join('');
  }
  $('wh-select-all').checked = rows.length > 0 && rows.every(p => warehouseSelected.has(p.id));
  renderWarehouseBulk();
  renderWarehouseTotals(rows);
}

/*
 * Totals of the products shown. The profit % is the margin of all their stock together: each product's margin
 * counts as much as its stock is worth at selling price (without VAT), so it says roughly how much of the money
 * would be profit if what is on the shelves were sold at today's prices.
 */
function renderWarehouseTotals(rows) {
  let saleValue = 0;
  let profit = 0;
  let costValue = 0;
  rows.forEach(p => {
    const stock = Math.max(0, parseFloat(p.stock));
    const priceExVat = parseFloat(p.price) / (1 + parseFloat(p.taxRate) / 100);
    const value = stock * priceExVat;
    saleValue += value;
    profit += value * parseFloat(p.marginPercent) / 100;
    costValue += parseFloat(p.stockValue);
  });
  const percent = saleValue > 0 ? profit / saleValue * 100 : 0;
  $('wh-foot').innerHTML = rows.length ? `<tr class="wh-total">
    <td></td>
    <td colspan="5"><strong>${t('Total of the {count} products shown', { count: rows.length })}</strong>
      <div class="wh-sub">${t('Profit if this stock is sold at today\'s prices: about {amount}', { amount: fmtLek(profit) })}</div></td>
    <td class="${percent < 10 ? 't-red' : ''}"><strong>~${percent.toFixed(1)}%</strong></td>
    <td class="td-p"><strong>${fmtLek(costValue)}</strong></td>
    <td></td>
  </tr>` : '';
}

function toggleWarehouseSelect(id, on) {
  if (on) warehouseSelected.add(id); else warehouseSelected.delete(id);
  renderWarehouseBulk();
}

function selectAllWarehouse(on) {
  warehouseRows().forEach(p => { if (on) warehouseSelected.add(p.id); else warehouseSelected.delete(p.id); });
  renderProdsTable();
}

function clearWarehouseSelection() {
  warehouseSelected.clear();
  renderProdsTable();
}

function renderWarehouseBulk() {
  $('wh-bulk').classList.toggle('hidden', warehouseSelected.size === 0);
  $('wh-bulk-count').textContent = t('{count} selected', { count: warehouseSelected.size });
}

async function openProdModalById(productId) {
  try {
    const res = await req('GET', `/products/${productId}`);
    openProdModal(res.data);
  } catch (e) {
    toast(e.message || t('Could not load product'), 'error');
  }
}

/*  Kartela e Artikullit: one product on a full page  */
const MOVEMENT_LABELS = { SALE: 'Sale', REFUND: 'Refund', PURCHASE: 'Purchase', ADJUSTMENT: 'Adjustment' };
const PRICE_SOURCES = { EDIT: 'Edited', PURCHASE: 'Purchase invoice', BULK: 'Bulk change', IMPORT: 'File import' };
const PROMOTION_TYPES = { PERCENT: 'Percentage off', BUY_X_GET_Y: 'Buy X get Y free' };
const WEEKDAYS_SHORT = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const KARTELA_SECTIONS = [
  ['kr-sales', 'Sales'], ['kr-stock', 'Stock'], ['kr-details', 'Details'], ['kr-boxes', 'Boxes'], ['kr-prices', 'Prices'],
  ['kr-moves', 'Stock movements'], ['kr-suppliers', 'Suppliers'], ['kr-customers', 'Customers'], ['kr-promos', 'Promotions'],
  ['kr-history', 'History']
];
let productCard = null;
let kartelaId = null;
let kartelaPeriod = 90;
let kartelaAnalysis = null;
let kartelaMoves = [];

/* Magazina rows, boxes and labels open the Kartela. */
function openProductCard(productId) {
  return openKartela(productId);
}

function openKartela(productId) {
  kartelaId = productId;
  if ($('view-kartela').classList.contains('on')) return loadKartela(productId);
  gotoView('kartela');
}

function initKartela() {
  if (kartelaId) loadKartela(kartelaId);
  else renderKartelaEmpty();
  setTimeout(() => $('kr-search')?.focus(), 50);
}

async function loadKartela(productId, keepScroll = false) {
  kartelaId = productId;
  const page = $('kr-page');
  const scroll = page.scrollTop;
  if (!productCard || productCard.product.id !== productId) {
    page.innerHTML = `<div class="kr-loading">${t('Loading...')}</div>`;
  }
  try {
    const [card, analysis, moves] = await Promise.all([
      req('GET', `/warehouse/products/${productId}`),
      req('GET', `/warehouse/products/${productId}/analysis?days=${kartelaPeriod}`),
      req('GET', `/warehouse/products/${productId}/movements`)
    ]);
    productCard = card.data;
    kartelaAnalysis = analysis.data;
    kartelaMoves = moves.data || [];
    rememberKartela(productCard.product);
    renderKartela();
    if (keepScroll) page.scrollTop = scroll;
    updateKartelaNav();
  } catch (e) {
    page.innerHTML = `<div class="kr-empty"><strong>${esc(e.message)}</strong></div>`;
  }
}

/* The last products opened, to come back to them quickly (this computer only). */
function rememberKartela(product) {
  try {
    const recent = JSON.parse(localStorage.getItem('kartela-recent') || '[]').filter(r => r.id !== product.id);
    recent.unshift({ id: product.id, name: product.name });
    localStorage.setItem('kartela-recent', JSON.stringify(recent.slice(0, 8)));
  } catch {}
}

function renderKartelaEmpty() {
  let recent = [];
  try { recent = JSON.parse(localStorage.getItem('kartela-recent') || '[]'); } catch {}
  $('kr-page').innerHTML = `<div class="kr-empty">
    <div class="kr-empty-icon">
      <svg width="42" height="42" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><rect x="3" y="4" width="18" height="16" rx="2"/><path d="M7 9h6"/><path d="M7 13h10"/><path d="M7 17h4"/></svg>
    </div>
    <strong>${t('Choose a product')}</strong>
    <span>${t('Search by name above, scan its barcode, or open it from Magazina.')}</span>
    ${recent.length ? `<div class="kr-recent"><small>${t('Opened recently')}</small>
      ${recent.map(r => `<button class="btn btn-secondary btn-sm" onclick="openKartela(${r.id})">${esc(r.name)}</button>`).join('')}</div>` : ''}
  </div>`;
  updateKartelaNav();
}

/* Previous / next follow the Magazina list (its filters and order). */
function kartelaList() {
  const rows = allProdTable.length ? warehouseRows() : [];
  return rows.length ? rows : allProdTable;
}

async function stepKartela(direction) {
  if (!allProdTable.length) {
    try { allProdTable = (await req('GET', '/warehouse/products')).data || []; } catch { return; }
  }
  const list = kartelaList();
  if (!list.length) return;
  const index = list.findIndex(p => p.id === kartelaId);
  const next = list[(index + direction + list.length) % list.length];
  if (next) loadKartela(next.id);
}

function updateKartelaNav() {
  const disabled = !kartelaId;
  if ($('kr-prev')) $('kr-prev').disabled = disabled;
  if ($('kr-next')) $('kr-next').disabled = disabled;
}

let _krSearchTmr;
let kartelaResults = [];
let kartelaResultIndex = -1;

function onKartelaSearch(value) {
  clearTimeout(_krSearchTmr);
  const text = value.trim();
  if (!text || /^\d+$/.test(text)) { hideKartelaResults(); return; }
  _krSearchTmr = setTimeout(async () => {
    try {
      const res = await req('GET', `/products/search?query=${encodeURIComponent(text)}`);
      if ($('kr-search').value.trim() !== text) return;
      kartelaResults = (res.data || []).slice(0, 12);
      kartelaResultIndex = kartelaResults.length ? 0 : -1;
      renderKartelaResults();
    } catch {}
  }, 250);
}

function renderKartelaResults() {
  const box = $('kr-results');
  box.innerHTML = kartelaResults.length ? kartelaResults.map((p, i) => `
    <div class="sr-item ${i === kartelaResultIndex ? 'on' : ''}" onmousedown="event.preventDefault(); pickKartelaResult(${i})">
      <span class="sr-main"><span class="sr-name">${esc(p.name)}</span><span class="sr-code">${esc(p.barcode)}</span></span>
      <span class="sr-stock">${fmtQty(p.stock, p.unit)} ${esc(unitLabel(p.unit))}</span>
      <span class="sr-price">${fmt(p.price)}</span>
    </div>`).join('') : `<div class="sr-empty">${t('No products found')}</div>`;
  box.classList.remove('hidden');
}

function hideKartelaResults() {
  kartelaResults = [];
  $('kr-results')?.classList.add('hidden');
}

function pickKartelaResult(index) {
  const p = kartelaResults[index];
  if (!p) return;
  hideKartelaResults();
  $('kr-search').value = '';
  loadKartela(p.id);
}

async function onKartelaSearchKey(event) {
  const open = !$('kr-results').classList.contains('hidden') && kartelaResults.length;
  if (open && (event.key === 'ArrowDown' || event.key === 'ArrowUp')) {
    event.preventDefault();
    kartelaResultIndex = (kartelaResultIndex + (event.key === 'ArrowDown' ? 1 : -1) + kartelaResults.length) % kartelaResults.length;
    renderKartelaResults();
    return;
  }
  if (event.key !== 'Enter') return;
  event.preventDefault();
  const text = event.target.value.trim();
  if (/^\d{3,}$/.test(text)) {
    try {
      const res = await req('GET', `/products/scan/${encodeURIComponent(text)}`);
      event.target.value = '';
      hideKartelaResults();
      loadKartela(res.data.product.id);
    } catch (e) { toast(e.message, 'error'); }
  } else if (open) {
    pickKartelaResult(kartelaResultIndex);
  }
}

function setKartelaPeriod(days) {
  kartelaPeriod = days;
  loadKartela(kartelaId, true);
}

function scrollKartela(id) {
  $(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

function cardBoxPrice(box) {
  return box.price != null ? parseFloat(box.price) : parseFloat(productCard.product.price) * box.pieces;
}

function renderKartela() {
  const c = productCard;
  const p = c.product;
  const a = kartelaAnalysis;
  const stock = parseFloat(p.stock);
  const boxes = c.packages.filter(b => b.active);
  const biggest = boxes.reduce((max, b) => (!max || b.pieces > max.pieces ? b : max), null);
  const lastSale = c.sales.lastSaleAt ? new Date(c.sales.lastSaleAt) : null;
  const deadStock = stock > 0 && (!lastSale || Date.now() - lastSale.getTime() > 60 * 86400000);
  const changedToday = c.priceHistory.some(ch => ch.newPrice != null && isoDay(new Date(ch.changedAt)) === isoDay(new Date()));
  const low = p.minStock != null && stock <= parseFloat(p.minStock);
  const badge = (cls, text) => `<span class="kr-badge ${cls}">${text}</span>`;
  const badges = [
    badge('', esc(c.category || t('None'))),
    badge('', esc(unitLabel(p.unit))),
    badge('', `${t('VAT')} ${fmtTax(p.taxRate)}`),
    p.active ? '' : badge('bad', t('Inactive')),
    stock <= 0 ? badge('bad', t('Out of stock')) : low ? badge('warn', t('Below minimum stock')) : badge('good', t('In stock')),
    deadStock ? badge('warn', t('No sales in 60 days')) : '',
    changedToday ? badge('info', t('Price changed today')) : '',
    boxes.length ? badge('info', t('{count} boxes', { count: boxes.length })) : ''
  ].join('');
  const kpi = (label, value, sub = '', tone = '') => `<div class="kr-kpi ${tone}"><span>${label}</span><strong>${value}</strong>${sub ? `<small>${sub}</small>` : ''}</div>`;
  const margin = parseFloat(c.marginPercent);
  const period = t('{days} days', { days: a.days });

  $('kr-page').innerHTML = `
    <section class="kr-hero">
      <div class="kr-hero-main">
        <div class="kr-eyebrow">${esc(p.barcode)}</div>
        <h1>${esc(p.name)}</h1>
        <div class="kr-badges">${badges}</div>
      </div>
      <div class="kr-hero-price">
        <span>${t('Selling price')}</span>
        <strong>${fmtLek(p.price)}</strong>
        ${c.unitPriceLabel ? `<small>${esc(c.unitPriceLabel)} ${fmtLek(c.unitPrice)}</small>` : ''}
      </div>
      <div class="kr-hero-actions">
        <button class="btn btn-primary btn-sm" onclick="openProdModalById(${p.id})">${t('Edit')}</button>
        <button class="btn btn-secondary btn-sm" onclick="openAdjust(${p.id})">${t('Adjust stock')}</button>
        <button class="btn btn-secondary btn-sm" onclick="openLabelsForCard()">${t('Print label')}</button>
        ${p.unit === 'pcs' ? `<button class="btn btn-secondary btn-sm" onclick="openPackageModal()">${t('Add box')}</button>` : ''}
        <button class="btn btn-secondary btn-sm" onclick="printKartela()">${t('Print product card')}</button>
        ${p.active ? `<button class="btn btn-ghost btn-sm kr-danger" onclick="confirmDelete(${p.id})">${t('Deactivate')}</button>`
          : `<button class="btn btn-secondary btn-sm" onclick="activateProd(${p.id})">${t('Reactivate')}</button>`}
      </div>
    </section>

    <nav class="kr-nav">${KARTELA_SECTIONS.map(([id, label]) => `<button onclick="scrollKartela('${id}')">${t(label)}</button>`).join('')}</nav>

    <section class="kr-kpis">
      ${kpi(t('Stock'), `${fmtQty(p.stock, p.unit)} ${esc(unitLabel(p.unit))}`,
        biggest && stock >= biggest.pieces ? esc('= ' + t('{count} x {box}', { count: Math.floor(stock / biggest.pieces), box: biggest.name })) : '',
        stock <= 0 ? 'bad' : low ? 'warn' : '')}
      ${kpi(t('Stock value'), fmtLek(Math.max(0, stock) * parseFloat(p.purchasePrice)), `${t('at selling price')} ${fmtLek(Math.max(0, stock) * parseFloat(p.price))}`)}
      ${kpi(t('Average cost'), fmtLek(p.purchasePrice), `${t('Margin')} ${margin.toFixed(1)}%`, margin < 10 ? 'warn' : '')}
      ${kpi(t('Sold'), `${fmtQty(a.quantity, p.unit)} ${esc(unitLabel(p.unit))}`, `${period} · ${t('{count} receipts', { count: a.receipts })}`)}
      ${kpi(t('Revenue'), fmtLek(a.revenue), period)}
      ${kpi(t('Profit'), fmtLek(a.profit), `${t('Margin')} ${parseFloat(a.marginPercent).toFixed(1)}% · ${period}`, parseFloat(a.profit) < 0 ? 'bad' : 'good')}
      ${kpi(t('Days of stock left'), c.sales.daysOfStockLeft == null ? '-' : c.sales.daysOfStockLeft,
        t('{n} per day', { n: parseFloat(a.averagePerDay).toFixed(1) }))}
      ${kpi(t('Last sale'), lastSale ? fmtDate(c.sales.lastSaleAt) : t('Never sold'),
        a.lastPurchase ? `${t('Last purchase')} ${formatDay(a.lastPurchase.date)}` : t('Not bought on a purchase invoice yet.'))}
    </section>

    <div class="kr-period">
      <span>${t('Period')}</span>
      <div class="seg">${[30, 90, 365].map(d => `<button class="btn btn-secondary btn-sm ${d === a.days ? 'on' : ''}" onclick="setKartelaPeriod(${d})">${t('{days} days', { days: d })}</button>`).join('')}</div>
    </div>

    <div class="kr-grid">
      <section class="kr-card kr-wide" id="kr-sales">
        <header><h2>${t('Sales')}</h2><p>${t('Quantity sold per day')} · ${period}</p></header>
        ${svgBars(a.daily.map(d => ({ value: parseFloat(d.quantity), title: `${formatDay(d.day)}: ${fmtQty(d.quantity, p.unit)} · ${fmtLek(d.revenue)}` })), 150)}
        ${axisDays(a.daily.map(d => d.day))}
      </section>

      <section class="kr-card kr-wide" id="kr-stock">
        <header><h2>${t('Stock')}</h2><p>${t('Stock at the end of each day')}${p.minStock != null ? ` · ${t('dashed line: minimum stock')}` : ''}</p></header>
        ${svgLine(a.stock.map(s => ({ value: parseFloat(s.stock), title: `${formatDay(s.day)}: ${fmtQty(s.stock, p.unit)}` })), p.minStock != null ? parseFloat(p.minStock) : null, 150)}
        ${axisDays(a.stock.map(s => s.day))}
      </section>

      <section class="kr-card">
        <header><h2>${t('Sales by weekday')}</h2><p>${period}</p></header>
        ${svgBars(a.byWeekday.map((v, i) => ({ value: parseFloat(v), title: `${t(WEEKDAYS_SHORT[i])}: ${fmtQty(v, p.unit)}` })), 120, true)}
        <div class="kr-axis even">${WEEKDAYS_SHORT.map(d => `<span>${t(d)}</span>`).join('')}</div>
      </section>

      <section class="kr-card">
        <header><h2>${t('Sales by hour')}</h2><p>${t('Busiest hours are darker')}</p></header>
        ${heatHours(a.byHour, p.unit)}
      </section>

      <section class="kr-card" id="kr-details">
        <header><h2>${t('Details')}</h2></header>
        <dl class="kr-dl">
          <dt>${t('Barcode')}</dt><dd class="td-m">${esc(p.barcode)}</dd>
          <dt>${t('Category')}</dt><dd>${esc(c.category || t('None'))}</dd>
          <dt>${t('Unit')}</dt><dd>${esc(unitLabel(p.unit))}</dd>
          <dt>${t('VAT')}</dt><dd>${fmtTax(p.taxRate)}</dd>
          <dt>${t('Content of one piece')}</dt><dd>${p.contentAmount ? `${parseFloat(p.contentAmount)} ${esc(p.contentUnit)}` : '-'}</dd>
          <dt>${t('Average cost')}</dt><dd>${fmtLek(p.purchasePrice)}</dd>
          <dt>${t('Minimum stock')}</dt><dd>${p.minStock != null ? fmtQty(p.minStock, p.unit) : '-'}</dd>
          <dt>${t('Reorder quantity')}</dt><dd>${p.reorderQuantity != null ? fmtQty(p.reorderQuantity, p.unit) : t('Automatic')}</dd>
        </dl>
      </section>

      <section class="kr-card" id="kr-suppliers">
        <header><h2>${t('Suppliers')}</h2>${a.lastPurchase ? `<p>${t('Last purchase')}: ${formatDay(a.lastPurchase.date)} · ${esc(a.lastPurchase.supplier)} · ${esc(a.lastPurchase.invoice)}</p>` : ''}</header>
        ${krTable([t('Supplier'), t('Last purchase'), t('Last price (piece)'), t('Total bought'), t('Invoices')],
          c.suppliers.map(s => [`<strong>${esc(s.supplier)}</strong>`, formatDay(s.lastPurchase), fmtLek(s.lastPurchasePrice),
            fmtQty(s.totalQuantity, p.unit), s.purchases]), t('Not bought on a purchase invoice yet.'))}
      </section>

      <section class="kr-card kr-wide" id="kr-boxes">
        <header><h2>${t('Boxes')}</h2><p>${t('A box has its own barcode. Selling or buying a box moves its pieces in and out of the stock of this product.')}</p></header>
        ${p.unit !== 'pcs' ? `<p class="no-data">${t('Only products sold by the piece can have boxes.')}</p>` : krTable(
          [t('Box'), t('Barcode'), t('Pieces'), t('Box price'), t('Per piece'), t('Customer saves'), ''],
          c.packages.map(b => {
            const price = cardBoxPrice(b);
            const saving = parseFloat(p.price) * b.pieces - price;
            return [`<strong>${esc(b.name)}</strong>${b.active ? '' : ` <span class="badge b-red">${t('Inactive')}</span>`}`, `<span class="td-m">${esc(b.barcode)}</span>`,
              b.pieces, `${fmtLek(price)}${b.price == null ? ` <span class="badge b-muted">${t('auto')}</span>` : ''}`, fmtLek(price / b.pieces),
              saving > 0.004 ? fmtLek(saving) : '-', `<button class="btn btn-secondary btn-sm" onclick="openPackageModal(${b.id})">${t('Edit')}</button>`];
          }), t('No boxes yet.'))}
      </section>

      <section class="kr-card kr-wide" id="kr-prices">
        <header><h2>${t('Price history')}</h2><p>${t('Every change of the selling or purchase price')}</p></header>
        ${krTable([t('Date'), t('For'), t('Selling price'), t('Purchase price'), t('Source'), t('User')],
          c.priceHistory.map(ch => {
            const box = ch.packageId ? c.packages.find(b => b.id === ch.packageId) : null;
            const change = (from, to) => to == null ? '' : `${from == null ? '' : fmtLek(from) + ' → '}<strong>${fmtLek(to)}</strong>`;
            return [fmtDate(ch.changedAt), box ? esc(box.name) : t('Piece'), change(ch.oldPrice, ch.newPrice),
              change(ch.oldPurchasePrice, ch.newPurchasePrice), esc(t(PRICE_SOURCES[ch.source] || ch.source)), esc(ch.cashierName || '')];
          }), t('No price changes yet.'))}
      </section>

      <section class="kr-card kr-wide" id="kr-moves">
        <header><h2>${t('Stock movements')}</h2><p>${t('Every sale, refund, purchase and adjustment, with the stock after it')}</p></header>
        <div class="kr-filters">
          <select class="input" id="krm-type" onchange="filterKartelaMoves()">
            <option value="">${t('All types')}</option>
            ${Object.entries(MOVEMENT_LABELS).map(([k, v]) => `<option value="${k}">${t(v)}</option>`).join('')}
          </select>
          <input class="input" id="krm-from" type="date" onchange="filterKartelaMoves()" />
          <input class="input" id="krm-to" type="date" onchange="filterKartelaMoves()" />
          <span class="tb-count" id="krm-count"></span>
          <button class="btn btn-secondary btn-sm" onclick="exportKartelaMoves()">${t('Excel')}</button>
        </div>
        <div id="krm-table"></div>
      </section>

      <section class="kr-card" id="kr-customers">
        <header><h2>${t('Top customers')}</h2><p>${t('Customers with a loyalty card')} · ${period}</p></header>
        ${krTable([t('Customer'), t('Quantity'), t('Revenue'), t('Receipts')],
          a.topCustomers.map(cu => [`<strong>${esc(cu.name)}</strong><div class="wh-sub">${esc(cu.cardNumber || '')}</div>`,
            fmtQty(cu.quantity, p.unit), fmtLek(cu.revenue), cu.purchases]), t('No customer with a card bought it in this period.'))}
      </section>

      <section class="kr-card" id="kr-promos">
        <header><h2>${t('Promotions')}</h2><p>${t('Promotions on this product or its category')}</p></header>
        ${a.promotions.length ? `<ul class="kr-list">${a.promotions.map(pr => `<li>
            <span class="kr-dot ${pr.runningNow ? 'good' : pr.active ? 'info' : ''}"></span>
            <div><strong>${esc(pr.name)}</strong><div class="wh-sub">${esc(t(PROMOTION_TYPES[pr.type] || pr.type))} · ${esc(pr.scope)}
              ${pr.startsOn || pr.endsOn ? ` · ${pr.startsOn ? formatDay(pr.startsOn) : '…'} – ${pr.endsOn ? formatDay(pr.endsOn) : '…'}` : ''}</div></div>
            <span class="kr-badge ${pr.runningNow ? 'good' : pr.active ? 'info' : ''}">${pr.runningNow ? t('Running now') : pr.active ? t('Active') : t('Inactive')}</span>
          </li>`).join('')}</ul>` : `<p class="no-data">${t('No promotions.')}</p>`}
      </section>

      <section class="kr-card kr-wide" id="kr-history">
        <header><h2>${t('History')}</h2><p>${t('Who changed what on this product')}</p></header>
        ${c.history.length ? `<ol class="kr-timeline">${c.history.map(e => `<li>
            <time>${fmtDate(e.createdAt)}</time>
            <div><strong>${esc(auditActionLabel(e.action))}</strong> · ${esc(e.cashierName || '')}<div class="wh-sub">${esc(e.details || '')}</div></div>
          </li>`).join('')}</ol>` : `<p class="no-data">-</p>`}
      </section>
    </div>`;
  filterKartelaMoves();
}

function krTable(header, rows, empty) {
  return `<div class="tbl-wrap kr-table"><table><thead><tr>${header.map(h => `<th>${h}</th>`).join('')}</tr></thead>
    <tbody>${rows.length ? rows.map(r => `<tr>${r.map(v => `<td>${v}</td>`).join('')}</tr>`).join('')
      : `<tr><td colspan="${header.length}" class="no-data">${empty}</td></tr>`}</tbody></table></div>`;
}

function filterKartelaMoves() {
  if (!productCard) return;
  const p = productCard.product;
  const type = $('krm-type').value;
  const from = $('krm-from').value;
  const to = $('krm-to').value;
  const rows = kartelaMoves.filter(m => (!type || m.type === type)
    && (!from || m.at.slice(0, 10) >= from) && (!to || m.at.slice(0, 10) <= to));
  $('krm-count').textContent = t('{count} movements', { count: rows.length });
  $('krm-table').innerHTML = krTable([t('Date'), t('Type'), t('Document'), t('Quantity change'), t('Stock after'), t('Details'), t('User')],
    rows.slice(0, 500).map(m => {
      const change = parseFloat(m.change);
      const what = m.type === 'ADJUSTMENT' ? t(ADJUST_REASONS[m.reference] || m.reference) : (m.reference || '');
      return [fmtDate(m.at), `<span class="kr-badge mv-${m.type}">${esc(t(MOVEMENT_LABELS[m.type] || m.type))}</span>`, esc(what),
        `<strong class="${change < 0 ? 'diff-minus' : 'diff-plus'}">${change > 0 ? '+' : ''}${fmtQty(change, p.unit)}</strong>`,
        `<span class="td-m">${fmtQty(m.stockAfter, p.unit)}</span>`, esc(m.detail || ''), esc(m.cashier || '')];
    }), t('No stock movements yet.'));
}

function exportKartelaMoves() {
  const params = new URLSearchParams();
  if ($('krm-type').value) params.set('type', $('krm-type').value);
  if ($('krm-from').value) params.set('from', $('krm-from').value);
  if ($('krm-to').value) params.set('to', $('krm-to').value);
  downloadFile(`/warehouse/products/${productCard.product.id}/movements.xlsx?${params}`);
}

/* Small SVG charts: they stay sharp at any size and show the value on hover. */
function svgBars(points, height, spaced = false) {
  const max = Math.max(1, ...points.map(p => p.value));
  const w = 100 / Math.max(1, points.length);
  const gap = spaced ? w * 0.25 : Math.min(0.35, w * 0.2);
  return `<svg class="kr-chart" viewBox="0 0 100 ${height}" preserveAspectRatio="none" style="height:${height}px">
    ${[0.25, 0.5, 0.75].map(f => `<line x1="0" x2="100" y1="${height * f}" y2="${height * f}" class="grid" />`).join('')}
    ${points.map((p, i) => {
      const h = p.value > 0 ? Math.max(1.5, p.value / max * (height - 6)) : 0;
      return `<rect x="${i * w + gap / 2}" y="${height - h}" width="${Math.max(0.2, w - gap)}" height="${h}" rx="0.6"><title>${esc(p.title)}</title></rect>
        <rect class="hit" x="${i * w}" y="0" width="${w}" height="${height}"><title>${esc(p.title)}</title></rect>`;
    }).join('')}
  </svg>`;
}

function svgLine(points, minimum, height) {
  if (!points.length) return '';
  const values = points.map(p => p.value);
  const top = Math.max(1, ...values, minimum || 0) * 1.1;
  const bottom = Math.min(0, ...values);
  const y = v => height - (v - bottom) / (top - bottom) * height;
  const x = i => points.length === 1 ? 50 : i / (points.length - 1) * 100;
  const line = points.map((p, i) => `${x(i).toFixed(2)},${y(p.value).toFixed(2)}`).join(' ');
  const w = 100 / points.length;
  return `<svg class="kr-chart line" viewBox="0 0 100 ${height}" preserveAspectRatio="none" style="height:${height}px">
    ${[0.25, 0.5, 0.75].map(f => `<line x1="0" x2="100" y1="${height * f}" y2="${height * f}" class="grid" />`).join('')}
    <polygon class="area" points="0,${height} ${line} 100,${height}" />
    <polyline class="stroke" points="${line}" vector-effect="non-scaling-stroke" />
    ${minimum != null ? `<line x1="0" x2="100" y1="${y(minimum)}" y2="${y(minimum)}" class="min" vector-effect="non-scaling-stroke" />` : ''}
    ${points.map((p, i) => `<rect class="hit" x="${Math.max(0, x(i) - w / 2)}" y="0" width="${w}" height="${height}"><title>${esc(p.title)}</title></rect>`).join('')}
  </svg>`;
}

function axisDays(days) {
  if (!days.length) return '';
  const pick = [0, Math.floor(days.length / 2), days.length - 1];
  return `<div class="kr-axis">${pick.map(i => `<span>${formatDay(days[i])}</span>`).join('')}</div>`;
}

function heatHours(hours, unit) {
  const values = hours.map(v => parseFloat(v));
  const max = Math.max(1, ...values);
  return `<div class="kr-hours">${values.map((v, h) => `<div class="kr-hour" style="--a:${v > 0 ? 0.15 + 0.85 * v / max : 0.04}"
      title="${String(h).padStart(2, '0')}:00 – ${fmtQty(v, unit)}"><span>${h % 3 === 0 ? String(h).padStart(2, '0') : ''}</span></div>`).join('')}</div>`;
}

/*  Regjistri i Veprimeve: every operation of the shop  */
const JOURNAL_TYPES = {
  SALE: { label: 'Sale', icon: '<circle cx="9" cy="21" r="1"/><circle cx="20" cy="21" r="1"/><path d="M1 1h4l2.7 13.4a2 2 0 0 0 2 1.6h9.7a2 2 0 0 0 2-1.6L23 6H6"/>' },
  REFUND: { label: 'Refund', icon: '<polyline points="1 4 1 10 7 10"/><path d="M3.5 15a9 9 0 1 0 2.1-9.4L1 10"/>' },
  PURCHASE: { label: 'Purchase invoice', icon: '<rect x="1" y="3" width="15" height="13"/><polygon points="16 8 20 8 23 11 23 16 16 16 16 8"/><circle cx="5.5" cy="18.5" r="2.5"/><circle cx="18.5" cy="18.5" r="2.5"/>' },
  ADJUSTMENT: { label: 'Stock adjustment', icon: '<path d="M21 16V8a2 2 0 0 0-1-1.7l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.7l7 4a2 2 0 0 0 2 0l7-4a2 2 0 0 0 1-1.7z"/>' },
  COUNT: { label: 'Stock count', icon: '<path d="M9 11l3 3L22 4"/><path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11"/>' },
  CASH_IN: { label: 'Cash in', icon: '<line x1="12" y1="5" x2="12" y2="19"/><polyline points="19 12 12 19 5 12"/>' },
  CASH_OUT: { label: 'Cash out', icon: '<line x1="12" y1="19" x2="12" y2="5"/><polyline points="5 12 12 5 19 12"/>' },
  SUPPLIER_PAYMENT: { label: 'Supplier payment', icon: '<rect x="1" y="4" width="22" height="16" rx="2"/><line x1="1" y1="10" x2="23" y2="10"/>' },
  DEBT_PAYMENT: { label: 'Debt payment', icon: '<path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>' },
  SHIFT_OPEN: { label: 'Shift opened', icon: '<polygon points="5 3 19 12 5 21 5 3"/>' },
  SHIFT_CLOSE: { label: 'Shift closed', icon: '<rect x="5" y="5" width="14" height="14" rx="1"/>' },
  SUPPLIER_RETURN: { label: 'Return to supplier', icon: '<polyline points="9 14 4 9 9 4"/><path d="M20 20v-7a4 4 0 0 0-4-4H4"/>' },
  OPENING_STOCK: { label: 'Opening stock', icon: '<path d="M12 2v20"/><path d="M5 9l7-7 7 7"/><rect x="3" y="18" width="18" height="4" rx="1"/>' },
  EXPENSE: { label: 'Expense', icon: '<path d="M21 12V7H5a2 2 0 0 1 0-4h14v4"/><path d="M3 5v14a2 2 0 0 0 2 2h16v-5"/><path d="M18 12a2 2 0 0 0 0 4h4v-4z"/>' },
  PRICE_CHANGE: { label: 'Price change', icon: '<path d="M20.6 13.4l-7.2 7.2a2 2 0 0 1-2.8 0L2 12V2h10l8.6 8.6a2 2 0 0 1 0 2.8z"/><line x1="7" y1="7" x2="7.01" y2="7"/>' }
};
const journalTypes = new Set();
let journalData = null;
let journalEntries = [];
let journalPageNumber = 0;
let journalUsersLoaded = false;

async function initJournal() {
  if (!journalUsersLoaded) {
    try {
      const users = (await req('GET', '/journal/users')).data || [];
      $('jr-user').innerHTML = `<option value="">${t('All users')}</option>` + users.map(u => `<option value="${u.id}">${esc(u.name)}</option>`).join('');
      journalUsersLoaded = true;
    } catch {}
  }
  loadJournal();
}

function onJournalPeriod() {
  const custom = $('jr-period').value === 'custom';
  $('jr-from').classList.toggle('hidden', !custom);
  $('jr-to').classList.toggle('hidden', !custom);
  loadJournal();
}

let _jrSearchTmr;
function onJournalSearch() {
  clearTimeout(_jrSearchTmr);
  _jrSearchTmr = setTimeout(() => loadJournal(), 300);
}

function journalParams(page) {
  const range = periodRange($('jr-period').value, $('jr-from').value, $('jr-to').value);
  const params = new URLSearchParams({ from: range.from, to: range.to });
  if (journalTypes.size) params.set('types', [...journalTypes].join(','));
  if ($('jr-user').value) params.set('cashierId', $('jr-user').value);
  if ($('jr-q').value.trim()) params.set('q', $('jr-q').value.trim());
  if (page !== undefined) { params.set('page', page); params.set('size', 100); }
  return params;
}

async function loadJournal(more = false) {
  const page = more ? journalPageNumber + 1 : 0;
  if (!more) $('jr-timeline').innerHTML = `<div class="kr-loading">${t('Loading...')}</div>`;
  try {
    const res = await req('GET', '/journal?' + journalParams(page));
    journalData = res.data;
    journalPageNumber = page;
    journalEntries = more ? journalEntries.concat(res.data.entries) : res.data.entries;
    renderJournal();
  } catch (e) {
    $('jr-timeline').innerHTML = `<div class="kr-empty"><strong>${esc(e.message)}</strong></div>`;
  }
}

function toggleJournalType(type) {
  if (type === null) journalTypes.clear();
  else if (journalTypes.has(type)) journalTypes.delete(type);
  else journalTypes.add(type);
  loadJournal();
}

function journalIcon(type) {
  return `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">${JOURNAL_TYPES[type].icon}</svg>`;
}

function renderJournal() {
  const counts = Object.fromEntries((journalData.totals || []).map(x => [x.type, x]));
  $('jr-chips').innerHTML = `<button class="jr-chip ${journalTypes.size ? '' : 'on'}" onclick="toggleJournalType(null)">${t('All')}</button>`
    + Object.keys(JOURNAL_TYPES).map(type => `<button class="jr-chip jt-${type} ${journalTypes.has(type) ? 'on' : ''}" onclick="toggleJournalType('${type}')">
        ${journalIcon(type)} ${t(JOURNAL_TYPES[type].label)}${counts[type] ? ` <b>${counts[type].count}</b>` : ''}</button>`).join('');

  const card = (types, label, tone) => {
    const list = types.map(x => counts[x]).filter(Boolean);
    const count = list.reduce((s, x) => s + x.count, 0);
    const amount = list.reduce((s, x) => s + parseFloat(x.amount || 0), 0);
    return `<div class="jr-card ${tone}"><span>${label}</span><strong>${fmtLek(Math.abs(amount))}</strong><small>${t('{count} operations', { count })}</small></div>`;
  };
  $('jr-summary').innerHTML = card(['SALE'], t('Sales'), 'good') + card(['REFUND'], t('Refunds'), 'bad')
    + card(['PURCHASE'], t('Purchase invoices'), 'info') + card(['CASH_IN', 'DEBT_PAYMENT'], t('Cash in and debt payments'), 'good')
    + card(['CASH_OUT', 'SUPPLIER_PAYMENT'], t('Cash out and supplier payments'), 'bad')
    + card(['EXPENSE'], t('Expenses'), 'bad')
    + card(['ADJUSTMENT', 'COUNT'], t('Stock adjustments and counts (at cost)'), 'warn');

  const days = Object.fromEntries((journalData.days || []).map(d => [d.day, d]));
  if (!journalEntries.length) {
    $('jr-timeline').innerHTML = `<div class="kr-empty"><strong>${t('No operations in this period.')}</strong><span>${t('Choose another period or remove a filter.')}</span></div>`;
  } else {
    let html = '';
    let current = null;
    journalEntries.forEach((e, i) => {
      const day = e.at.slice(0, 10);
      if (day !== current) {
        if (current) html += '</div>';
        current = day;
        const d = days[day];
        html += `<div class="jr-day"><header><strong>${esc(formatWeekdayDate(new Date(day + 'T12:00:00')))}</strong>
          ${d ? `<span>${t('{count} operations', { count: d.count })} · <em class="in">+${fmtLek(d.moneyIn)}</em> · <em class="out">−${fmtLek(d.moneyOut)}</em></span>` : ''}</header>`;
      }
      html += journalRow(e, i);
    });
    $('jr-timeline').innerHTML = html + '</div>';
  }
  const rest = journalData.total - journalEntries.length;
  $('jr-more').innerHTML = rest > 0
    ? `<button class="btn btn-secondary" onclick="loadJournal(true)">${t('Show {count} more', { count: Math.min(100, rest) })}</button> <span class="tb-count">${t('{shown} of {total}', { shown: journalEntries.length, total: journalData.total })}</span>`
    : (journalEntries.length ? `<span class="tb-count">${t('{count} operations', { count: journalData.total })}</span>` : '');
}

/* What one entry says, in words. */
function journalText(e) {
  switch (e.type) {
    case 'SALE': return [`${t('Invoice')} ${e.number}`, [e.counterparty, linesText(e.lines)].filter(Boolean).join(' · ')];
    case 'REFUND': return [`${e.number} · ${t('for invoice')} ${e.counterparty || ''}`, e.note];
    case 'PURCHASE': return [`${e.counterparty || ''} · ${e.number}`, linesText(e.lines)];
    case 'ADJUSTMENT': return [`${e.productName} · ${parseFloat(e.quantity) > 0 ? '+' : ''}${parseFloat(e.quantity)}`, [t(ADJUST_REASONS[e.number] || e.number), e.note].filter(Boolean).join(' · ')];
    case 'SUPPLIER_RETURN': {
      if (e.note === 'REPLACED') return [`${e.number} · ${e.counterparty}`, t('Replacement received')];
      const [kind, reason] = (e.note || '').split(' ');
      const rest = (e.note || '').split(' - ').slice(1).join(' - ');
      return [`${e.number} · ${e.counterparty}`, [t(RETURN_KINDS[kind] || kind), t(RETURN_REASONS[reason] || reason), linesText(e.lines), rest].filter(Boolean).join(' · ')];
    }
    case 'OPENING_STOCK': return [`${e.productName} · +${parseFloat(e.quantity)}`, e.note || ''];
    case 'COUNT': return [`${t('Stock count')} ${e.number}`, [t('{count} products changed', { count: e.lines }), e.note].filter(Boolean).join(' · ')];
    case 'CASH_IN': case 'CASH_OUT': return [e.note, ''];
    case 'SUPPLIER_PAYMENT': return [e.counterparty, e.note];
    case 'DEBT_PAYMENT': return [e.counterparty, e.note];
    case 'SHIFT_OPEN': return [`${t('Shift')} ${e.number}`, `${t('Opening cash')} ${fmtLek(e.before)}`];
    case 'SHIFT_CLOSE': return [`${t('Shift')} ${e.number}`, `${t('Counted')} ${fmtLek(e.before)} · ${t('Difference')} ${fmtLek(e.after)}`];
    case 'EXPENSE': {
      const [method, ...rest] = (e.note || '').split(' - ');
      return [[e.number, e.counterparty].filter(Boolean).join(' · '), [t(EXPENSE_METHODS[method] || method), rest.join(' - ')].filter(Boolean).join(' · ')];
    }
    case 'PRICE_CHANGE': return [e.productName, `${t(e.note === 'COST' ? 'Purchase price' : 'Selling price')}: ${e.before != null ? fmtLek(e.before) + ' → ' : ''}${fmtLek(e.after)} · ${t(PRICE_SOURCES[e.number] || e.number)}`];
    default: return [e.number || '', e.note || ''];
  }
}

function journalRow(e, index) {
  const [main, sub] = journalText(e);
  const amount = e.amount == null ? '' : parseFloat(e.amount);
  return `<div class="jr-entry jt-${e.type}" onclick="openJournalEntry(${index})">
    <time>${formatTime(new Date(e.at))}</time>
    <span class="jr-icon">${journalIcon(e.type)}</span>
    <div class="jr-text"><div><b>${t(JOURNAL_TYPES[e.type].label)}</b> ${esc(main || '')}</div>${sub ? `<small>${esc(sub)}</small>` : ''}</div>
    <span class="jr-user">${esc(e.user || '')}</span>
    <strong class="jr-amount ${amount === '' ? '' : amount < 0 ? 'out' : 'in'}">${amount === '' ? '' : (amount > 0 ? '+' : '−') + fmtLek(Math.abs(amount))}</strong>
  </div>`;
}

function openJournalEntry(index) {
  const e = journalEntries[index];
  const [main, sub] = journalText(e);
  const row = (label, value) => value == null || value === '' ? '' : `<div class="balance-line"><span>${label}</span><strong>${value}</strong></div>`;
  $('je-title').innerHTML = `<span class="jr-icon jt-${e.type}">${journalIcon(e.type)}</span> ${t(JOURNAL_TYPES[e.type].label)}`;
  $('je-body').innerHTML = `<p class="sale-detail-meta">${esc(main || '')}${sub ? `<br><span class="t-muted">${esc(sub)}</span>` : ''}</p>`
    + row(t('Date'), fmtDate(e.at)) + row(t('Document'), esc(e.number || '')) + row(t('Customer / supplier'), esc(e.counterparty || ''))
    + row(t('Product'), esc(e.productName || '')) + row(t('User'), esc(e.user || ''))
    + row(t('Amount'), e.amount == null ? '' : fmtLek(e.amount));
  const actions = [`<button class="btn btn-secondary" onclick="closeModal('modal-journal')">${t('Close')}</button>`];
  if (e.type === 'SALE') actions.push(`<button class="btn btn-primary" onclick="closeModal('modal-journal'); viewSale(${e.refId})">${t('Open the sale')}</button>`);
  if (e.productId) actions.push(`<button class="btn btn-primary" onclick="closeModal('modal-journal'); openKartela(${e.productId})">${t('Product card')}</button>`);
  if (e.type === 'SHIFT_OPEN' || e.type === 'SHIFT_CLOSE') actions.push(`<button class="btn btn-primary" onclick="closeModal('modal-journal'); showShiftReport(${e.refId})">${t('Shift report')}</button>`);
  $('je-actions').innerHTML = actions.join('');
  openModal('modal-journal');
}

async function showShiftReport(shiftId) {
  try {
    const res = await req('GET', `/shifts/${shiftId}/report`);
    showReceipt(res.data.printableText);
  } catch (e) { toast(e.message, 'error'); }
}

function exportJournal() {
  downloadFile('/journal/export.xlsx?' + journalParams());
}

/*  Boxes  */
function openPackageModal(packageId) {
  const p = productCard.product;
  const box = packageId ? productCard.packages.find(b => b.id === packageId) : null;
  $('pkg-id').value = box?.id || '';
  $('pkg-title').textContent = box ? t('Edit box') : t('Add box');
  $('pkg-meta').textContent = `${p.name} — ${t('piece price')} ${fmtLek(p.price)}`;
  $('pkg-barcode').value = box?.barcode || '';
  $('pkg-name').value = box?.name || '';
  $('pkg-pieces').value = box?.pieces || '';
  $('pkg-price').value = box?.price ?? '';
  $('pkg-active').checked = box ? box.active : true;
  updatePackageHint();
  openModal('modal-package');
  setTimeout(() => $('pkg-barcode').focus(), 50);
}

function updatePackageHint() {
  const pieces = parseInt($('pkg-pieces').value, 10);
  const piecePrice = parseFloat(productCard?.product.price || 0);
  if (!pieces || pieces < 2) { $('pkg-hint').textContent = ''; return; }
  const full = piecePrice * pieces;
  const own = parseFloat($('pkg-price').value);
  $('pkg-hint').textContent = Number.isFinite(own) && own > 0
    ? t('{pieces} pieces one by one: {full}. With this box the customer pays {own} ({per} per piece).',
        { pieces, full: fmtLek(full), own: fmtLek(own), per: fmtLek(own / pieces) })
    : t('Without its own price the box costs {full} ({pieces} x {price}).', { full: fmtLek(full), pieces, price: fmtLek(piecePrice) });
}

async function savePackage() {
  const id = $('pkg-id').value;
  const price = $('pkg-price').value.trim();
  const body = {
    barcode: $('pkg-barcode').value.trim(),
    name: $('pkg-name').value.trim(),
    pieces: parseInt($('pkg-pieces').value, 10),
    price: price === '' ? null : parseFloat(price),
    active: $('pkg-active').checked
  };
  try {
    if (id) await req('PUT', `/warehouse/packages/${id}`, body);
    else await req('POST', `/warehouse/products/${productCard.product.id}/packages`, body);
    closeModal('modal-package');
    toast(t('Box saved'), 'success');
    await loadKartela(productCard.product.id, true);
    loadProdsTable();
  } catch (e) { toast(e.message, 'error'); }
}

/*  Bulk price change  */
let _bulkTmr;
function openBulkPrice() {
  $('bp-meta').textContent = t('{count} products selected. The new prices are shown before anything is saved.', { count: warehouseSelected.size });
  $('bp-percent').value = '';
  $('bp-rows').innerHTML = '';
  $('bp-apply').disabled = true;
  openModal('modal-bulk-price');
  setTimeout(() => $('bp-percent').focus(), 50);
}

function bulkPriceBody(apply) {
  return { productIds: [...warehouseSelected], percent: parseFloat($('bp-percent').value), roundTo: parseFloat($('bp-round').value), apply };
}

function previewBulkPrice() {
  clearTimeout(_bulkTmr);
  _bulkTmr = setTimeout(async () => {
    const body = bulkPriceBody(false);
    if (!Number.isFinite(body.percent)) { $('bp-rows').innerHTML = ''; $('bp-apply').disabled = true; return; }
    try {
      const res = await req('POST', '/warehouse/prices/bulk', body);
      $('bp-rows').innerHTML = res.data.map(r => `<tr><td>${esc(r.name)}</td><td class="td-p">${fmtLek(r.oldPrice)}</td>
        <td class="td-p"><strong>${fmtLek(r.newPrice)}</strong></td></tr>`).join('');
      $('bp-apply').disabled = false;
    } catch (e) {
      $('bp-rows').innerHTML = `<tr><td colspan="3" class="no-data" style="color:var(--red)">${esc(e.message)}</td></tr>`;
      $('bp-apply').disabled = true;
    }
  }, 300);
}

async function applyBulkPrice() {
  try {
    await req('POST', '/warehouse/prices/bulk', bulkPriceBody(true));
    closeModal('modal-bulk-price');
    toast(t('Prices changed. Print the new shelf labels.'), 'success');
    const ids = [...warehouseSelected];
    await loadProdsTable();
    openLabels(ids.map(id => ({ productId: id, copies: 1 })));
  } catch (e) { toast(e.message, 'error'); }
}

/*  Shelf labels  */
let labelItems = [];

function openLabelsForCard() {
  const p = productCard.product;
  openLabels([{ productId: p.id, copies: 1 }].concat(
    productCard.packages.filter(b => b.active).map(b => ({ productId: p.id, packageId: b.id, copies: 0 }))));
}

function openLabelsForSelected() {
  openLabels([...warehouseSelected].map(id => ({ productId: id, copies: 1 })));
}

function openLabelsChangedToday() {
  if (!warehouseChangedToday.size) { toast(t('No prices changed today.'), 'info'); return; }
  openLabels([...warehouseChangedToday].map(id => ({ productId: id, copies: 1 })));
}

async function openLabels(items) {
  labelItems = items;
  const printers = $('lbl-printer');
  printers.innerHTML = `<option value="">${t('Receipt printer from Settings')}</option>`;
  openModal('modal-labels');
  renderLabelItems();
  try {
    const res = await req('GET', '/printer/printers');
    (res.data || []).forEach(name => printers.add(new Option(name, name)));
  } catch {}
}

async function renderLabelItems() {
  try {
    const res = await req('POST', '/warehouse/labels/preview', { items: labelItems.map(i => ({ ...i, copies: Math.max(1, i.copies) })) });
    const labels = res.data;
    $('lbl-items').innerHTML = labels.map((l, i) => `<tr>
      <td><strong>${esc(l.name)}</strong>${l.packageText ? `<div class="wh-sub">${esc(l.packageText)}</div>` : ''}</td>
      <td><input class="input lbl-copies" type="number" min="0" max="50" value="${labelItems[i].copies}" onchange="setLabelCopies(${i}, this.value)" /></td>
      <td><button class="btn btn-ghost btn-sm" onclick="removeLabelItem(${i})">x</button></td></tr>`).join('');
    $('lbl-preview').innerHTML = labels.filter((l, i) => labelItems[i].copies > 0).map(l => `
      <div class="shelf-label">
        <div class="sl-name">${esc(l.name)}</div>
        ${l.packageText ? `<div class="sl-box">${esc(l.packageText)}</div>` : ''}
        <div class="sl-price">${esc(labelMoney(l.price))} <small>LEK</small></div>
        ${l.unitPriceText ? `<div class="sl-unit">${esc(l.unitPriceText)}</div>` : ''}
        <div class="sl-barcode">${esc(l.barcode)}</div>
        <div class="sl-shop">${esc(shopSettings?.name || '')} ${formatDay(isoDay(new Date()))}</div>
      </div>`).join('') || `<p class="no-data">${t('Nothing to print.')}</p>`;
  } catch (e) { toast(e.message, 'error'); }
}

function labelMoney(amount) {
  const n = parseFloat(amount);
  return Number.isInteger(n) ? String(n) : n.toFixed(2);
}

function setLabelCopies(index, value) {
  labelItems[index].copies = Math.max(0, Math.min(50, parseInt(value, 10) || 0));
  renderLabelItems();
}

function removeLabelItem(index) {
  labelItems.splice(index, 1);
  renderLabelItems();
}

async function printLabels() {
  const items = labelItems.filter(i => i.copies > 0);
  if (!items.length) { toast(t('Nothing to print.'), 'error'); return; }
  try {
    const res = await req('POST', '/warehouse/labels/print', { items, printerName: $('lbl-printer').value });
    closeModal('modal-labels');
    toast(t('{count} labels sent to the printer', { count: res.data.labels }), 'success');
  } catch (e) { toast(e.message, 'error'); }
}

/*
 * Stock is typed here only as the opening stock of a new product on the Products screen. When editing, it is
 * shown but changed with "Stock" (an adjustment with a reason). A product registered from a purchase invoice
 * starts at 0: its stock comes from that invoice.
 */
let prodModalForPurchase = false;

function openProdModal(prod, barcodePrefill, forPurchase = false) {
  const editing = !!prod;
  prodModalForPurchase = !editing && forPurchase;
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
  $('pm-stock').value  = prodModalForPurchase ? '0' : (prod?.stock ?? '');
  $('pm-stock').disabled = editing || prodModalForPurchase;
  $('pm-stock-hint').textContent = editing ? t('Change the stock with the "Stock" button (a reason is recorded).')
    : prodModalForPurchase ? t('The stock comes from this purchase invoice.') : '';
  $('pm-stock-hint').classList.toggle('hidden', !editing && !prodModalForPurchase);
  $('pm-min').value    = prod?.minStock ?? '';
  $('pm-reorder').value = prod?.reorderQuantity ?? '';
  $('pm-content').value = prod?.contentAmount ?? '';
  $('pm-content-unit').value = prod?.contentUnit || '';
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
    stock:        id ? null : parseQuantity($('pm-stock').value || '0'),
    categoryName: $('pm-cat').value,
    minStock:     $('pm-min').value === '' ? null : parseQuantity($('pm-min').value),
    reorderQuantity: $('pm-reorder').value === '' ? null : parseQuantity($('pm-reorder').value),
    contentAmount: $('pm-content').value === '' ? null : parseFloat($('pm-content').value),
    contentUnit: $('pm-content-unit').value || null,
  };
  if (!body.name || !body.barcode || isNaN(body.purchasePrice) || isNaN(body.taxRate) || isNaN(body.price) || (!id && isNaN(body.stock))) {
    toast(t('Please fill in all required fields'), 'error'); return;
  }
  try {
    if (id) {
      await req('PUT', `/products/${id}`, body);
      toast(t('Product updated'), 'success');
    } else {
      const res = await req('POST', '/products', body);
      if (prodModalForPurchase) {
        addPurchaseProduct(res.data);
        toast(t('Product registered and added to the invoice. Enter the quantity received.'), 'success');
      } else {
        toast(t('Product added'), 'success');
      }
    }
    closeModal('modal-prod');
    prodModalForPurchase = false;
    loadProdsTable();
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
  } catch (e) { toast(e.message || t('Save failed'), 'error'); }
}

async function deleteProd(id) {
  try {
    await req('DELETE', `/products/${id}`);
    toast(t('Product deactivated'), 'success');
    closeModal('modal-confirm');
    loadProdsTable();
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
    case 'last7': return { from: daysAgo(6), to: today };
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
      <td><span class="badge b-muted">${linesText(sale.lines)}</span></td>
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
      <td>${esc(i.productName || i.product?.name || '')}${i.packageName ? `<div class="wh-sub">${esc(parseFloat(i.packageCount) + ' x ' + i.packageName)}</div>` : ''}</td>
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
  try {
    const res = await req('GET', `/sales/${saleId}/receipt`);
    closeModal('modal-sale');
    showReceipt(res.data.receiptText);
  } catch (e) { toast(e.message, 'error'); }
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
      <div class="report-kpi"><span>${t('Credit notes')}</span><strong>${fmtLek(s.totalCredited)}</strong></div>
      <div class="report-kpi"><span>${t('Owed')}</span><strong>${fmtLek(s.balance)}</strong></div>`;
    $('sd-invoices').innerHTML = d.invoices.map(i => `
      <tr><td class="td-m">${esc(i.invoiceNumber)}</td><td>${esc(i.invoiceDate)}</td><td class="td-p">${fmtLek(i.totalAmount)}</td></tr>`).join('')
      || `<tr><td colspan="3" class="no-data">-</td></tr>`;
    $('sd-payments').innerHTML = d.payments.map(p => `
      <tr><td>${esc(p.paidOn)}</td><td>${esc(p.method === 'CASH' ? t('Cash') : t('Bank'))}</td><td class="td-p">${fmtLek(p.amount)}</td><td>${esc(p.note || '')}</td></tr>`).join('')
      || `<tr><td colspan="4" class="no-data">-</td></tr>`;
    $('sd-returns').innerHTML = (d.returns || []).map(r => `
      <tr><td class="td-m">${esc(r.number)}</td><td>${fmtDate(r.createdAt)}</td><td>${t(RETURN_KINDS[r.kind])}</td>
        <td><span class="kr-badge ${RETURN_STATUS[r.status][1]}">${t(RETURN_STATUS[r.status][0])}</span></td><td class="td-p">${fmtLek(r.total)}</td></tr>`).join('')
      || `<tr><td colspan="5" class="no-data">-</td></tr>`;
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
  if (tab === 'orders') loadOrders();
  if (tab === 'returns') loadReturns();
  if (tab === 'expiring') loadExpiring();
  if (tab === 'count') loadCount();
}

async function loadReorder() {
  const box = $('reorder-groups');
  box.innerHTML = `<p class="no-data">${t('Loading...')}</p>`;
  try {
    const res = await req('GET', '/inventory/reorder');
    const groups = res.data || [];
    lastReorderGroups = groups;
    box.innerHTML = groups.length ? groups.map((g, gi) => `
      <div class="reorder-group">
        <div class="reorder-head"><h3>${esc(g.supplierName || t('No supplier yet'))} — ${t('estimated {amount}', { amount: fmtLek(g.estimatedTotal) })}</h3>
          <button class="btn btn-primary btn-sm" onclick="createOrderFromReorder(${gi})">${t('Create order')}</button></div>
        <div class="tbl-wrap"><table>
          <thead><tr><th>${t('Product')}</th><th>${t('Barcode')}</th><th>${t('Stock')}</th><th>${t('Minimum stock')}</th><th>${t('To order')}</th><th>${t('Last cost')}</th><th>${t('Total')}</th></tr></thead>
          <tbody>${g.lines.map(l => `
            <tr><td><strong>${esc(l.productName)}</strong></td><td class="td-m">${esc(l.barcode)}</td>
              <td><span class="badge b-amber">${fmtQty(l.stock, l.unit)}</span></td><td class="td-m">${fmtQty(l.minStock, l.unit)}</td>
              <td><strong>${fmtQty(l.suggestedQuantity, l.unit)} ${esc(unitLabel(l.unit))}</strong>
                ${parseFloat(l.onOrder) > 0 ? `<div class="wh-sub">${t('{qty} already ordered', { qty: fmtQty(l.onOrder, l.unit) })}</div>` : ''}</td>
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
  COUNT_CORRECTION: 'Stock count', OTHER: 'Other', OPENING_STOCK: 'Opening stock', SUPPLIER_RETURN: 'Returned to supplier',
  REPLACEMENT: 'Replacement received' };

async function openAdjust(productId) {
  const p = allProdTable.find(x => x.id === productId) || (productCard?.product.id === productId ? productCard.product : null);
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
  CARTS_DISCARDED: 'Products thrown away at closing',
  PACKAGE_CREATED: 'Box added', PACKAGE_UPDATED: 'Box changed', PRICE_BULK: 'Prices changed in bulk',
  SETTINGS_CHANGED: 'Shop settings changed', RECEIPT_REPRINTED: 'Receipt reprinted',
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
    if (isSuperAdmin()) {
      renderBackupRows(backupsRes?.data || []);
      loadSystemInfo();
    }
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

/* Movements the program adds itself carry a two-language reason; show it in the language of the screen. */
const CASH_REASON_PREFIXES = [
  ['Shpenzim i anuluar / expense cancelled: ', 'Expense cancelled'],
  ['Shpenzim / expense: ', 'Expense'],
  ['Pagesë borxhi / debt payment: ', 'Debt payment']
];

/* "1 rresht", "2 rreshta": the singular is its own text. */
function linesText(count) {
  return Number(count) === 1 ? t('1 line') : t('{count} lines', { count });
}

function cashReasonText(reason) {
  const hit = CASH_REASON_PREFIXES.find(([prefix]) => (reason || '').startsWith(prefix));
  return hit ? `${t(hit[1])}: ${reason.slice(hit[0].length)}` : (reason || '');
}

async function loadCashMovements() {
  const list = $('cash-movements-list');
  if (!activeShift) { list.innerHTML = ''; return; }
  try {
    const res = await req('GET', `/shifts/${activeShift.id}/cash-movements`);
    list.innerHTML = (res.data || []).map(m => `
      <div class="balance-line">
        <span>${formatTime(m.createdAt)} · ${esc(m.type === 'IN' ? t('Cash in') : t('Cash out'))} · ${esc(cashReasonText(m.reason))}</span>
        <strong>${m.type === 'IN' ? '+' : '-'}${fmtLek(m.amount)}</strong>
      </div>`).join('');
  } catch { list.innerHTML = ''; }
}

async function addCashMovement() {
  if (!activeShift?.id) { toast(t('Open a shift first'), 'error'); return; }
  if ($('cash-type').value === 'EXPENSE') {
    try {
      await req('POST', '/expenses', { categoryId: Number($('cash-category').value), amount: parseFloat($('cash-amount').value || '0'),
        method: 'CASH', note: $('cash-reason').value.trim() });
      $('cash-amount').value = '';
      $('cash-reason').value = '';
      toast(t('Expense saved'), 'success');
      loadOperations();
    } catch (e) { toast(e.message || t('Save failed'), 'error'); }
    return;
  }
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
    let res;
    try {
      res = await req('POST', `/shifts/${activeShift.id}/close`, { closingCash });
    } catch (e) {
      if (e.code !== 'cart.unsoldItems') throw e;
      if (!confirm(t('There are products that are not sold yet. Are you sure you want to close the till? The tabs will be emptied and the sale will not be registered.'))) return;
      res = await req('POST', `/shifts/${activeShift.id}/close`, { closingCash, discardCarts: true });
      cartItems = [];
      cartSummary = null;
      renderCart();
    }
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

/* Program and database version (sidebar), and where backups are saved (Operations). */
async function loadSystemInfo() {
  try {
    const info = (await req('GET', '/system/info')).data;
    $('sb-version').textContent = 'v' + info.version;
    if ($('backup-info')) {
      $('backup-info').innerHTML = `
        <div class="balance-line"><span>${t('Backup folder')}</span><strong>${esc(info.backupFolder)}</strong></div>
        <div class="balance-line"><span>${t('Version')}</span><strong>${esc(info.version)} (${esc(info.built)}) · ${t('database')} ${esc(info.databaseVersion)}</strong></div>`;
    }
  } catch {}
}

/*  Shop settings  */
async function loadShopSettings() {
  try {
    const res = await req('GET', '/settings/shop');
    shopSettings = res.data;
    showShopName(shopSettings.name);
    sendToDisplay();
  } catch {}
}

function showShopName(name) {
  if (!name) return;
  if ($('sb-shop-name')) $('sb-shop-name').textContent = name;
  if ($('auth-shop-name')) $('auth-shop-name').textContent = name;
  document.title = name + ' - ' + t('Cash register');
}

function settingsFromForm() {
  return {
    name: $('set-name').value,
    address: $('set-address').value,
    city: $('set-city').value,
    taxId: $('set-taxid').value,
    phone: $('set-phone').value,
    email: $('set-email').value,
    receiptFooter: $('set-footer').value,
    receiptWidth: parseInt($('set-width').value, 10),
    autoPrint: $('set-autoprint').checked,
    receiptPrinter: $('set-printer').value
  };
}

async function loadSettingsForm() {
  await loadShopSettings();
  const s = shopSettings || {};
  $('set-name').value = s.name || '';
  $('set-address').value = s.address || '';
  $('set-city').value = s.city || '';
  $('set-taxid').value = s.taxId || '';
  $('set-phone').value = s.phone || '';
  $('set-email').value = s.email || '';
  $('set-footer').value = s.receiptFooter || '';
  $('set-width').value = String(s.receiptWidth || 32);
  $('set-autoprint').checked = !!s.autoPrint;
  const printers = $('set-printer');
  printers.innerHTML = `<option value="">${t('Default Windows printer')}</option>`;
  try {
    const res = await req('GET', '/printer/printers');
    (res.data || []).forEach(name => printers.add(new Option(name, name)));
  } catch {}
  if (s.receiptPrinter && ![...printers.options].some(o => o.value === s.receiptPrinter)) {
    printers.add(new Option(s.receiptPrinter, s.receiptPrinter));
  }
  printers.value = s.receiptPrinter || '';
  previewSettings();
}

let _previewTmr;
function previewSettings() {
  clearTimeout(_previewTmr);
  _previewTmr = setTimeout(async () => {
    try {
      const res = await req('POST', '/settings/shop/preview', settingsFromForm());
      $('settings-preview').textContent = res.data.receiptText;
    } catch (e) {
      $('settings-preview').textContent = e.message;
    }
  }, 300);
}

async function saveSettings() {
  try {
    const res = await req('PUT', '/settings/shop', settingsFromForm());
    shopSettings = res.data;
    showShopName(shopSettings.name);
    sendToDisplay();
    toast(t('Settings saved'), 'success');
  } catch (e) { toast(e.message, 'error'); }
}

/*  Customer display  */
/*
 * A second window (on the screen facing the customer) shows the cart, the total and the change. It is opened
 * from the till in the same browser and gets updates through a BroadcastChannel; it needs no login of its own.
 */
const displayChannel = 'BroadcastChannel' in window ? new BroadcastChannel('pos-customer-display') : null;
let displayIdleTimer;

function openCustomerDisplay() {
  const win = window.open('/display.html', 'pos-customer-display', 'popup,width=1024,height=768');
  if (!win) toast(t('Allow pop-ups for this page to open the customer display'), 'error');
  setTimeout(() => sendToDisplay(), 800);
}

function sendToDisplay(message) {
  if (!displayChannel) return;
  clearTimeout(displayIdleTimer);
  const shop = { name: shopSettings?.name || '', footer: shopSettings?.receiptFooter || '' };
  if (message) {
    displayChannel.postMessage({ ...message, shop, lang: currentLang });
    if (message.type === 'paid') {
      displayIdleTimer = setTimeout(() => sendToDisplay(), 15000);
    }
    return;
  }
  displayChannel.postMessage({
    type: 'cart',
    shop,
    lang: currentLang,
    items: cartItems.map(i => ({ name: i.packageName ? `${i.productName} (${i.packageName})` : i.productName,
      quantity: fmtQty(i.quantity, i.unit), unit: i.packageName ? t('box') : unitLabel(i.unit), total: i.lineTotal })),
    total: cartSummary?.totalAmount ?? cartItems.reduce((sum, i) => sum + parseFloat(i.lineTotal || 0), 0),
    discount: cartSummary?.discountAmount || 0,
    customer: cartSummary?.customer?.fullName || null
  });
}

if (displayChannel) {
  displayChannel.onmessage = e => { if (e.data?.type === 'hello' && currentUser) sendToDisplay(); };
}

/*  XSS escape  */
function esc(str) {
  return String(str ?? '').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&#39;');
}

/*  Keyboard shortcuts  */
const TILL_KEYS = {
  F1: () => openModal('modal-shortcuts'),
  F2: () => { $('pos-search').focus(); $('pos-search').select(); },
  F3: () => openCustomerPicker(),
  F4: () => promptQuantity(),
  F6: () => askManualDiscount(),
  F7: () => parkCart(),
  F5: () => doCheckout(),
  F8: () => { if (cartItems.length) clearCart(); },
  F9: () => openParkedCarts()
};

function openOverlay() {
  return [...document.querySelectorAll('.overlay')].find(m => !m.classList.contains('hidden'));
}

document.addEventListener('keydown', e => {
  if (e.key === 'F5' && currentUser && $('view-pos').classList.contains('on')) e.preventDefault();
  const overlay = openOverlay();
  if (e.key === 'Escape') {
    if (overlay?.id === 'modal-pin') return;
    if (overlay) {
      e.preventDefault();
      if (overlay.id === 'modal-receipt') closeReceipt(); else overlay.classList.add('hidden');
    }
    return;
  }
  if (overlay?.id === 'modal-payment') {
    if (e.key === 'F5') { e.preventDefault(); paymentShortcut(); }
    if (e.key === 'F9') { e.preventDefault(); payAllByCard(); }
    return;
  }
  if (overlay?.id === 'modal-receipt') {
    if (e.key === 'Enter') { e.preventDefault(); printReceipt(); }
    return;
  }
  if (overlay || !currentUser || !$('view-pos').classList.contains('on')) return;

  if (TILL_KEYS[e.key]) {
    e.preventDefault();
    TILL_KEYS[e.key]();
    return;
  }
  if (e.altKey && ['1', '2', '3'].includes(e.key)) {
    e.preventDefault();
    switchTillTab(parseInt(e.key, 10));
    return;
  }
  const target = e.target;
  const inSearch = target === $('pos-search');
  const typing = ['INPUT', 'SELECT', 'TEXTAREA'].includes(target.tagName) && !inSearch;
  if (typing) return;
  const searchEmpty = !$('pos-search').value;
  const resultsOpen = !$('search-results').classList.contains('hidden');
  if ((e.key === 'ArrowDown' || e.key === 'ArrowUp') && !resultsOpen && cartItems.length) {
    e.preventDefault();
    const from = selectedLine < 0 ? cartItems.length : selectedLine;
    selectLine(Math.min(cartItems.length - 1, Math.max(0, from + (e.key === 'ArrowDown' ? 1 : -1))));
    return;
  }
  if (searchEmpty && cartItems.length && (e.key === '+' || e.key === '-')) {
    e.preventDefault();
    stepQty(selectedItem().lineId, e.key === '+' ? 1 : -1);
    return;
  }
  if (searchEmpty && cartItems.length && e.key === 'Delete') {
    e.preventDefault();
    removeItem(selectedItem().lineId);
    return;
  }
  // Typing anywhere on the till (or a scanner) goes into the search field.
  if (!inSearch && e.key.length === 1 && !e.ctrlKey && !e.altKey && !e.metaKey) {
    $('pos-search').focus();
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


/* Prints the Kartela on one A4 page: a summary made for paper, not the screen (which would take 4-5 pages). */
const A4_CONTENT_HEIGHT = 1040;   // 277 mm (A4 minus 10 mm margins) at 96 px per inch, a little to spare
const A4_CONTENT_WIDTH = 718;     // 190 mm

function printKartela() {
  if (!productCard || !kartelaAnalysis) return;
  let sheet = $('kr-print');
  if (!sheet) {
    sheet = document.createElement('div');
    sheet.id = 'kr-print';
    document.body.appendChild(sheet);
  }
  sheet.innerHTML = kartelaPrintHtml();
  // Measure off screen at A4 width; a long card is scaled down so it still fits one page.
  sheet.style.zoom = 1;
  sheet.classList.add('measuring');
  const height = sheet.scrollHeight;
  sheet.classList.remove('measuring');
  sheet.style.zoom = height > A4_CONTENT_HEIGHT ? (A4_CONTENT_HEIGHT / height).toFixed(3) : 1;

  document.body.classList.add('print-kartela');
  const done = () => { document.body.classList.remove('print-kartela'); window.removeEventListener('afterprint', done); };
  window.addEventListener('afterprint', done);
  window.print();
}

function kartelaPrintHtml() {
  const c = productCard;
  const p = c.product;
  const a = kartelaAnalysis;
  const unit = esc(unitLabel(p.unit));
  const stock = parseFloat(p.stock);
  const period = t('{days} days', { days: a.days });
  const shop = shopSettings?.name || $('sb-shop-name')?.textContent || '';
  const cell = (label, value, sub = '') => `<div class="kp-cell"><span>${label}</span><strong>${value}</strong>${sub ? `<small>${sub}</small>` : ''}</div>`;
  const table = (header, rows, empty) => `<table><thead><tr>${header.map(h => `<th>${h}</th>`).join('')}</tr></thead>
    <tbody>${rows.length ? rows.map(r => `<tr>${r.map(v => `<td>${v}</td>`).join('')}</tr>`).join('') : `<tr><td colspan="${header.length}" class="kp-empty">${empty}</td></tr>`}</tbody></table>`;
  const section = (title, body) => `<section><h3>${title}</h3>${body}</section>`;
  const status = stock <= 0 ? t('Out of stock') : (p.minStock != null && stock <= parseFloat(p.minStock)) ? t('Below minimum stock') : t('In stock');
  const boxes = c.packages.filter(b => b.active);

  return `
    <header class="kp-head">
      <div>
        <div class="kp-shop">${esc(shop)} · ${t('Product card')}</div>
        <h1>${esc(p.name)}</h1>
        <div class="kp-meta">${esc(p.barcode)} · ${esc(c.category || t('None'))} · ${unit} · ${t('VAT')} ${fmtTax(p.taxRate)} · ${status}${p.active ? '' : ' · ' + t('Inactive')}</div>
      </div>
      <div class="kp-price"><span>${t('Selling price')}</span><strong>${fmtLek(p.price)}</strong>
        ${c.unitPriceLabel ? `<small>${esc(c.unitPriceLabel)} ${fmtLek(c.unitPrice)}</small>` : ''}</div>
    </header>

    <div class="kp-cells">
      ${cell(t('Stock'), `${fmtQty(p.stock, p.unit)} ${unit}`, p.minStock != null ? `${t('Minimum stock')} ${fmtQty(p.minStock, p.unit)}` : '')}
      ${cell(t('Average cost'), fmtLek(p.purchasePrice), `${t('Margin')} ${parseFloat(c.marginPercent).toFixed(1)}%`)}
      ${cell(t('Stock value'), fmtLek(Math.max(0, stock) * parseFloat(p.purchasePrice)), `${t('at selling price')} ${fmtLek(Math.max(0, stock) * parseFloat(p.price))}`)}
      ${cell(t('Days of stock left'), c.sales.daysOfStockLeft == null ? '-' : c.sales.daysOfStockLeft, t('{n} per day', { n: parseFloat(a.averagePerDay).toFixed(1) }))}
      ${cell(t('Sold'), `${fmtQty(a.quantity, p.unit)} ${unit}`, `${period} · ${t('{count} receipts', { count: a.receipts })}`)}
      ${cell(t('Revenue'), fmtLek(a.revenue), period)}
      ${cell(t('Profit'), fmtLek(a.profit), `${t('Margin')} ${parseFloat(a.marginPercent).toFixed(1)}%`)}
      ${cell(t('Last sale'), c.sales.lastSaleAt ? fmtDate(c.sales.lastSaleAt) : t('Never sold'),
        a.lastPurchase ? `${t('Last purchase')} ${formatDay(a.lastPurchase.date)}` : '')}
    </div>

    <div class="kp-charts">
      <div><h3>${t('Quantity sold per day')} · ${period}</h3>
        ${svgBars(a.daily.map(d => ({ value: parseFloat(d.quantity), title: '' })), 60)}${axisDays(a.daily.map(d => d.day))}</div>
      <div><h3>${t('Stock at the end of each day')}</h3>
        ${svgLine(a.stock.map(s => ({ value: parseFloat(s.stock), title: '' })), p.minStock != null ? parseFloat(p.minStock) : null, 60)}${axisDays(a.stock.map(s => s.day))}</div>
    </div>

    <div class="kp-cols">
      ${section(t('Boxes'), table([t('Box'), t('Barcode'), t('Pieces'), t('Box price'), t('Per piece')],
        boxes.map(b => { const price = cardBoxPrice(b); return [esc(b.name), esc(b.barcode), b.pieces, fmtLek(price), fmtLek(price / b.pieces)]; }),
        p.unit === 'pcs' ? t('No boxes yet.') : '-'))}
      ${section(t('Suppliers'), table([t('Supplier'), t('Last purchase'), t('Last price (piece)'), t('Total bought')],
        c.suppliers.slice(0, 4).map(s => [esc(s.supplier), formatDay(s.lastPurchase), fmtLek(s.lastPurchasePrice), fmtQty(s.totalQuantity, p.unit)]),
        t('Not bought on a purchase invoice yet.')))}
      ${section(t('Price history'), table([t('Date'), t('Selling price'), t('Purchase price'), t('Source')],
        c.priceHistory.filter(ch => !ch.packageId).slice(0, 6).map(ch => [formatDay(ch.changedAt.slice(0, 10)),
          ch.newPrice == null ? '' : `${ch.oldPrice == null ? '' : fmtLek(ch.oldPrice) + ' → '}${fmtLek(ch.newPrice)}`,
          ch.newPurchasePrice == null ? '' : `${ch.oldPurchasePrice == null ? '' : fmtLek(ch.oldPurchasePrice) + ' → '}${fmtLek(ch.newPurchasePrice)}`,
          esc(t(PRICE_SOURCES[ch.source] || ch.source))]), t('No price changes yet.')))}
      ${section(`${t('Top customers')} · ${period}`, table([t('Customer'), t('Quantity'), t('Revenue')],
        a.topCustomers.slice(0, 4).map(cu => [esc(cu.name), fmtQty(cu.quantity, p.unit), fmtLek(cu.revenue)]), '-'))}
    </div>

    ${section(t('Stock movements'), table([t('Date'), t('Type'), t('Document'), t('Quantity change'), t('Stock after'), t('User')],
      kartelaMoves.slice(0, 12).map(m => [fmtDate(m.at), esc(t(MOVEMENT_LABELS[m.type] || m.type)),
        esc(m.type === 'ADJUSTMENT' ? t(ADJUST_REASONS[m.reference] || m.reference) : (m.reference || '')),
        `${parseFloat(m.change) > 0 ? '+' : ''}${fmtQty(m.change, p.unit)}`, fmtQty(m.stockAfter, p.unit), esc(m.cashier || '')]),
      t('No stock movements yet.')))}

    <footer class="kp-foot">${t('Printed')} ${fmtDate(new Date().toISOString())} · ${esc(currentUser?.fullName || '')}</footer>`;
}

/*  Shpenzimet: rent, electricity, salaries...  */
const EXPENSE_METHODS = { CASH: 'Cash from the till', BANK: 'Bank', CARD: 'Card' };
let expenseCategories = [];
let expenseData = null;

async function loadExpenseCategories(all = false) {
  const res = await req('GET', `/expenses/categories?activeOnly=${!all}`);
  return res.data || [];
}

async function initExpenses() {
  if (!$('ex-month').value) $('ex-month').value = isoDay(new Date()).slice(0, 7);
  try {
    expenseCategories = await loadExpenseCategories(true);
    const chosen = $('ex-category').value;
    $('ex-category').innerHTML = `<option value="">${t('All categories')}</option>`
      + expenseCategories.map(c => `<option value="${c.id}">${esc(c.name)}${c.active ? '' : ' (' + t('Inactive') + ')'}</option>`).join('');
    $('ex-category').value = chosen;
  } catch (e) { toast(e.message, 'error'); }
  loadExpenses();
}

function expenseMonthRange() {
  const [y, m] = ($('ex-month').value || isoDay(new Date()).slice(0, 7)).split('-').map(Number);
  const last = new Date(y, m, 0).getDate();
  return { from: `${y}-${String(m).padStart(2, '0')}-01`, to: `${y}-${String(m).padStart(2, '0')}-${String(last).padStart(2, '0')}` };
}

function expenseParams() {
  const range = expenseMonthRange();
  const params = new URLSearchParams(range);
  if ($('ex-category').value) params.set('categoryId', $('ex-category').value);
  return params;
}

async function loadExpenses() {
  try {
    expenseData = (await req('GET', '/expenses?' + expenseParams())).data;
    renderExpenses();
  } catch (e) { toast(e.message, 'error'); }
}

function renderExpenses() {
  const d = expenseData;
  if (!d) return;
  const card = (label, amount, sub, tone) => `<div class="jr-card ${tone}"><span>${label}</span><strong>${fmtLek(amount)}</strong><small>${sub}</small></div>`;
  const active = d.expenses.filter(e => !e.cancelled);
  $('ex-summary').innerHTML = card(t('Total expenses'), d.total, t('{count} expenses', { count: active.length }), 'bad')
    + card(t('Cash from the till'), d.cash, t('Left the till during a shift'), '')
    + card(t('Bank'), d.bank, t('Transfers and direct debits'), 'info')
    + card(t('Card'), d.card, t('Paid with a card'), 'info');

  const max = Math.max(1, ...d.byCategory.map(c => parseFloat(c.amount)));
  $('ex-bars').innerHTML = d.byCategory.length ? `<div class="ex-bars">${d.byCategory.map(c => `
      <div class="ex-bar">
        <div class="ex-bar-head"><span>${esc(c.category)}</span><strong>${fmtLek(c.amount)}</strong></div>
        <div class="ex-bar-track"><div style="width:${(parseFloat(c.amount) / max * 100).toFixed(1)}%"></div></div>
        <small>${t('{count} expenses', { count: c.count })} · ${(parseFloat(c.amount) / parseFloat(d.total) * 100).toFixed(1)}%</small>
      </div>`).join('')}</div>` : `<p class="no-data">${t('No expenses in this month.')}</p>`;

  const showCancelled = $('ex-cancelled').checked;
  const rows = d.expenses.filter(e => showCancelled || !e.cancelled);
  $('ex-count').textContent = t('{count} expenses', { count: active.length });
  $('ex-table').innerHTML = krTable([t('Date'), t('Category'), t('Paid to'), t('Note'), t('Paid by'), t('User'), t('Amount'), ''],
    rows.map(e => [
      formatDay(e.paidOn),
      `<strong>${esc(e.category)}</strong>`,
      esc(e.paidTo || ''),
      esc(e.note || ''),
      `<span class="kr-badge ${e.method === 'CASH' ? 'warn' : 'info'}">${t(EXPENSE_METHODS[e.method])}</span>`,
      esc(e.user || ''),
      e.cancelled ? `<s class="t-muted">${fmtLek(e.amount)}</s><div class="wh-sub">${t('Cancelled by {name}', { name: esc(e.cancelledBy || '') })}</div>`
        : `<strong>${fmtLek(e.amount)}</strong>`,
      e.cancelled ? '' : `<button class="btn btn-ghost btn-sm kr-danger-text" onclick="cancelExpense(${e.id})">${t('Cancel')}</button>`
    ]), t('No expenses in this month.'));
}

async function openExpenseModal() {
  try {
    const categories = await loadExpenseCategories();
    $('exm-category').innerHTML = categories.map(c => `<option value="${c.id}">${esc(c.name)}</option>`).join('');
    if ($('ex-category').value && categories.some(c => String(c.id) === $('ex-category').value)) $('exm-category').value = $('ex-category').value;
  } catch (e) { toast(e.message, 'error'); return; }
  $('exm-amount').value = '';
  $('exm-method').value = 'BANK';
  $('exm-date').value = isoDay(new Date());
  $('exm-date').max = isoDay(new Date());
  $('exm-paidto').value = '';
  $('exm-note').value = '';
  onExpenseMethodChange();
  openModal('modal-expense');
  setTimeout(() => $('exm-amount').focus(), 50);
}

function onExpenseMethodChange() {
  const cash = $('exm-method').value === 'CASH';
  $('exm-date').disabled = cash;
  if (cash) $('exm-date').value = isoDay(new Date());
  $('exm-hint').textContent = cash ? t('The money is taken out of the till of your open shift, today.') : '';
}

async function saveExpense() {
  const body = {
    categoryId: Number($('exm-category').value),
    amount: parseFloat($('exm-amount').value || '0'),
    method: $('exm-method').value,
    paidOn: $('exm-date').value || null,
    paidTo: $('exm-paidto').value.trim(),
    note: $('exm-note').value.trim()
  };
  try {
    await req('POST', '/expenses', body);
    closeModal('modal-expense');
    toast(t('Expense saved'), 'success');
    const month = body.paidOn ? body.paidOn.slice(0, 7) : isoDay(new Date()).slice(0, 7);
    if (month !== $('ex-month').value) $('ex-month').value = month;
    loadExpenses();
  } catch (e) { toast(e.message, 'error'); }
}

async function cancelExpense(id) {
  const e = expenseData.expenses.find(x => x.id === id);
  const text = e.method === 'CASH' ? t('Cancel this expense? The money goes back into the till it was paid from.') : t('Cancel this expense?');
  if (!confirm(`${text}\n\n${e.category}: ${fmtLek(e.amount)}`)) return;
  try {
    await req('POST', `/expenses/${id}/cancel`);
    toast(t('Expense cancelled'), 'success');
    loadExpenses();
  } catch (err) { toast(err.message, 'error'); }
}

function exportExpenses() {
  downloadFile('/expenses/export.xlsx?' + expenseParams());
}

async function openExpenseCategories() {
  $('exc-new').value = '';
  await renderExpenseCategories();
  openModal('modal-expense-categories');
}

async function renderExpenseCategories() {
  try {
    const all = await loadExpenseCategories(true);
    $('exc-list').innerHTML = all.map(c => `
      <div class="exc-row ${c.active ? '' : 'off'}">
        <input class="input" value="${esc(c.name)}" onchange="saveExpenseCategory(${c.id}, this.value, ${c.active})" />
        <small>${t('{count} expenses', { count: c.expenses })}</small>
        <button class="btn btn-secondary btn-sm" onclick="saveExpenseCategory(${c.id}, null, ${!c.active})">${c.active ? t('Deactivate') : t('Reactivate')}</button>
      </div>`).join('');
  } catch (e) { toast(e.message, 'error'); }
}

async function addExpenseCategory() {
  const name = $('exc-new').value.trim();
  if (!name) return;
  try {
    await req('POST', '/expenses/categories', { name });
    $('exc-new').value = '';
    await renderExpenseCategories();
    initExpenses();
  } catch (e) { toast(e.message, 'error'); }
}

async function saveExpenseCategory(id, name, active) {
  const current = (await loadExpenseCategories(true)).find(c => c.id === id);
  try {
    await req('PUT', `/expenses/categories/${id}`, { name: name ?? current.name, active });
    await renderExpenseCategories();
    initExpenses();
  } catch (e) { toast(e.message, 'error'); renderExpenseCategories(); }
}

/* Till: an expense paid from the drawer is a cash out with a category. */
async function onCashTypeChange() {
  const expense = $('cash-type').value === 'EXPENSE';
  $('cash-category-wrap').classList.toggle('hidden', !expense);
  if (expense && !$('cash-category').options.length) {
    try {
      $('cash-category').innerHTML = (await loadExpenseCategories()).map(c => `<option value="${c.id}">${esc(c.name)}</option>`).join('');
    } catch (e) { toast(e.message, 'error'); }
  }
}

/*  Kontabiliteti: one month for the accountant  */
const LOSS_LABELS = { DAMAGED: 'Damaged', EXPIRED: 'Expired', LOST: 'Lost / stolen', INTERNAL_USE: 'Internal use', OTHER: 'Other',
  COUNT: 'Stock count differences' };
let accountingData = null;
let accountingTab = 'pl';
let accountingDocuments = false;
/* Amounts of the books: always LEK, two decimals, thousands apart. */
const acNum = n => (parseFloat(n) || 0).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).replace(/,/g, ' ');

function initAccounting() {
  if (!$('ac-month').value) $('ac-month').value = isoDay(new Date()).slice(0, 7);
  $('ac-month').max = isoDay(new Date()).slice(0, 7);
  loadAccounting();
}

async function loadAccounting() {
  $('ac-body').innerHTML = `<div class="kr-loading">${t('Loading...')}</div>`;
  try {
    accountingData = (await req('GET', `/accounting?month=${encodeURIComponent($('ac-month').value)}`)).data;
    renderAccounting();
  } catch (e) {
    $('ac-body').innerHTML = `<div class="kr-empty"><strong>${esc(e.message)}</strong></div>`;
  }
}

function showAccountingTab(tab) {
  accountingTab = tab;
  document.querySelectorAll('.ac-tabs .btn').forEach(b => b.classList.toggle('on', b.dataset.tab === tab));
  renderAccounting();
}

function renderAccounting() {
  if (!accountingData) return;
  if (accountingTab === 'pl') renderProfitAndLoss();
  if (accountingTab === 'sales') renderSalesBook();
  if (accountingTab === 'purchases') renderPurchaseBook();
  if (accountingTab === 'stock') renderStockValue();
}

function exportAccounting() {
  downloadFile(`/accounting/export.xlsx?month=${encodeURIComponent($('ac-month').value)}`);
}

function printAccounting() {
  document.body.classList.add('print-accounting');
  const done = () => { document.body.classList.remove('print-accounting'); window.removeEventListener('afterprint', done); };
  window.addEventListener('afterprint', done);
  window.print();
}

function monthName(month) {
  return formatMonthYear(new Date(month + '-15T12:00:00'));
}

/* Change against last month: arrow and percent, green when it is good news. */
function changeBadge(now, before, higherIsGood = true) {
  const a = parseFloat(now), b = parseFloat(before);
  if (!b) return '';
  const pct = (a - b) / Math.abs(b) * 100;
  if (Math.abs(pct) < 0.05) return `<span class="ac-change">0%</span>`;
  const good = (pct > 0) === higherIsGood;
  return `<span class="ac-change ${good ? 'up' : 'down'}">${pct > 0 ? '▲' : '▼'} ${Math.abs(pct).toFixed(1)}%</span>`;
}

function renderProfitAndLoss() {
  const { current: c, previous: p } = accountingData.profitAndLoss;
  const kpi = (label, value, sub, tone, badge = '') => `<div class="jr-card ${tone}"><span>${label}</span><strong>${fmtLek(value)}</strong><small>${sub} ${badge}</small></div>`;
  const prevName = monthName(p.from.slice(0, 7));
  const amountOf = (lines, key) => (lines.find(l => l.key === key) || {}).amount;
  const row = (label, now, before, cls = '', sign = '', higherIsGood = true) => `<tr class="${cls}">
      <td>${label}</td>
      <td class="num">${now == null ? '' : (parseFloat(now) ? sign : '') + fmtLek(now)}</td>
      <td class="num t-muted">${before == null ? '-' : (parseFloat(before) ? sign : '') + fmtLek(before)}</td>
      <td class="num">${now == null || before == null ? '' : changeBadge(now, before, higherIsGood)}</td>
    </tr>`;
  const stock = accountingData;
  const net = parseFloat(c.netProfit);

  $('ac-body').innerHTML = `
    <div class="jr-summary">
      ${kpi(t('Revenue without VAT'), c.revenueWithoutVat, t('{count} receipts', { count: c.salesCount }), 'info', changeBadge(c.revenueWithoutVat, p.revenueWithoutVat))}
      ${kpi(t('Gross profit'), c.grossProfit, `${t('Margin')} ${parseFloat(c.grossMargin).toFixed(1)}%`, 'good', changeBadge(c.grossProfit, p.grossProfit))}
      ${kpi(t('Expenses and stock losses'), parseFloat(c.expenseTotal) + parseFloat(c.stockLossTotal), t('{count} expenses', { count: c.expenses.reduce((s, l) => s + l.count, 0) }), 'bad', changeBadge(parseFloat(c.expenseTotal) + parseFloat(c.stockLossTotal), parseFloat(p.expenseTotal) + parseFloat(p.stockLossTotal), false))}
      ${kpi(t('Net profit'), c.netProfit, `${t('Margin')} ${parseFloat(c.netMargin).toFixed(1)}%`, net < 0 ? 'bad' : 'good', changeBadge(c.netProfit, p.netProfit))}
    </div>
    <div class="ac-grid">
      <section class="kr-card ac-statement">
        <header><h2>${t('Profit and loss')} · ${esc(monthName(accountingData.month))}</h2><p>${t('Without VAT; the cost of goods is the average cost at the moment of each sale')}</p></header>
        <table class="ac-table">
          <thead><tr><th></th><th class="num">${esc(monthName(accountingData.month))}</th><th class="num">${esc(prevName)}</th><th></th></tr></thead>
          <tbody>
            ${row(t('Sales with VAT'), c.grossSales, p.grossSales)}
            ${row(t('Refunds'), c.refunds, p.refunds, '', '−', false)}
            ${row(t('VAT'), c.vat, p.vat, '', '−', false)}
            ${row(t('Revenue without VAT'), c.revenueWithoutVat, p.revenueWithoutVat, 'ac-sum')}
            ${row(t('Cost of goods sold'), c.costOfGoods, p.costOfGoods, '', '−', false)}
            ${row(`${t('Gross profit')} <small>${parseFloat(c.grossMargin).toFixed(1)}%</small>`, c.grossProfit, p.grossProfit, 'ac-sum')}
            ${c.stockLosses.map(l => row(`<span class="ac-sub">${t(LOSS_LABELS[l.key] || l.key)} <small>(${l.count})</small></span>`, l.amount, amountOf(p.stockLosses, l.key), '', '−', false)).join('')}
            ${row(t('Stock losses'), c.stockLossTotal, p.stockLossTotal, 'ac-group', '−', false)}
            ${c.expenses.map(l => row(`<span class="ac-sub">${esc(l.label)} <small>(${l.count})</small></span>`, l.amount, amountOf(p.expenses, l.key), '', '−', false)).join('')}
            ${row(t('Expenses'), c.expenseTotal, p.expenseTotal, 'ac-group', '−', false)}
            ${row(`${t('Net profit')} <small>${parseFloat(c.netMargin).toFixed(1)}%</small>`, c.netProfit, p.netProfit, `ac-total ${net < 0 ? 'neg' : ''}`)}
          </tbody>
        </table>
      </section>
      <section class="kr-card">
        <header><h2>${t('Stock movement of the month')}</h2><p>${t('Without VAT, at cost: for the accountant to check the cost of goods')}</p></header>
        <dl class="kr-dl">
          <dt>${t('Stock at the start')} (${formatDay(stock.stockAtStart.at)})</dt><dd>${fmtLek(stock.stockAtStart.valueWithoutVat)}</dd>
          <dt>+ ${t('Purchases')}</dt><dd>${fmtLek(stock.purchases.base)}</dd>
          <dt>+ ${t('Opening stock of new products')}</dt><dd>${fmtLek(stock.openingStock)}</dd>
          <dt>− ${t('Stock at the end')} (${formatDay(stock.stockAtEnd.at)})</dt><dd>${fmtLek(stock.stockAtEnd.valueWithoutVat)}</dd>
          <dt><strong>= ${t('Goods used')}</strong></dt><dd><strong>${fmtLek(parseFloat(stock.stockAtStart.valueWithoutVat) + parseFloat(stock.purchases.base) + parseFloat(stock.openingStock) - parseFloat(stock.stockAtEnd.valueWithoutVat))}</strong></dd>
          <dt class="t-muted">${t('Cost of goods sold + stock losses')}</dt><dd class="t-muted">${fmtLek(parseFloat(c.costOfGoods) + parseFloat(c.stockLossTotal))}</dd>
        </dl>
        <p class="input-hint">${t('The two numbers are close when every delivery is entered as a purchase invoice. A big difference means goods came in or went out without being recorded.')}</p>
      </section>
    </div>`;
}

function rateHeaders(rates) {
  return rates.map(r => `<th class="num">${t('Without VAT')} ${parseFloat(r)}%</th><th class="num">${t('VAT')} ${parseFloat(r)}%</th>`).join('')
    + `<th class="num">${t('Without VAT')}</th><th class="num">${t('VAT')}</th><th class="num">${t('Total')}</th>`;
}

function rateCells(rates, base, vat, total) {
  return rates.map(r => `<td class="num">${acNum(r.base)}</td><td class="num">${acNum(r.vat)}</td>`).join('')
    + `<td class="num">${acNum(base)}</td><td class="num">${acNum(vat)}</td><td class="num"><strong>${acNum(total)}</strong></td>`;
}

function bookTotals(book) {
  return `<div class="jr-summary">${book.totals.map(r => `<div class="jr-card info"><span>${t('VAT')} ${parseFloat(r.rate)}%</span>
      <strong>${fmtLek(r.total)}</strong><small>${t('Without VAT')} ${acNum(r.base)} · ${t('VAT')} ${acNum(r.vat)}</small></div>`).join('')}
    <div class="jr-card good"><span>${t('Total')}</span><strong>${fmtLek(book.total)}</strong><small>${t('Without VAT')} ${acNum(book.base)} · ${t('VAT')} ${acNum(book.vat)}</small></div></div>`;
}

function renderSalesBook() {
  const b = accountingData.sales;
  const toggle = `<div class="seg"><button class="btn btn-secondary btn-sm ${accountingDocuments ? '' : 'on'}" onclick="accountingDocuments = false; renderSalesBook()">${t('By day')}</button>
    <button class="btn btn-secondary btn-sm ${accountingDocuments ? 'on' : ''}" onclick="accountingDocuments = true; renderSalesBook()">${t('By invoice')}</button></div>`;
  let table;
  if (!accountingDocuments) {
    table = `<table><thead><tr><th>${t('Date')}</th><th class="num">${t('Receipts')}</th><th>${t('Invoice numbers')}</th><th class="num">${t('Refunds')}</th>${rateHeaders(b.rates)}</tr></thead>
      <tbody>${b.days.length ? b.days.map(d => `<tr><td>${formatDay(d.day)}</td><td class="num">${d.receipts}</td>
          <td class="td-m">${d.firstInvoice ? esc(d.firstInvoice) + (d.lastInvoice !== d.firstInvoice ? ' – ' + esc(d.lastInvoice) : '') : ''}</td>
          <td class="num">${d.refunds || ''}</td>${rateCells(d.rates, d.base, d.vat, d.total)}</tr>`).join('')
        : `<tr><td colspan="${7 + b.rates.length * 2}" class="no-data">${t('No sales in this month.')}</td></tr>`}</tbody>
      <tfoot><tr><td>${t('Total')}</td><td class="num">${b.days.reduce((s, d) => s + d.receipts, 0)}</td><td></td>
        <td class="num">${b.days.reduce((s, d) => s + d.refunds, 0) || ''}</td>${rateCells(b.totals, b.base, b.vat, b.total)}</tr></tfoot></table>`;
  } else {
    const docs = b.documents;
    table = `<table><thead><tr><th>${t('Date')}</th><th>${t('Document')}</th><th>${t('Customer')}</th>${rateHeaders(b.rates)}</tr></thead>
      <tbody>${docs.slice(0, 1000).map(d => `<tr class="${d.kind === 'REFUND' ? 'ac-refund' : ''}"><td>${fmtDate(d.at)}</td>
          <td class="td-m">${esc(d.number || '')}${d.kind === 'REFUND' ? ` <span class="kr-badge bad">${t('Refund')}</span> <small>${t('for invoice')} ${esc(d.forInvoice || '')}</small>` : ''}</td>
          <td>${esc(d.customer || '')}</td>${rateCells(d.rates, d.base, d.vat, d.total)}</tr>`).join('')
        || `<tr><td colspan="${6 + b.rates.length * 2}" class="no-data">${t('No sales in this month.')}</td></tr>`}</tbody></table>
      ${docs.length > 1000 ? `<p class="input-hint">${t('The first {count} are shown; the Excel file has all of them.', { count: 1000 })}</p>` : ''}`;
  }
  $('ac-body').innerHTML = bookTotals(b) + `<section class="kr-card"><header class="ac-head"><div><h2>${t('Sales book')} · ${esc(monthName(accountingData.month))}</h2>
      <p>${t('Receipts minus refunds, by VAT rate')}</p></div>${toggle}</header><div class="tbl-wrap ac-book">${table}</div></section>`;
}

function renderPurchaseBook() {
  const b = accountingData.purchases;
  $('ac-body').innerHTML = bookTotals(b) + `<section class="kr-card"><header><h2>${t('Purchase book')} · ${esc(monthName(accountingData.month))}</h2>
      <p>${t('Purchase invoices by their date, by VAT rate')}</p></header>
    <div class="tbl-wrap ac-book"><table><thead><tr><th>${t('Date')}</th><th>${t('Invoice')}</th><th>${t('Supplier')}</th><th>NIPT</th>${rateHeaders(b.rates)}</tr></thead>
      <tbody>${b.invoices.length ? b.invoices.map(r => `<tr class="${r.kind === 'CREDIT' ? 'ac-refund' : ''}"><td>${formatDay(r.date)}</td><td class="td-m">${esc(r.invoice)}${r.kind === 'CREDIT' ? ` <span class="kr-badge bad">${t('Credit note')}</span>` : ''}</td><td>${esc(r.supplier || '')}</td>
          <td class="td-m">${r.taxNumber ? esc(r.taxNumber) : `<span class="t-muted" title="${esc(t('Add the NIPT of the supplier in Suppliers'))}">-</span>`}</td>${rateCells(r.rates, r.base, r.vat, r.total)}</tr>`).join('')
        : `<tr><td colspan="${7 + b.rates.length * 2}" class="no-data">${t('No purchase invoices in this month.')}</td></tr>`}</tbody>
      <tfoot><tr><td>${t('Total')}</td><td class="num">${b.invoices.length}</td><td></td><td></td>${rateCells(b.totals, b.base, b.vat, b.total)}</tr></tfoot></table></div></section>`;
}

let accountingStockFilter = '';
function renderStockValue() {
  const s = accountingData.stockAtEnd;
  const a = accountingData.stockAtStart;
  const max = Math.max(1, ...s.categories.map(c => parseFloat(c.valueWithoutVat)));
  const filter = accountingStockFilter.toLowerCase();
  const products = s.products.filter(p => !filter || p.name.toLowerCase().includes(filter) || p.barcode.includes(filter))
    .sort((x, y) => parseFloat(y.value) - parseFloat(x.value));
  $('ac-body').innerHTML = `
    <div class="jr-summary">
      <div class="jr-card"><span>${t('Stock at the start')} · ${formatDay(a.at)}</span><strong>${fmtLek(a.valueWithoutVat)}</strong><small>${t('Without VAT')} · ${t('with VAT')} ${acNum(a.value)}</small></div>
      <div class="jr-card info"><span>${t('Stock at the end')} · ${formatDay(s.at)}</span><strong>${fmtLek(s.valueWithoutVat)}</strong><small>${t('Without VAT')} · ${t('with VAT')} ${acNum(s.value)}</small></div>
      <div class="jr-card"><span>${t('Products in stock')}</span><strong>${s.products.length}</strong><small>${t('{count} categories', { count: s.categories.length })}</small></div>
    </div>
    <div class="ex-grid">
      <section class="kr-card"><header><h2>${t('By category')}</h2><p>${t('Without VAT')}</p></header>
        <div class="ex-bars">${s.categories.map(c => `<div class="ex-bar"><div class="ex-bar-head"><span>${esc(c.category)}</span><strong>${fmtLek(c.valueWithoutVat)}</strong></div>
          <div class="ex-bar-track ac-track"><div style="width:${(parseFloat(c.valueWithoutVat) / max * 100).toFixed(1)}%"></div></div>
          <small>${t('{count} products', { count: c.products })}</small></div>`).join('') || `<p class="no-data">-</p>`}</div>
      </section>
      <section class="kr-card"><header class="ac-head"><div><h2>${t('Products')}</h2><p>${t('Stock at the end of the month at the cost of that day')}</p></div>
          <input class="input ac-filter" placeholder="${esc(t('Search'))}" value="${esc(accountingStockFilter)}" oninput="accountingStockFilter = this.value; clearTimeout(window._acTmr); window._acTmr = setTimeout(() => { renderStockValue(); const i = document.querySelector('.ac-filter'); i.focus(); i.setSelectionRange(i.value.length, i.value.length); }, 250)" /></header>
        ${krTable([t('Product'), t('Category'), t('Quantity'), t('Cost'), t('Value'), t('Without VAT')], products.slice(0, 300).map(p => [
          `<a class="ac-link" onclick="openKartela(${p.id})">${esc(p.name)}</a><div class="wh-sub td-m">${esc(p.barcode)}</div>`, esc(p.category),
          `${fmtQty(p.stock, p.unit)} ${esc(unitLabel(p.unit))}`, fmtLek(p.cost), fmtLek(p.value), fmtLek(p.valueWithoutVat)]), t('No products in stock.'))}
        ${products.length > 300 ? `<p class="input-hint">${t('The first {count} are shown; the Excel file has all of them.', { count: 300 })}</p>` : ''}
      </section>
    </div>`;
}

/*  Porositë te furnitorët: orders sent to suppliers by WhatsApp or Viber  */
const ORDER_STATUS = { OPEN: ['Being prepared', 'warn'], SENT: ['Sent', 'info'], RECEIVED: ['Received', 'good'], CANCELLED: ['Cancelled', ''] };
let orderFilter = 'ACTIVE';
let ordersCache = [];
let lastReorderGroups = [];
let orderDraft = null;

function setOrderFilter(status) {
  orderFilter = status;
  document.querySelectorAll('#ord-filter .btn').forEach(b => b.classList.toggle('on', b.dataset.s === status));
  loadOrders();
}

async function loadOrders() {
  const box = $('orders-list');
  box.innerHTML = `<p class="no-data">${t('Loading...')}</p>`;
  try {
    ordersCache = (await req('GET', `/purchase-orders?status=${encodeURIComponent(orderFilter)}`)).data || [];
    box.innerHTML = ordersCache.length ? ordersCache.map(o => {
      const [label, tone] = ORDER_STATUS[o.status];
      const open = o.status === 'OPEN' || o.status === 'SENT';
      return `<div class="ord-card">
        <div class="ord-card-main" onclick="openOrder(${o.id})">
          <div><strong>${esc(o.supplierName)}</strong> <span class="kr-badge ${tone}">${t(label)}</span></div>
          <div class="wh-sub">${esc(o.number)} · ${linesText(o.lineCount)} · ${fmtDate(o.createdAt)}${o.sentAt ? ` · ${t('sent')} ${fmtDate(o.sentAt)}` : ''}${o.closedAt ? ` · ${esc(o.closedBy || '')} ${fmtDate(o.closedAt)}` : ''}</div>
          ${o.note ? `<div class="wh-sub">${esc(o.note)}</div>` : ''}
        </div>
        <strong class="ord-card-total">${fmtLek(o.total)}</strong>
        <div class="ord-card-actions">
          <button class="btn btn-secondary btn-sm" onclick="openOrder(${o.id})">${t('Open the order')}</button>
          ${open ? `<button class="btn btn-secondary btn-sm" onclick="sendOrderBy(${o.id}, 'whatsapp')">WhatsApp</button>
            <button class="btn btn-primary btn-sm" onclick="closeOrder(${o.id}, 'received')">${t('Mark received')}</button>` : ''}
        </div>
      </div>`;
    }).join('') : `<p class="no-data">${orderFilter === 'ACTIVE' ? t('No orders waiting for goods.') : t('No orders.')}</p>`;
  } catch (e) { box.innerHTML = `<p class="no-data" style="color:var(--red)">${esc(e.message)}</p>`; }
}

/* The reorder list of one supplier becomes a new order (not saved until Save or Send). */
async function createOrderFromReorder(index) {
  const g = lastReorderGroups[index];
  const lines = g.lines.filter(l => parseFloat(l.suggestedQuantity) > 0);
  if (!lines.length) { toast(t('Everything in this group is already on order.'), 'info'); return; }
  orderDraft = { id: null, status: 'OPEN', supplierId: g.supplierId, note: '', lines: [] };
  for (const l of lines) {
    orderDraft.lines.push({ productId: l.productId, productName: l.productName, barcode: l.barcode, unit: l.unit, stock: l.stock,
      packageId: null, quantity: parseFloat(l.suggestedQuantity), piecePrice: parseFloat(l.lastPurchasePrice), price: parseFloat(l.lastPurchasePrice), boxes: null });
  }
  await showOrderModal();
}

async function openOrder(id) {
  try {
    if (!id) {
      orderDraft = { id: null, status: 'OPEN', supplierId: null, note: '', lines: [] };
    } else {
      const o = (await req('GET', `/purchase-orders/${id}`)).data;
      orderDraft = { id: o.id, number: o.number, status: o.status, supplierId: o.supplierId, note: o.note || '', createdAt: o.createdAt,
        lines: o.lines.map(l => ({ productId: l.productId, productName: l.productName, barcode: l.barcode, unit: l.unit, stock: l.stock,
          packageId: l.packageId, quantity: parseFloat(l.quantity), price: parseFloat(l.price),
          piecePrice: l.packageId ? parseFloat(l.price) / l.pieces : parseFloat(l.price), boxes: null })) };
    }
    await showOrderModal();
  } catch (e) { toast(e.message, 'error'); }
}

async function showOrderModal() {
  try {
    if (!allSuppliers.length) allSuppliers = (await req('GET', '/suppliers')).data || [];
    await Promise.all(orderDraft.lines.map(loadLineBoxes));
  } catch (e) { toast(e.message, 'error'); }
  const editable = orderDraft.status === 'OPEN' || orderDraft.status === 'SENT';
  $('ord-supplier').innerHTML = `<option value="">${t('Choose the supplier')}</option>` + allSuppliers
    .filter(s => s.active || s.id === orderDraft.supplierId).map(s => `<option value="${s.id}">${esc(s.name)}</option>`).join('');
  $('ord-supplier').value = orderDraft.supplierId || '';
  $('ord-note').value = orderDraft.note || '';
  $('ord-supplier').disabled = !editable;
  $('ord-note').disabled = !editable;
  document.querySelector('.ord-search').classList.toggle('hidden', !editable);
  renderOrderLines();
  openModal('modal-order');
  if (editable) setTimeout(() => $('ord-search').focus(), 60);
}

async function loadLineBoxes(line) {
  if (line.boxes) return;
  line.boxes = line.unit === 'pcs' ? ((await req('GET', `/warehouse/products/${line.productId}/packages`)).data || []).filter(b => b.active || b.id === line.packageId) : [];
}

function renderOrderLines() {
  const d = orderDraft;
  const editable = d.status === 'OPEN' || d.status === 'SENT';
  const [label, tone] = ORDER_STATUS[d.status];
  $('ord-title').innerHTML = d.id ? `${t('Order')} ${esc(d.number)} <span class="kr-badge ${tone}">${t(label)}</span>` : t('New order');
  const dis = editable ? '' : 'disabled';
  $('ord-lines').innerHTML = d.lines.length ? d.lines.map((l, i) => {
    const box = l.boxes?.find(b => b.id === l.packageId);
    return `<tr>
      <td><strong>${esc(l.productName)}</strong><div class="wh-sub td-m">${esc(l.barcode)}</div></td>
      <td class="td-m">${fmtQty(l.stock, l.unit)}</td>
      <td>${l.boxes && l.boxes.length ? `<select class="input ord-in" ${dis} onchange="setOrderBox(${i}, this.value)">
          <option value="">${esc(unitLabel(l.unit))}</option>${l.boxes.map(b => `<option value="${b.id}" ${b.id === l.packageId ? 'selected' : ''}>${esc(b.name)} (${b.pieces})</option>`).join('')}
        </select>` : esc(unitLabel(l.unit))}</td>
      <td><input class="input ord-in ord-qty" type="number" min="0" step="${l.unit === 'kg' && !box ? '0.001' : '1'}" value="${l.quantity}" ${dis}
          oninput="orderDraft.lines[${i}].quantity = parseFloat(this.value) || 0; renderOrderTotal()" />
        ${box ? `<div class="wh-sub">= ${fmtQty(l.quantity * box.pieces, 'pcs')} ${esc(unitLabel('pcs'))}</div>` : ''}</td>
      <td><input class="input ord-in ord-price" type="number" min="0" step="0.01" value="${l.price.toFixed(2)}" ${dis}
          oninput="orderDraft.lines[${i}].price = parseFloat(this.value) || 0; renderOrderTotal()" /></td>
      <td class="td-p" id="ord-line-total-${i}">${fmtLek(l.quantity * l.price)}</td>
      <td>${editable ? `<button class="btn btn-ghost btn-sm" onclick="orderDraft.lines.splice(${i}, 1); renderOrderLines()">x</button>` : ''}</td>
    </tr>`;
  }).join('') : `<tr><td colspan="7" class="no-data">${t('Search a product above to add it.')}</td></tr>`;
  renderOrderTotal();
  renderOrderActions();
}

function renderOrderTotal() {
  orderDraft.lines.forEach((l, i) => { const cell = $('ord-line-total-' + i); if (cell) cell.textContent = fmtLek(l.quantity * l.price); });
  const total = orderDraft.lines.reduce((s, l) => s + l.quantity * l.price, 0);
  $('ord-total').innerHTML = `${linesText(orderDraft.lines.length)} · ${t('Estimated total')} <strong>${fmtLek(total)}</strong>`;
}

function setOrderBox(index, value) {
  const l = orderDraft.lines[index];
  const box = l.boxes.find(b => String(b.id) === value);
  if (box) {
    if (!l.packageId) l.quantity = Math.max(1, Math.ceil(l.quantity / box.pieces));
    l.packageId = box.id;
    l.price = Math.round(l.piecePrice * box.pieces * 100) / 100;
  } else {
    const pieces = l.boxes.find(b => b.id === l.packageId)?.pieces || 1;
    l.quantity = l.quantity * pieces;
    l.packageId = null;
    l.price = l.piecePrice;
  }
  renderOrderLines();
}

function renderOrderActions() {
  const d = orderDraft;
  const editable = d.status === 'OPEN' || d.status === 'SENT';
  const b = (cls, onclick, text) => `<button class="btn ${cls}" onclick="${onclick}">${text}</button>`;
  $('ord-actions').innerHTML = editable
    ? `<div class="ord-foot-left">${d.id ? b('btn-ghost kr-danger-text', 'cancelOrderFromModal()', t('Cancel order')) : ''}</div>
       ${b('btn-secondary', "sendOrder('copy')", t('Copy text'))}
       ${b('btn-secondary', "sendOrder('print')", t('Print'))}
       ${b('btn-secondary', "sendOrder('viber')", 'Viber')}
       ${b('btn-secondary', "sendOrder('whatsapp')", 'WhatsApp')}
       ${b('btn-primary', 'saveOrder()', t('Save'))}
       ${d.id ? b('btn-primary', 'receiveOrderFromModal()', t('Mark received')) : ''}`
    : `${b('btn-secondary', "sendOrder('print')", t('Print'))}${b('btn-secondary', "closeModal('modal-order')", t('Close'))}`;
}

/* Product search inside the order window. */
let _ordSearchTmr;
let orderResults = [];
let orderResultIndex = -1;

function onOrderSearch(value) {
  clearTimeout(_ordSearchTmr);
  const text = value.trim();
  if (!text) { hideOrderResults(); return; }
  _ordSearchTmr = setTimeout(async () => {
    try {
      const res = /^\d{6,}$/.test(text)
        ? { data: [await req('GET', `/products/scan/${encodeURIComponent(text)}`).then(r => r.data.product).catch(() => null)].filter(Boolean) }
        : await req('GET', `/products/search?query=${encodeURIComponent(text)}`);
      if ($('ord-search').value.trim() !== text) return;
      orderResults = (res.data || []).slice(0, 10);
      orderResultIndex = orderResults.length ? 0 : -1;
      $('ord-results').innerHTML = orderResults.length ? orderResults.map((p, i) => `
        <div class="sr-item ${i === orderResultIndex ? 'on' : ''}" onmousedown="event.preventDefault(); addOrderProduct(${i})">
          <span class="sr-main"><span class="sr-name">${esc(p.name)}</span><span class="sr-code">${esc(p.barcode)}</span></span>
          <span class="sr-stock">${fmtQty(p.stock, p.unit)} ${esc(unitLabel(p.unit))}</span>
          <span class="sr-price">${fmtLek(p.purchasePrice)}</span>
        </div>`).join('') : `<div class="sr-empty">${t('No products found')}</div>`;
      $('ord-results').classList.remove('hidden');
    } catch {}
  }, 250);
}

function hideOrderResults() {
  orderResults = [];
  $('ord-results')?.classList.add('hidden');
}

function onOrderSearchKey(event) {
  if (!orderResults.length) return;
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault();
    orderResultIndex = (orderResultIndex + (event.key === 'ArrowDown' ? 1 : -1) + orderResults.length) % orderResults.length;
    document.querySelectorAll('#ord-results .sr-item').forEach((el, i) => el.classList.toggle('on', i === orderResultIndex));
  } else if (event.key === 'Enter') {
    event.preventDefault();
    addOrderProduct(orderResultIndex);
  }
}

async function addOrderProduct(index) {
  const p = orderResults[index];
  if (!p) return;
  hideOrderResults();
  $('ord-search').value = '';
  if (orderDraft.lines.some(l => l.productId === p.id)) { toast(t('This product is already on the order.'), 'info'); return; }
  const line = { productId: p.id, productName: p.name, barcode: p.barcode, unit: p.unit, stock: p.stock, packageId: null, quantity: 1,
    piecePrice: parseFloat(p.purchasePrice), price: parseFloat(p.purchasePrice), boxes: null };
  try { await loadLineBoxes(line); } catch { line.boxes = []; }
  orderDraft.lines.push(line);
  renderOrderLines();
  setTimeout(() => document.querySelectorAll('.ord-qty')[orderDraft.lines.length - 1]?.select(), 30);
}

/* Saving and sending */
async function saveOrder(quiet = false) {
  const d = orderDraft;
  d.supplierId = Number($('ord-supplier').value) || null;
  d.note = $('ord-note').value.trim();
  const body = { supplierId: d.supplierId, note: d.note,
    lines: d.lines.map(l => ({ productId: l.productId, packageId: l.packageId, quantity: l.quantity, price: l.price })) };
  try {
    const res = d.id ? await req('PUT', `/purchase-orders/${d.id}`, body) : await req('POST', '/purchase-orders', body);
    d.id = res.data.id;
    d.number = res.data.number;
    d.status = res.data.status;
    d.saved = res.data;
    if (!quiet) toast(t('Order saved'), 'success');
    renderOrderLines();
    if (currentInventoryTab === 'orders') loadOrders();
    if (currentInventoryTab === 'reorder') loadReorder();
    return res.data;
  } catch (e) { toast(e.message, 'error'); return null; }
}

/* The message for the supplier, in the language of the screen. */
function orderMessage(o) {
  const shop = shopSettings?.name || $('sb-shop-name')?.textContent || '';
  const lines = o.lines.map((l, i) => {
    const what = l.packageId ? `${parseFloat(l.quantity)} x ${l.packageName} (${fmtQty(l.pieceQuantity, 'pcs')} ${unitLabel('pcs')})`
      : `${fmtQty(l.quantity, l.unit)} ${unitLabel(l.unit)}`;
    return `${i + 1}. ${l.productName} - ${what}`;
  });
  return [t('Hello!'), t('Order from {shop} (no. {number}):', { shop, number: o.number }), '', ...lines, '',
    o.note ? `${t('Note')}: ${o.note}` : null, shopSettings?.phone ? `${t('Phone')}: ${shopSettings.phone}` : null, t('Thank you!')]
    .filter(x => x !== null).join('\n');
}

/* Albanian mobile numbers (069...) become 35569... for WhatsApp links. */
function whatsappNumber(phone) {
  let digits = (phone || '').replace(/[^\d+]/g, '');
  if (digits.startsWith('+')) digits = digits.slice(1);
  else if (digits.startsWith('00')) digits = digits.slice(2);
  else if (digits.startsWith('0')) digits = '355' + digits.slice(1);
  return digits.length >= 8 ? digits : '';
}

async function sendOrder(how) {
  const editable = orderDraft.status === 'OPEN' || orderDraft.status === 'SENT';
  const o = editable ? await saveOrder(true) : (await req('GET', `/purchase-orders/${orderDraft.id}`)).data;
  if (!o) return;
  await deliverOrder(o, how);
}

async function sendOrderBy(id, how) {
  try { await deliverOrder((await req('GET', `/purchase-orders/${id}`)).data, how); } catch (e) { toast(e.message, 'error'); }
}

async function deliverOrder(o, how) {
  const text = orderMessage(o);
  if (how === 'print') { printOrder(o); }
  else if (how === 'copy') {
    try { await navigator.clipboard.writeText(text); toast(t('Order copied: paste it in the chat with the supplier.'), 'success'); }
    catch { window.prompt(t('Copy the order:'), text); }
  } else if (how === 'whatsapp') {
    window.open(`https://wa.me/${whatsappNumber(o.supplierPhone)}?text=${encodeURIComponent(text)}`, '_blank');
  } else if (how === 'viber') {
    try { await navigator.clipboard.writeText(text); } catch {}
    window.location.href = `viber://forward?text=${encodeURIComponent(text)}`;
    toast(t('Viber opens with the order; it is also copied, in case Viber does not fill it in.'), 'info');
  }
  if (o.status === 'OPEN') {
    try {
      const res = await req('POST', `/purchase-orders/${o.id}/sent`);
      if (orderDraft && orderDraft.id === o.id) { orderDraft.status = res.data.status; renderOrderLines(); }
    } catch {}
  }
  if (currentInventoryTab === 'orders') loadOrders();
}

async function closeOrder(id, action) {
  const o = ordersCache.find(x => x.id === id) || orderDraft;
  const question = action === 'received' ? t('Did the goods of this order arrive? Enter the supplier\'s invoice in Purchases as usual.')
    : t('Cancel this order?');
  if (!confirm(`${question}\n\n${o.number || ''} · ${o.supplierName || ''}`)) return false;
  try {
    await req('POST', `/purchase-orders/${id}/${action === 'received' ? 'received' : 'cancel'}`);
    toast(action === 'received' ? t('Order marked as received') : t('Order cancelled'), 'success');
    loadOrders();
    return true;
  } catch (e) { toast(e.message, 'error'); return false; }
}

async function receiveOrderFromModal() {
  if (await closeOrder(orderDraft.id, 'received')) closeModal('modal-order');
}

async function cancelOrderFromModal() {
  if (await closeOrder(orderDraft.id, 'cancel')) closeModal('modal-order');
}

/* A4 order sheet, e.g. to give to the supplier's agent. */
function printOrder(o) {
  const shop = shopSettings || {};
  let sheet = $('order-print');
  if (!sheet) {
    sheet = document.createElement('div');
    sheet.id = 'order-print';
    document.body.appendChild(sheet);
  }
  sheet.innerHTML = `
    <header class="kp-head"><div><div class="kp-shop">${esc(shop.name || '')}${shop.address ? ' · ' + esc(shop.address) : ''}${shop.phone ? ' · ' + esc(shop.phone) : ''}</div>
      <h1>${t('Order')} ${esc(o.number)}</h1><div class="kp-meta">${t('Supplier')}: <strong>${esc(o.supplierName)}</strong>${o.supplierPhone ? ' · ' + esc(o.supplierPhone) : ''} · ${fmtDate(o.createdAt)}</div></div></header>
    <table><thead><tr><th>#</th><th>${t('Product')}</th><th>${t('Barcode')}</th><th>${t('Quantity')}</th><th>${t('Price')}</th><th>${t('Total')}</th></tr></thead>
      <tbody>${o.lines.map((l, i) => `<tr><td>${i + 1}</td><td>${esc(l.productName)}</td><td>${esc(l.barcode)}</td>
        <td>${l.packageId ? `${parseFloat(l.quantity)} x ${esc(l.packageName)} (${fmtQty(l.pieceQuantity, 'pcs')})` : `${fmtQty(l.quantity, l.unit)} ${esc(unitLabel(l.unit))}`}</td>
        <td>${fmtLek(l.price)}</td><td>${fmtLek(l.total)}</td></tr>`).join('')}</tbody>
      <tfoot><tr><td colspan="5"><strong>${t('Estimated total')}</strong></td><td><strong>${fmtLek(o.total)}</strong></td></tr></tfoot></table>
    ${o.note ? `<p class="op-note"><strong>${t('Note')}:</strong> ${esc(o.note)}</p>` : ''}
    <footer class="kp-foot">${t('Printed')} ${fmtDate(new Date().toISOString())} · ${esc(currentUser?.fullName || '')}</footer>`;
  document.body.classList.add('print-order');
  const done = () => { document.body.classList.remove('print-order'); window.removeEventListener('afterprint', done); };
  window.addEventListener('afterprint', done);
  window.print();
}

/*  Kthimet te furnitorët: goods given back to a supplier  */
const RETURN_KINDS = { CREDIT: 'Credit note', REPLACEMENT: 'Replacement' };
const RETURN_STATUS = { DONE: ['Taken off the debt', 'good'], PENDING: ['Waiting for replacement', 'warn'], REPLACED: ['Replaced', 'good'] };
const RETURN_REASONS = { DAMAGED: 'Damaged', EXPIRED: 'Expired', WRONG: 'Wrong goods', OTHER: 'Other' };
let returnPendingOnly = false;
let returnsCache = [];
let returnDraft = null;

function setReturnFilter(pending) {
  returnPendingOnly = pending;
  document.querySelectorAll('#ret-filter .btn').forEach(b => b.classList.toggle('on', b.dataset.p === String(pending)));
  loadReturns();
}

async function loadReturns() {
  const box = $('returns-list');
  box.innerHTML = `<p class="no-data">${t('Loading...')}</p>`;
  try {
    returnsCache = (await req('GET', `/supplier-returns?pending=${returnPendingOnly}`)).data || [];
    box.innerHTML = returnsCache.length ? returnsCache.map(r => {
      const [label, tone] = RETURN_STATUS[r.status];
      return `<div class="ord-card">
        <div class="ord-card-main" onclick="openReturn(${r.id})">
          <div><strong>${esc(r.supplierName)}</strong> <span class="kr-badge ${tone}">${t(label)}</span></div>
          <div class="wh-sub">${esc(r.number)} · ${t(RETURN_KINDS[r.kind])} · ${t(RETURN_REASONS[r.reason])} · ${linesText(r.lines.length)} · ${fmtDate(r.createdAt)}${r.replacedAt ? ` · ${t('replaced')} ${fmtDate(r.replacedAt)}` : ''}</div>
          ${r.note ? `<div class="wh-sub">${esc(r.note)}</div>` : ''}
        </div>
        <strong class="ord-card-total">${fmtLek(r.total)}</strong>
        <div class="ord-card-actions">
          <button class="btn btn-secondary btn-sm" onclick="openReturn(${r.id})">${t('Details')}</button>
          ${r.status === 'PENDING' ? `<button class="btn btn-primary btn-sm" onclick="replacementReceived(${r.id})">${t('Replacement received')}</button>` : ''}
        </div>
      </div>`;
    }).join('') : `<p class="no-data">${returnPendingOnly ? t('Nothing is waiting for replacement.') : t('No returns to suppliers yet.')}</p>`;
  } catch (e) { box.innerHTML = `<p class="no-data" style="color:var(--red)">${esc(e.message)}</p>`; }
}

async function openReturn(id) {
  try {
    if (!allSuppliers.length) allSuppliers = (await req('GET', '/suppliers')).data || [];
    if (id) {
      const r = (await req('GET', `/supplier-returns/${id}`)).data;
      returnDraft = { ...r, saved: true, lines: r.lines.map(l => ({ ...l, quantity: parseFloat(l.quantity), price: parseFloat(l.price) })) };
    } else {
      returnDraft = { saved: false, supplierId: null, kind: 'CREDIT', reason: 'DAMAGED', note: '', lines: [] };
    }
  } catch (e) { toast(e.message, 'error'); return; }
  const d = returnDraft;
  $('ret-supplier').innerHTML = `<option value="">${t('Choose the supplier')}</option>` + allSuppliers
    .filter(s => s.active || s.id === d.supplierId).map(s => `<option value="${s.id}">${esc(s.name)}</option>`).join('');
  $('ret-supplier').value = d.supplierId || '';
  $('ret-reason').value = d.reason;
  $('ret-note').value = d.note || '';
  ['ret-supplier', 'ret-reason', 'ret-note'].forEach(x => $(x).disabled = d.saved);
  $('ret-search-wrap').classList.toggle('hidden', d.saved);
  setReturnKind(d.kind);
  openModal('modal-return');
  if (!d.saved) setTimeout(() => $('ret-supplier').focus(), 60);
}

function setReturnKind(kind) {
  if (!returnDraft.saved) returnDraft.kind = kind;
  document.querySelectorAll('.ret-kind').forEach(b => {
    b.classList.toggle('on', b.dataset.k === returnDraft.kind);
    b.disabled = returnDraft.saved;
  });
  renderReturnLines();
}

function renderReturnLines() {
  const d = returnDraft;
  if (d.saved) {
    const [label, tone] = RETURN_STATUS[d.status];
    $('ret-title').innerHTML = `${t('Return')} ${esc(d.number)} <span class="kr-badge ${tone}">${t(label)}</span>`;
  } else {
    $('ret-title').textContent = t('New return to a supplier');
  }
  const dis = d.saved ? 'disabled' : '';
  $('ret-lines').innerHTML = d.lines.length ? d.lines.map((l, i) => `<tr>
      <td><strong>${esc(l.productName)}</strong><div class="wh-sub td-m">${esc(l.barcode)}</div></td>
      <td class="td-m">${l.stock != null ? fmtQty(l.stock, l.unit) : ''}</td>
      <td><input class="input ord-in ord-qty" type="number" min="0" step="${l.unit === 'kg' ? '0.001' : '1'}" value="${l.quantity}" ${dis}
          oninput="returnDraft.lines[${i}].quantity = parseFloat(this.value) || 0; renderReturnTotal()" /> ${esc(unitLabel(l.unit))}</td>
      <td><input class="input ord-in ord-price" type="number" min="0" step="0.01" value="${l.price == null ? '' : l.price}" ${dis}
          placeholder="${esc(t('last price'))}" oninput="returnDraft.lines[${i}].price = this.value === '' ? null : parseFloat(this.value); renderReturnTotal()" /></td>
      <td class="td-p" id="ret-line-total-${i}"></td>
      <td>${d.saved ? '' : `<button class="btn btn-ghost btn-sm" onclick="returnDraft.lines.splice(${i}, 1); renderReturnLines()">x</button>`}</td>
    </tr>`).join('') : `<tr><td colspan="6" class="no-data">${t('Search a product above to add it.')}</td></tr>`;
  renderReturnTotal();
  const b = (cls, onclick, text) => `<button class="btn ${cls}" onclick="${onclick}">${text}</button>`;
  $('ret-actions').innerHTML = d.saved
    ? `${d.status === 'PENDING' ? b('btn-primary', `replacementReceived(${d.id}, true)`, t('Replacement received')) : ''}${b('btn-secondary', "closeModal('modal-return')", t('Close'))}`
    : `${b('btn-secondary', "closeModal('modal-return')", t('Cancel'))}${b('btn-primary', 'saveReturn()', t('Save the return'))}`;
}

function renderReturnTotal() {
  let total = 0;
  returnDraft.lines.forEach((l, i) => {
    const price = l.price != null ? l.price : (l.defaultPrice || 0);
    total += price * l.quantity;
    const cell = $('ret-line-total-' + i);
    if (cell) cell.textContent = fmtLek(price * l.quantity);
  });
  const what = returnDraft.kind === 'CREDIT' ? t('Taken off what you owe') : t('Value of the goods');
  $('ret-total').innerHTML = `${linesText(returnDraft.lines.length)} · ${what} <strong>${fmtLek(returnDraft.saved ? returnDraft.total : total)}</strong>`;
}

let _retSearchTmr;
let returnResults = [];
let returnResultIndex = -1;

function onReturnSearch(value) {
  clearTimeout(_retSearchTmr);
  const text = value.trim();
  if (!text) { hideReturnResults(); return; }
  _retSearchTmr = setTimeout(async () => {
    try {
      const res = /^\d{6,}$/.test(text)
        ? { data: [await req('GET', `/products/scan/${encodeURIComponent(text)}`).then(r => r.data.product).catch(() => null)].filter(Boolean) }
        : await req('GET', `/products/search?query=${encodeURIComponent(text)}`);
      if ($('ret-search').value.trim() !== text) return;
      returnResults = (res.data || []).slice(0, 10);
      returnResultIndex = returnResults.length ? 0 : -1;
      $('ret-results').innerHTML = returnResults.length ? returnResults.map((p, i) => `
        <div class="sr-item ${i === returnResultIndex ? 'on' : ''}" onmousedown="event.preventDefault(); addReturnProduct(${i})">
          <span class="sr-main"><span class="sr-name">${esc(p.name)}</span><span class="sr-code">${esc(p.barcode)}</span></span>
          <span class="sr-stock">${fmtQty(p.stock, p.unit)} ${esc(unitLabel(p.unit))}</span>
          <span class="sr-price">${fmtLek(p.purchasePrice)}</span>
        </div>`).join('') : `<div class="sr-empty">${t('No products found')}</div>`;
      $('ret-results').classList.remove('hidden');
    } catch {}
  }, 250);
}

function hideReturnResults() {
  returnResults = [];
  $('ret-results')?.classList.add('hidden');
}

function onReturnSearchKey(event) {
  if (!returnResults.length) return;
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault();
    returnResultIndex = (returnResultIndex + (event.key === 'ArrowDown' ? 1 : -1) + returnResults.length) % returnResults.length;
    document.querySelectorAll('#ret-results .sr-item').forEach((el, i) => el.classList.toggle('on', i === returnResultIndex));
  } else if (event.key === 'Enter') {
    event.preventDefault();
    addReturnProduct(returnResultIndex);
  }
}

function addReturnProduct(index) {
  const p = returnResults[index];
  if (!p) return;
  hideReturnResults();
  $('ret-search').value = '';
  if (returnDraft.lines.some(l => l.productId === p.id)) { toast(t('This product is already on the return.'), 'info'); return; }
  returnDraft.lines.push({ productId: p.id, productName: p.name, barcode: p.barcode, unit: p.unit, stock: p.stock, quantity: 1,
    price: null, defaultPrice: parseFloat(p.purchasePrice) });
  renderReturnLines();
  setTimeout(() => document.querySelectorAll('#ret-lines .ord-qty')[returnDraft.lines.length - 1]?.select(), 30);
}

async function saveReturn() {
  const d = returnDraft;
  const body = { supplierId: Number($('ret-supplier').value) || null, kind: d.kind, reason: $('ret-reason').value,
    note: $('ret-note').value.trim(), lines: d.lines.map(l => ({ productId: l.productId, quantity: l.quantity, price: l.price })) };
  const question = d.kind === 'CREDIT'
    ? t('The goods leave stock now and the amount is taken off what you owe this supplier. Continue?')
    : t('The goods leave stock now and come back when the replacement arrives. Continue?');
  if (!confirm(question)) return;
  try {
    const res = await req('POST', '/supplier-returns', body);
    toast(t('Return {number} saved', { number: res.data.number }), 'success');
    closeModal('modal-return');
    loadReturns();
  } catch (e) { toast(e.message, 'error'); }
}

async function replacementReceived(id, fromModal = false) {
  const r = returnsCache.find(x => x.id === id) || returnDraft;
  if (!confirm(`${t('Did the replacement goods arrive? They go back into stock.')}\n\n${r.number} · ${r.supplierName}`)) return;
  try {
    await req('POST', `/supplier-returns/${id}/replaced`);
    toast(t('Replacement received: the goods are back in stock'), 'success');
    if (fromModal) closeModal('modal-return');
    loadReturns();
  } catch (e) { toast(e.message, 'error'); }
}
