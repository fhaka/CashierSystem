'use strict';

const API = window.location.origin;
let currentUser  = null;
let posProducts  = [];
let cartItems    = [];
let allProdTable = [];
let allSales     = [];
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

async function req(method, path, body) {
  const opts = { method, headers: { 'Content-Type': 'application/json', 'Accept-Language': currentLang } };
  if (body !== undefined) opts.body = JSON.stringify(body);
  const res  = await fetch(API + path, opts);
  const json = await res.json().catch(() => ({}));
  if (res.status === 401 && currentUser) clearLocalSession();
  if (!res.ok) throw new Error(json.message || json.error || t('Request failed with status {status}', { status: res.status }));
  if (json.success === false) throw new Error(json.message || t('Request failed'));
  return json;
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
  if (['products', 'purchases', 'reports'].includes(view) && !isOperationalManager()) {
    toast(t('Super Cashier or Super Admin access is required'), 'error');
    view = 'home';
  }
  if (view === 'users' && !isSuperAdmin()) {
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
  if (view === 'sales')    loadSales();
  if (view === 'reports')  loadReports();
  if (view === 'users') loadUsers();
  if (view === 'operations') loadOperations();
}

async function loadHomeData() {
  updateHomeClock();
  try {
    const [productsRes, salesRes, cartRes] = await Promise.all([
      req('GET', '/products'),
      req('GET', '/sales'),
      req('GET', '/sales/cart')
    ]);
    const products = Array.isArray(productsRes.data) ? productsRes.data : [];
    const sales = Array.isArray(salesRes.data) ? salesRes.data : [];
    const cart = Array.isArray(cartRes.data) ? cartRes.data : [];
    const revenue = sales.reduce((sum, sale) => sum + parseFloat(sale.totalAmount || 0), 0);
    const lowStock = products.filter(product => (product.stock || 0) <= 5).length;
    const cartCount = cart.reduce((sum, item) => sum + (item.quantity || 0), 0);

    $('home-products-count').textContent = products.length;
    $('home-sales-count').textContent = sales.length;
    $('home-revenue').textContent = fmtLek(revenue);
    $('home-cart-count').textContent = cartCount;
    $('home-status-list').innerHTML = `
      <div class="home-status-item"><span>${t('Low Stock')}</span><strong>${t('{count} products need attention', { count: lowStock })}</strong></div>
      <div class="home-status-item"><span>${t('Last Sale')}</span><strong>${sales[0] ? fmtDate(sales[0].date) : t('No sales yet')}</strong></div>
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
    const sc = p.stock === 0 ? 'sk-oos' : p.stock < 5 ? 'sk-low' : 'sk-ok';
    const sl = p.stock === 0 ? t('Out of Stock') : t('{count} left', { count: p.stock });
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
  const total = cartItems.reduce((s, i) => s + (i.quantity || 0), 0);
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
        <button class="qty-btn" onclick="changeQty(${item.productId},${item.quantity - 1})"></button>
        <span class="qty-val">${item.quantity}</span>
        <button class="qty-btn" onclick="changeQty(${item.productId},${item.quantity + 1})">+</button>
      </div>
      <div class="ci-total">${fmt(item.lineTotal)}</div>
      <button class="ci-rm" onclick="removeItem(${item.productId})" title="${t('Remove')}"></button>
    </div>
  `).join('');
}

async function fetchSubtotal() {
  try {
    const res = await req('GET', '/sales/cart/subtotal');
    const sub = res.data?.subtotal ?? res.data ?? 0;
    $('cart-subtotal').textContent = fmt(sub);
  } catch {}
}

function refreshCurrencyDisplay() {
  renderCart();
  renderGrid(posProducts);
  renderProdsTable(allProdTable);
  renderSalesTable(allSales);
  renderStats(allSales);
  if ($('view-reports')?.classList.contains('on')) applyReportFilters();
  calculateChange();
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
    const priceCell = isOperationalManager()
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
      <td><input class="input pos-edit-cell pos-qty-cell" type="number" min="1" step="1" value="${item.quantity}" onchange="updateCartInline(${item.productId}, 'quantity', this.value)" /></td>
      <td class="td-p">${fmt(item.lineTotal)}</td>
      <td class="td-m">${esc(unitLabel(item.unit))}</td>
      <td class="td-a">
        <button class="btn btn-secondary btn-sm btn-icon" title="${t('Decrease')}" onclick="changeQty(${item.productId},${item.quantity - 1})">-</button>
        <button class="btn btn-secondary btn-sm btn-icon" title="${t('Increase')}" onclick="changeQty(${item.productId},${item.quantity + 1})">+</button>
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

function promptQuantity() {
  if (!cartItems.length) {
    toast(t('Add an item before changing quantity'), 'error');
    return;
  }
  const last = cartItems[cartItems.length - 1];
  const qty = parseInt(prompt(t('Quantity for ') + last.productName, last.quantity), 10);
  if (!Number.isNaN(qty)) changeQty(last.productId, qty);
}

async function changeQty(productId, newQty) {
  if (newQty < 1) { removeItem(productId); return; }
  const item = cartItems.find(i => i.productId === productId);
  if (!item) return;
  const delta = newQty - item.quantity;
  try {
    if (delta > 0) {
      await req('POST', '/sales/cart', { productId, quantity: delta });
      await refreshCart();
    } else {
      await rebuildCart(productId, newQty);
    }
  } catch (e) { toast(e.message || t('Could not update qty'), 'error'); }
}

async function updateCartInline(productId, field, value) {
  const item = cartItems.find(cartItem => cartItem.productId === productId);
  if (!item) return;
  const body = { quantity: item.quantity };
  if (field === 'quantity') {
    const quantity = parseInt(value || '0', 10);
    if (Number.isNaN(quantity) || quantity < 1) {
      toast(t('Quantity must be at least 1'), 'error');
      renderInvoiceRows();
      return;
    }
    body.quantity = quantity;
  }
  if (field === 'price') {
    if (!isOperationalManager()) {
      toast(t('Cashiers cannot change product prices'), 'error');
      renderInvoiceRows();
      return;
    }
    const price = parseFloat(value || '0');
    if (Number.isNaN(price) || price <= 0) {
      toast(t('Price must be greater than zero'), 'error');
      renderInvoiceRows();
      return;
    }
    body.price = price;
  }
  try {
    const res = await req('PUT', `/sales/cart/${productId}`, body);
    cartItems = Array.isArray(res.data) ? res.data : [];
    renderCart();
    fetchSubtotal();
    toast(t('Invoice line updated'), 'success');
  } catch (e) {
    toast(e.message || t('Could not update invoice line'), 'error');
    refreshCart();
  }
}

async function removeItem(productId) {
  await rebuildCart(productId, 0);
}

async function rebuildCart(targetId, targetQty) {
  const snapshot = [...cartItems];
  try {
    await req('DELETE', '/sales/cart');
    for (const item of snapshot) {
      const qty = item.productId === targetId ? targetQty : item.quantity;
      if (qty > 0) await req('POST', '/sales/cart', { productId: item.productId, quantity: qty });
    }
    await refreshCart();
  } catch (e) { toast(e.message || t('Cart update failed'), 'error'); }
}

async function clearCart() {
  try {
    await req('DELETE', '/sales/cart');
    cartItems = [];
    renderCart();
    toast(t('Cart cleared'), 'info');
  } catch (e) { toast(e.message || t('Could not clear cart'), 'error'); }
}

/*  Checkout  */
async function doCheckout() {
  if (!cartItems.length) { toast(t('Cart is empty'), 'error'); return; }
  const btn = $('btn-checkout');
  btn.disabled = true;
  btn.innerHTML = `<div class="spin"></div> ${t('Processing')}`;
  try {
    const res = await req('POST', '/sales/checkout');
    const data = res.data || {};
    lastCheckoutData = data;
    lastReceipt = data.printableReceipt || buildFallbackReceipt(data);
    $('receipt-txt').textContent = lastReceipt;
    preparePayment(data);
    openModal('modal-receipt');
    loadReceiptPrinters();
    cartItems = [];
    renderCart();
    $('invoice-no').value = String((data.saleId || 0) + 1).padStart(4, '0');
    toast(t('Checkout complete!'), 'success');
  } catch (e) {
    toast(e.message || t('Checkout failed'), 'error');
  } finally {
    btn.disabled = false;
    btn.innerHTML = `<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="20 6 9 17 4 12"/></svg> ${t('Checkout')}`;
  }
}

function preparePayment(data) {
  const totalLek = parseFloat(data.totalAmount || 0);
  if ($('payment-total-lek')) $('payment-total-lek').textContent = fmtLek(totalLek);
  if ($('payment-currency')) $('payment-currency').value = $('currency-select')?.value || 'LEK';
  if ($('payment-amount')) $('payment-amount').value = '';
  calculateChange();
}

function calculateChange() {
  if (!lastCheckoutData) return;
  const totalLek = parseFloat(lastCheckoutData.totalAmount || 0);
  const paymentCurrency = $('payment-currency')?.value || 'LEK';
  const paidAmount = parseFloat($('payment-amount')?.value || '0');
  const paidLek = convertPaymentToLek(paidAmount, paymentCurrency);
  const changeLek = paidLek - totalLek;
  if ($('payment-total-lek')) $('payment-total-lek').textContent = fmtLek(totalLek);
  if ($('payment-converted')) $('payment-converted').textContent = fmtLek(paidLek);
  if ($('payment-change')) $('payment-change').textContent = fmtLek(changeLek);
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
    const res = await req('GET', '/sales');
    const sales = Array.isArray(res.data) ? res.data : [];
    const maxId = sales.reduce((max, sale) => Math.max(max, sale.id || 0), 0);
    if ($('invoice-no')) $('invoice-no').value = String(maxId + 1).padStart(4, '0');
  } catch {
    if ($('invoice-no')) $('invoice-no').value = '0001';
  }
}

/*  Purchase Invoices  */
function initPurchasePage() {
  loadCategories();
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
      sellingPrice: parseFloat(product.price || 0)
    });
  }
  renderPurchaseRows();
}

function renderPurchaseRows() {
  const tbody = $('purchase-rows');
  if (!tbody) return;
  if (!purchaseItems.length) {
    tbody.innerHTML = `<tr class="invoice-empty"><td colspan="11">${t('Scan products to build the supplier invoice.')}</td></tr>`;
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
      <td><input class="input purchase-cell" type="number" min="1" value="${item.quantity}" onchange="updatePurchaseItem(${index}, 'quantity', this.value)" /></td>
      <td><input class="input purchase-cell" type="number" min="0" step="0.01" value="${item.purchasePrice}" onchange="updatePurchaseItem(${index}, 'purchasePrice', this.value)" /></td>
      <td>
        <select class="input purchase-cell" onchange="updatePurchaseItem(${index}, 'taxRate', this.value)">
          <option value="20" ${Number(item.taxRate) === 20 ? 'selected' : ''}>20%</option>
          <option value="0" ${Number(item.taxRate) === 0 ? 'selected' : ''}>0%</option>
        </select>
      </td>
      <td><input class="input purchase-cell" type="number" min="0" step="0.01" value="${item.sellingPrice}" onchange="updatePurchaseItem(${index}, 'sellingPrice', this.value)" /></td>
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
    item[field] = field === 'quantity' ? parseInt(value || '0', 10) : parseFloat(value || '0');
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
  return parseFloat(item.purchasePrice || 0) * parseInt(item.quantity || 0, 10);
}

function updatePurchaseTotal() {
  const total = purchaseItems.reduce((sum, item) => sum + getPurchaseLineTotal(item), 0);
  const itemCount = purchaseItems.reduce((sum, item) => sum + parseInt(item.quantity || 0, 10), 0);
  if ($('purchase-total')) $('purchase-total').textContent = fmtLek(total);
  if ($('purchase-lines-count')) $('purchase-lines-count').textContent = purchaseItems.length;
  if ($('purchase-items-count')) $('purchase-items-count').textContent = itemCount;
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
      quantity: parseInt(item.quantity || 0, 10),
      purchasePrice: parseFloat(item.purchasePrice || 0),
      sellingPrice: parseFloat(item.sellingPrice || 0),
      taxRate: parseFloat(item.taxRate || 0),
      unit: item.unit || 'pcs'
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
    `${t('Sale no.')}: ${data.saleId || ''}`,
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
  lines.push('='.repeat(32));
  lines.push(t('Thank you for shopping!'));
  return lines.join('\n');
}

async function printReceipt() {
  calculateChange();
  const paidAmount = $('payment-amount')?.value || '0';
  const paidCurrency = $('payment-currency')?.value || 'LEK';
  const paidLek = convertPaymentToLek(paidAmount, paidCurrency);
  const totalLek = parseFloat(lastCheckoutData?.totalAmount || 0);
  const paymentLines = lastCheckoutData ? `\n\n${t('PAYMENT')}\n${t('Total')}: ${fmtLek(totalLek)}\n${t('Customer gave')}: ${paidAmount} ${paidCurrency}\n${t('Converted')}: ${fmtLek(paidLek)}\n${t('Change')}: ${fmtLek(paidLek - totalLek)}` : '';
  const receiptToPrint = lastReceipt + paymentLines;
  const printerName = $('receipt-printer')?.value || '';
  try {
    await req('POST', '/printer/receipt', { receiptText: receiptToPrint, printerName });
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
    const sc = p.stock === 0 ? 'b-red' : p.stock < 5 ? 'b-amber' : 'b-green';
    return `<tr>
      <td class="td-m t-muted">#${p.id}</td>
      <td><strong>${esc(p.name)}</strong></td>
      <td class="td-m">${esc(p.barcode)}</td>
      <td><span class="badge b-blue">${esc(p.category?.name || t('None'))}</span></td>
      <td><span class="badge b-muted">${esc(unitLabel(p.unit))}</span></td>
      <td class="td-p">${fmt(p.purchasePrice)}</td>
      <td class="td-m">${fmtTax(p.taxRate)}</td>
      <td class="td-p">${fmt(p.price)}</td>
      <td><span class="badge ${sc}">${p.stock}</span></td>
      <td class="td-a">
        <button class="btn btn-secondary btn-sm btn-icon" title="${t('Edit')}" onclick="openProdModalById(${p.id})">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
            <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
          </svg>
        </button>
        <button class="btn btn-danger btn-sm btn-icon" title="${t('Delete')}" onclick="confirmDelete(${p.id})">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <polyline points="3 6 5 6 21 6"/>
            <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/>
          </svg>
        </button>
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
    stock:        parseInt($('pm-stock').value),
    categoryName: $('pm-cat').value,
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
  $('confirm-msg').textContent = t('Delete "{name}"? This cannot be undone.', { name });
  $('confirm-ok').onclick = () => deleteProd(id);
  openModal('modal-confirm');
}

async function deleteProd(id) {
  try {
    await req('DELETE', `/products/${id}`);
    toast(t('Product deleted'), 'success');
    closeModal('modal-confirm');
    loadProdsTable();
    loadPosProducts();
  } catch (e) { toast(e.message || t('Delete failed'), 'error'); }
}

/*  Sales Log  */
async function loadSales() {
  $('sales-tbody').innerHTML = `<tr><td colspan="6" class="no-data">${t('Loading...')}</td></tr>`;
  try {
    const res = await req('GET', '/sales');
    allSales = Array.isArray(res.data) ? res.data : [];
    applySalesFilters();
  } catch (e) {
    $('sales-tbody').innerHTML = `<tr><td colspan="6" class="no-data" style="color:var(--red)">${t('Error')}: ${esc(e.message)}</td></tr>`;
  }
}

function applySalesFilters() {
  const period = $('sales-period')?.value || 'all';
  const cashierQuery = ($('sales-cashier-filter')?.value || '').toLowerCase().trim();
  const now = new Date();
  const filtered = allSales.filter(sale => {
    const saleDate = new Date(sale.date);
    const cashierName = (sale.cashier?.fullName || t('Unknown cashier')).toLowerCase();
    const matchesCashier = !cashierQuery || cashierName.includes(cashierQuery);
    const matchesPeriod =
      period === 'all' ||
      (period === 'day' && saleDate.toDateString() === now.toDateString()) ||
      (period === 'month' && saleDate.getFullYear() === now.getFullYear() && saleDate.getMonth() === now.getMonth());
    return matchesCashier && matchesPeriod;
  });
  renderSalesTable(filtered);
  renderStats(filtered);
}

function renderStats(sales) {
  const grid = $('stats-grid');
  if (!sales.length) { grid.classList.add('hidden'); return; }
  const total = sales.reduce((s, sale) => s + parseFloat(sale.totalAmount || 0), 0);
  const avgTx = total / sales.length;
  const totalItems = sales.reduce((s, sale) => s + (sale.items || []).reduce((q, item) => q + (item.quantity || 0), 0), 0);
  const profit = sales.reduce((sum, sale) => sum + getSaleProfit(sale), 0);
  const profitCard = isOperationalManager()
    ? `<div class="stat-card sc-green"><div class="sc-label">${t('Profit')}</div><div class="sc-value">${fmt(profit)}</div></div>`
    : '';
  grid.classList.remove('hidden');
  grid.innerHTML = `
    <div class="stat-card sc-amber"><div class="sc-label">${t('Total Revenue')}</div><div class="sc-value">${fmt(total)}</div></div>
    ${profitCard}
    <div class="stat-card sc-green"><div class="sc-label">${t('Transactions')}</div><div class="sc-value">${sales.length}</div></div>
    <div class="stat-card sc-blue"><div class="sc-label">${t('Avg Sale')}</div><div class="sc-value">${fmt(avgTx)}</div></div>
    <div class="stat-card"><div class="sc-label">${t('Total Items Sold')}</div><div class="sc-value" style="color:var(--text)">${totalItems}</div></div>
  `;
}

function renderSalesTable(sales) {
  const tbody = $('sales-tbody');
  if (!sales.length) {
    tbody.innerHTML = `<tr><td colspan="6" class="no-data">${t('No sales found for the selected filters.')}</td></tr>`; return;
  }
  tbody.innerHTML = sales.map(sale => `
    <tr class="sale-row" onclick="viewSale(${sale.id})">
      <td class="td-m"><strong>#${sale.id}</strong></td>
      <td>${fmtDate(sale.date)}</td>
      <td>${esc(sale.cashier?.fullName || t('Unknown cashier'))}</td>
      <td><span class="badge b-muted">${t('{count} items', { count: (sale.items || []).reduce((q, item) => q + (item.quantity || 0), 0) })}</span></td>
      <td class="td-p">${fmt(sale.totalAmount)}</td>
      <td>
        <button class="btn btn-secondary btn-sm" onclick="event.stopPropagation();viewSale(${sale.id})">
          ${t('View Details')}
        </button>
      </td>
    </tr>
  `).join('');
}

function viewSale(saleId) {
  const sale = allSales.find(s => s.id === saleId);
  if (!sale) { toast(t('Sale not found'), 'error'); return; }

  // If sale has printableReceipt (from checkout response stored in log), show it
  if (sale.printableReceipt) {
    lastReceipt = sale.printableReceipt;
    $('receipt-txt').textContent = lastReceipt;
    openModal('modal-receipt');
    loadReceiptPrinters();
    return;
  }

  // Otherwise show detail modal
  $('sale-modal-title').textContent = t('Sale #{id}', { id: sale.id });
  const items = (sale.items || []);
  const itemRows = items.map(i => `
    <tr>
      <td>${esc(i.productName || i.product?.name || '')}</td>
      <td class="td-m">${esc(i.barcode || i.product?.barcode || '')}</td>
      <td class="td-m">${esc(unitLabel(i.unit || i.product?.unit))}</td>
      <td class="td-m" style="text-align:center">${i.quantity}</td>
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
    <div class="modal-foot" style="margin-top:18px;">
      <button class="btn btn-secondary" onclick="closeModal('modal-sale')">${t('Close')}</button>
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

function printSale(saleId) {
  const sale = allSales.find(s => s.id === saleId);
  if (!sale) return;
  lastReceipt = buildFallbackReceipt(sale);
  printReceipt();
}

function getSaleProfit(sale) {
  const total = parseFloat(sale.totalAmount || 0);
  const cost = (sale.items || []).reduce((sum, item) => {
    const qty = parseFloat(item.quantity || 0);
    const purchase = parseFloat(item.product?.purchasePrice || 0);
    return sum + purchase * qty;
  }, 0);
  return Math.max(0, total - cost);
}

/*  Reports  */
async function loadReports() {
  const tbody = $('report-tbody');
  if (tbody) tbody.innerHTML = `<tr><td colspan="7" class="no-data">${t('Loading reports...')}</td></tr>`;
  try {
    const res = await req('GET', '/reports/sales');
    allSales = Array.isArray(res.data) ? res.data : [];
    applyReportFilters();
  } catch (e) {
    if (tbody) tbody.innerHTML = `<tr><td colspan="7" class="no-data" style="color:var(--red)">${t('Error')}: ${esc(e.message)}</td></tr>`;
    toast(t('Could not load reports: ') + e.message, 'error');
  }
}

function applyReportFilters() {
  const sales = getReportFilteredSales();
  const summary = buildReportSummary(sales);
  renderReportKpis(summary);
  renderReportTable(sales);
  renderReportCharts(summary);
}

function getReportFilteredSales() {
  const period = $('report-period')?.value || 'month';
  const now = new Date();
  const fromInput = $('report-from')?.value;
  const toInput = $('report-to')?.value;
  return allSales.filter(sale => {
    const d = new Date(sale.date);
    if (period === 'today') return d.toDateString() === now.toDateString();
    if (period === 'month') return d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth();
    if (period === 'year') return d.getFullYear() === now.getFullYear();
    if (period === 'custom') {
      const from = fromInput ? new Date(fromInput + 'T00:00:00') : null;
      const to = toInput ? new Date(toInput + 'T23:59:59') : null;
      return (!from || d >= from) && (!to || d <= to);
    }
    return true;
  });
}

function buildReportSummary(sales) {
  const byDay = new Map();
  const byMonth = new Map();
  const byCashier = new Map();
  const byProduct = new Map();
  let total = 0;
  let tax = 0;
  let itemsSold = 0;
  let cost = 0;

  sales.forEach(sale => {
    const saleTotal = parseFloat(sale.totalAmount || 0);
    total += saleTotal;
    const date = new Date(sale.date);
    const dayKey = date.toISOString().slice(0, 10);
    const monthKey = formatMonthYear(date);
    byDay.set(dayKey, (byDay.get(dayKey) || 0) + saleTotal);
    byMonth.set(monthKey, (byMonth.get(monthKey) || 0) + saleTotal);

    const cashier = sale.cashier?.fullName || t('Unknown cashier');
    byCashier.set(cashier, (byCashier.get(cashier) || 0) + saleTotal);

    (sale.items || []).forEach(item => {
      const qty = parseFloat(item.quantity || 0);
      const finalPrice = parseFloat(item.unitPrice || item.price || 0);
      const netPrice = parseFloat(item.priceWithoutTax || item.unitPriceWithoutTax || finalPrice);
      const lineTotal = parseFloat(item.lineTotal || qty * finalPrice);
      const lineTax = item.taxAmount !== undefined && item.taxAmount !== null
        ? parseFloat(item.taxAmount || 0)
        : Math.max(0, (finalPrice - netPrice) * qty);
      const productName = item.productName || item.product?.name || t('Unknown product');
      const purchasePrice = parseFloat(item.product?.purchasePrice || 0);
      const existing = byProduct.get(productName) || { label: productName, qty: 0, revenue: 0 };

      existing.qty += qty;
      existing.revenue += lineTotal;
      byProduct.set(productName, existing);
      tax += lineTax;
      itemsSold += qty;
      cost += purchasePrice * qty;
    });
  });

  return {
    sales,
    total,
    tax,
    net: Math.max(0, total - tax),
    cost,
    profit: Math.max(0, total - cost),
    itemsSold,
    transactions: sales.length,
    avgSale: sales.length ? total / sales.length : 0,
    byDay: sortedEntries(byDay),
    byMonth: sortedEntries(byMonth),
    byCashier: sortedEntries(byCashier).sort((a, b) => b.value - a.value),
    byProduct: Array.from(byProduct.values()).sort((a, b) => b.qty - a.qty).slice(0, 8),
  };
}

function sortedEntries(map) {
  return Array.from(map.entries()).map(([label, value]) => ({ label, value }));
}

function renderReportKpis(summary) {
  const kpis = $('report-kpis');
  if (!kpis) return;
  kpis.innerHTML = `
    <div class="report-kpi"><span>${t('Total Income')}</span><strong>${fmtLek(summary.total)}</strong></div>
    <div class="report-kpi"><span>${t('Gross Profit')}</span><strong>${fmtLek(summary.profit)}</strong></div>
    <div class="report-kpi"><span>${t('Tax Amount')}</span><strong>${fmtLek(summary.tax)}</strong></div>
    <div class="report-kpi"><span>${t('Transactions')}</span><strong>${summary.transactions}</strong></div>
    <div class="report-kpi"><span>${t('Items Sold')}</span><strong>${summary.itemsSold}</strong></div>
    <div class="report-kpi"><span>${t('Average Sale')}</span><strong>${fmtLek(summary.avgSale)}</strong></div>
  `;
  const label = $('report-period')?.selectedOptions?.[0]?.textContent || t('Report');
  if ($('report-range-label')) $('report-range-label').textContent = label;
}

function renderReportTable(sales) {
  const tbody = $('report-tbody');
  if (!tbody) return;
  if (!sales.length) {
    tbody.innerHTML = `<tr><td colspan="7" class="no-data">${t('No transactions for this report filter.')}</td></tr>`;
    return;
  }
  tbody.innerHTML = sales.map(sale => {
    const tax = getSaleTax(sale);
    const total = parseFloat(sale.totalAmount || 0);
    const items = (sale.items || []).reduce((sum, item) => sum + (item.quantity || 0), 0);
    return `
      <tr class="sale-row" onclick="viewSale(${sale.id})">
        <td class="td-m"><strong>#${sale.id}</strong></td>
        <td>${fmtDate(sale.date)}</td>
        <td>${esc(sale.cashier?.fullName || t('Unknown cashier'))}</td>
        <td><span class="badge b-muted">${t('{count} items', { count: items })}</span></td>
        <td class="td-p">${fmtLek(total - tax)}</td>
        <td class="td-p">${fmtLek(tax)}</td>
        <td class="td-p">${fmtLek(total)}</td>
      </tr>
    `;
  }).join('');
}

function getSaleTax(sale) {
  return (sale.items || []).reduce((sum, item) => {
    const qty = parseFloat(item.quantity || 0);
    const finalPrice = parseFloat(item.unitPrice || item.price || 0);
    const netPrice = parseFloat(item.priceWithoutTax || item.unitPriceWithoutTax || finalPrice);
    const tax = item.taxAmount !== undefined && item.taxAmount !== null
      ? parseFloat(item.taxAmount || 0)
      : Math.max(0, (finalPrice - netPrice) * qty);
    return sum + tax;
  }, 0);
}

function renderReportCharts(summary) {
  drawLineChart('chart-revenue', summary.byDay.length ? summary.byDay : summary.byMonth, t('Income'));
  drawDonutChart('chart-tax', [
    { label: t('Net sales'), value: summary.net },
    { label: t('Tax'), value: summary.tax }
  ]);
  drawBarChart('chart-cashiers', summary.byCashier.slice(0, 6), t('Revenue'));
  drawHorizontalBarChart('chart-products', summary.byProduct.map(p => ({ label: p.label, value: p.qty })), t('Qty'));
  drawBarChart('chart-monthly', summary.byMonth, t('Monthly income'));
}

const REPORT_CHART_HEIGHTS = {
  'chart-revenue': 240,
  'chart-tax': 240,
  'chart-cashiers': 260,
  'chart-products': 260,
  'chart-monthly': 240
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
  const barW = Math.max(18, (width - pad * 2 - gap * (data.length - 1)) / data.length);
  data.forEach((d, i) => {
    const x = pad + i * (barW + gap);
    const h = (d.value / max) * (height - pad * 2);
    const y = height - pad - h;
    ctx.fillStyle = i % 2 ? c.green : c.blue;
    roundRect(ctx, x, y, barW, h, 6);
    ctx.fill();
    ctx.fillStyle = c.muted;
    ctx.font = '600 11px Barlow, sans-serif';
    ctx.textAlign = 'center';
    ctx.fillText(shortLabel(d.label), x + barW / 2, height - 9);
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

function drawDonutChart(id, data) {
  const setup = setupCanvas(id);
  if (!setup) return;
  const { ctx, width, height } = setup;
  const c = chartColors();
  ctx.clearRect(0, 0, width, height);
  const total = data.reduce((s, d) => s + d.value, 0);
  if (!total) { drawEmptyChart(ctx, width, height, t('No tax data yet')); return; }
  const colors = [c.green, c.blue, c.purple, c.red];
  const cx = width / 2;
  const cy = height / 2 - 8;
  const radius = Math.min(width, height) / 3;
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
    ctx.fillRect(26, height - 42 + i * 20, 10, 10);
    ctx.fillStyle = c.muted;
    ctx.textAlign = 'left';
    ctx.fillText(`${d.label}: ${fmtLek(d.value)}`, 44, height - 33 + i * 20);
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
      <td><span class="badge ${user.active ? 'b-green' : 'b-red'}">${user.active ? t('Active') : t('Disabled')}</span></td>
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
  openModal('modal-user');
}

async function submitUser() {
  const id = $('um-id').value;
  const body = {
    fullName: $('um-name').value.trim(),
    username: $('um-username').value.trim(),
    password: $('um-password').value,
    role: $('um-role').value,
    active: $('um-active').value === 'true'
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

/*  Shifts and backups  */
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
  } catch (e) {
    toast(e.message || t('Could not load operations'), 'error');
  }
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
    renderClosedShiftSummary(res.data);
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
  $('shift-sales').textContent = fmtLek(shift?.totalSales || 0);
  $('shift-expected').textContent = fmtLek(shift?.expectedCash || shift?.openingCash || 0);
  $('shift-difference').textContent = fmtLek(shift?.difference || 0);
}

function renderClosedShiftSummary(shift) {
  $('shift-sales').textContent = fmtLek(shift?.totalSales || 0);
  $('shift-expected').textContent = fmtLek(shift?.expectedCash || 0);
  $('shift-difference').textContent = fmtLek(shift?.difference || 0);
}

function renderShiftRows(shifts) {
  const tbody = $('shifts-tbody');
  if (!tbody) return;
  if (!shifts.length) {
    tbody.innerHTML = `<tr><td colspan="10" class="no-data">${t('No shifts yet.')}</td></tr>`;
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
      <td>${fmtLek(shift.totalSales || 0)}</td>
      <td>${fmtLek(shift.expectedCash || 0)}</td>
      <td>${shift.closingCash === null || shift.closingCash === undefined ? '-' : fmtLek(shift.closingCash)}</td>
      <td>${fmtLek(shift.difference || 0)}</td>
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

