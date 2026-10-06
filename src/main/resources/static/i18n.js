'use strict';

/*
 * Translations. The English text is the key, so code stays readable: t('Cart cleared').
 * Albanian is the default language. To add a text: write it in English in the code, then add it here.
 * {name}-style placeholders are filled from the second argument of t().
 */
const SQ = {
  // Brand and sign in
  'SuperMarket POS - Cashier Workstation': 'SuperMarket POS - Arka',
  'SuperMarket POS': 'SuperMarket POS',
  'Cashier Workstation': 'Vendi i arkëtarit',
  'SuperMarket': 'SuperMarket',
  'POS System': 'Sistemi i arkës',
  'Username': 'Emri i përdoruesit',
  'Password': 'Fjalëkalimi',
  'Sign In': 'Hyr',
  'Sign Out': 'Dil',
  'Full Name': 'Emri i plotë',
  'Create Account': 'Krijo llogarinë',
  'Register': 'Regjistrohu',
  'cashier': 'arketari',
  'cashier1': 'arketari1',
  'Cashier One': 'Arkëtari Një',
  'username': 'perdoruesi',
  'Please fill in all fields': 'Ju lutemi plotësoni të gjitha fushat',
  'Fill in all fields': 'Plotësoni të gjitha fushat',
  'Welcome back, {name}!': 'Mirë se u kthyet, {name}!',
  'Account created! Welcome, {name}!': 'Llogaria u krijua! Mirë se vini, {name}!',
  'Login failed': 'Hyrja dështoi',
  'Registration failed': 'Regjistrimi dështoi',
  'Request failed': 'Kërkesa dështoi',
  'Request failed with status {status}': 'Kërkesa dështoi (kodi {status})',
  'Language': 'Gjuha',

  // Roles and statuses
  'Cashier': 'Arkëtar',
  'Super Cashier': 'Super Arkëtar',
  'Super Admin': 'Super Admin',
  'Active': 'Aktiv',
  'Disabled': 'Çaktivizuar',
  'Open': 'Hapur',
  'Closed': 'Mbyllur',
  'Success': 'Sukses',
  'Failed': 'Dështoi',
  'Super Cashier or Super Admin access is required': 'Kërkohen të drejta Super Arkëtar ose Super Admin',
  'Super Admin access is required': 'Kërkohen të drejta Super Admin',

  // Navigation
  'Management': 'Menaxhimi',
  'Home': 'Kreu',
  'Point of Sale': 'Arka',
  'Products': 'Produktet',
  'Purchases': 'Blerjet',
  'Sales Log': 'Regjistri i shitjeve',
  'Reports': 'Raportet',
  'Users': 'Përdoruesit',
  'Operations': 'Turni dhe kopjet',

  // Home
  'Cashier Dashboard': 'Paneli i arkëtarit',
  'Welcome,': 'Mirë se vini,',
  'Start a sale, manage products, and review daily activity from one clean workspace.':
    'Filloni një shitje, menaxhoni produktet dhe ndiqni aktivitetin e ditës nga një vend i vetëm.',
  'Start sales, review your transactions, and control your daily shift.':
    'Filloni shitjet, shikoni transaksionet tuaja dhe menaxhoni turnin e ditës.',
  'Sales': 'Shitjet',
  'Revenue': 'Të ardhurat',
  'Cart Items': 'Artikuj në shportë',
  'New Sale': 'Shitje e re',
  'Open Point of Sale': 'Hap arkën',
  'Inventory': 'Inventari',
  'Manage Products': 'Menaxho produktet',
  'Receiving': 'Pritja e mallit',
  'Product Purchase Invoice': 'Faturë blerjeje',
  'View Sales Log': 'Shiko regjistrin e shitjeve',
  'Analytics': 'Analiza',
  'Open Reports': 'Hap raportet',
  'Quick Status': 'Gjendja e shpejtë',
  'Overview based on the current backend data.': 'Përmbledhje sipas të dhënave aktuale.',
  'Refresh': 'Rifresko',
  'Status': 'Gjendja',
  'Low Stock': 'Gjendje e ulët',
  '{count} products need attention': '{count} produkte kërkojnë vëmendje',
  'Last Sale': 'Shitja e fundit',
  'No sales yet': 'Ende pa shitje',
  'Could not load dashboard': 'Paneli nuk u ngarkua',

  // Point of sale
  'Retail Card F12 (Press Enter to Activate)': 'Karta e shitjes F12 (shtypni Enter për ta aktivizuar)',
  'Card': 'Karta',
  'Currency': 'Monedha',
  'EUR sale': 'EUR shitje',
  'EUR buy': 'EUR blerje',
  'USD sale': 'USD shitje',
  'USD buy': 'USD blerje',
  'Invoice No.': 'Nr. i faturës',
  'Number': 'Numri',
  'Account Balance Debit - Credit': 'Bilanci i llogarisë Debi - Kredi',
  'Lek': 'Lekë',
  'Euro': 'Euro',
  'Dollar': 'Dollarë',
  'Account Mask': 'Cilësimet e shitjes',
  'Show items that are not in stock': 'Shfaq artikujt pa gjendje',
  'Do not sell unavailable items': 'Mos shit artikujt që mungojnë',
  'Allow sale of zero-price items': 'Lejo shitjen e artikujve me çmim zero',
  'Recalculate price on each sale': 'Rillogarit çmimin në çdo shitje',
  'Show sale confirmation message': 'Shfaq mesazhin e konfirmimit të shitjes',
  'Show detailed receipt lines': 'Shfaq rreshtat e detajuar të kuponit',
  'Scan barcode or type product name...': 'Skanoni barkodin ose shkruani emrin e produktit...',
  'Search products or scan barcode': 'Kërkoni produkte ose skanoni barkodin',
  'Refresh Invoice': 'Rifresko faturën',
  'ID': 'ID',
  'Item Code': 'Kodi',
  'Item Name': 'Emërtimi',
  'Net Price': 'Çmimi pa TVSH',
  'Tax %': 'TVSH %',
  'Tax Amount': 'Vlera e TVSH',
  'Final Price': 'Çmimi me TVSH',
  'Quantity': 'Sasia',
  'Total': 'Totali',
  'Unit': 'Njësia',
  'Scan a barcode or use the keypad to add products to the invoice.':
    'Skanoni një barkod ose përdorni tastierën për të shtuar produkte në faturë.',
  'Numeric Keypad': 'Tastiera numerike',
  'Back': 'Fshi',
  'Clear': 'Pastro',
  'Enter / Scan': 'Enter / Skano',
  'Qty': 'Sasia',
  'Subtotal': 'Nëntotali',
  'Checkout': 'Përfundo',
  'Clear Invoice': 'Pastro faturën',
  'Clear Cart': 'Pastro shportën',
  'Loading products': 'Duke ngarkuar produktet',
  'CART': 'SHPORTA',
  'Refresh cart': 'Rifresko shportën',
  'Cart is empty': 'Shporta është bosh',
  'Click a product or scan a barcode': 'Klikoni një produkt ose skanoni një barkod',
  'Click a product or scan a barcode to add items': 'Klikoni një produkt ose skanoni një barkod për të shtuar artikuj',
  'Could not load products: ': 'Produktet nuk u ngarkuan: ',
  'Failed to load': 'Ngarkimi dështoi',
  'No products found': 'Nuk u gjet asnjë produkt',
  'Try a different search or barcode': 'Provoni një kërkim ose barkod tjetër',
  'Out of Stock': 'Pa gjendje',
  '{count} left': '{count} në gjendje',
  'Remove': 'Hiq',
  'Decrease': 'Ul',
  'Increase': 'Shto',
  'Added to cart': 'U shtua në shportë',
  'Could not add item': 'Artikulli nuk u shtua',
  'Item scanned & added': 'Artikulli u skanua dhe u shtua',
  'Product is not registered or does not exist': 'Produkti nuk është i regjistruar ose nuk ekziston',
  'Enter or scan a barcode first': 'Shkruani ose skanoni fillimisht një barkod',
  'Add an item before changing quantity': 'Shtoni një artikull para se të ndryshoni sasinë',
  'Quantity for ': 'Sasia për ',
  'Could not update qty': 'Sasia nuk u ndryshua',
  'Quantity must be at least 1': 'Sasia duhet të jetë të paktën 1',
  'Cashiers cannot change product prices': 'Arkëtarët nuk mund të ndryshojnë çmimet',
  'Price must be greater than zero': 'Çmimi duhet të jetë më i madh se zero',
  'Invoice line updated': 'Rreshti i faturës u ndryshua',
  'Could not update invoice line': 'Rreshti i faturës nuk u ndryshua',
  'Cart update failed': 'Ndryshimi i shportës dështoi',
  'Cart cleared': 'Shporta u pastrua',
  'Could not clear cart': 'Shporta nuk u pastrua',
  'Processing': 'Duke përpunuar',
  'Checkout complete!': 'Shitja u përfundua!',
  'Checkout failed': 'Shitja nuk u përfundua',

  // Receipt and payment
  'Receipt': 'Kuponi',
  'Payment': 'Pagesa',
  'Customer gave': 'Klienti dha',
  'Converted payment': 'Pagesa e konvertuar',
  'Change / Resto': 'Kusuri / Resto',
  'Receipt printer': 'Printeri i kuponit',
  'Default Windows printer': 'Printeri kryesor i Windows',
  'Close': 'Mbyll',
  'Print': 'Printo',
  'SALES RECEIPT': 'KUPON SHITJEJE',
  'Sale no.': 'Nr. shitjes',
  'TOTAL': 'TOTALI',
  'Thank you for shopping!': 'Faleminderit për blerjen!',
  'PAYMENT': 'PAGESA',
  'Converted': 'E konvertuar',
  'Change': 'Kusuri',
  'Receipt sent to {printer}': 'Kuponi u dërgua te {printer}',
  'Receipt sent to default printer': 'Kuponi u dërgua te printeri kryesor',
  'Could not print receipt': 'Kuponi nuk u printua',
  'Could not load printer list': 'Lista e printerëve nuk u ngarkua',

  // Products
  'Manage inventory and pricing': 'Menaxhoni inventarin dhe çmimet',
  'Add Product': 'Shto produkt',
  'Edit Product': 'Ndrysho produktin',
  'Update Product': 'Ruaj ndryshimet',
  'Save Product': 'Ruaj produktin',
  'Filter products': 'Filtro produktet',
  'Name': 'Emri',
  'Barcode': 'Barkodi',
  'Category': 'Kategoria',
  'Purchase': 'Blerja',
  'Tax': 'TVSH',
  'Stock': 'Gjendja',
  'Actions': 'Veprime',
  'Loading...': 'Duke ngarkuar...',
  'Product Name': 'Emri i produktit',
  'e.g. Fresh Bread': 'p.sh. Bukë e freskët',
  'Purchase Price (LEK)': 'Çmimi i blerjes (LEK)',
  'Stock Qty': 'Sasia në gjendje',
  'Final Selling Price (LEK)': 'Çmimi i shitjes me TVSH (LEK)',
  'Loading categories...': 'Duke ngarkuar kategoritë...',
  'Cancel': 'Anulo',
  'None': 'Pa kategori',
  'Edit': 'Ndrysho',
  'Delete': 'Fshi',
  'Confirm Delete': 'Konfirmo fshirjen',
  '{count} products': '{count} produkte',
  '{shown} of {total} products': '{shown} nga {total} produkte',
  'Error': 'Gabim',
  'Could not load product': 'Produkti nuk u ngarkua',
  'Please fill in all required fields': 'Ju lutemi plotësoni të gjitha fushat e detyrueshme',
  'Product updated': 'Produkti u ndryshua',
  'Product added': 'Produkti u shtua',
  'Now scan the barcode again to add it to the purchase invoice': 'Tani skanoni përsëri barkodin për ta shtuar në faturën e blerjes',
  'Save failed': 'Ruajtja dështoi',
  'Delete "{name}"? This cannot be undone.': 'Të fshihet "{name}"? Ky veprim nuk mund të kthehet.',
  'Product deleted': 'Produkti u fshi',
  'Delete failed': 'Fshirja dështoi',

  // Purchases
  'Save Purchase': 'Ruaj blerjen',
  'Purchase Invoice': 'Fatura e blerjes',
  'Company': 'Furnitori',
  'Supplier company': 'Emri i furnitorit',
  'Invoice Date': 'Data e faturës',
  'Date': 'Data',
  'Lines': 'Rreshta',
  'Items': 'Artikuj',
  'Scan Product': 'Skano produktin',
  'Scan barcode, then press Enter...': 'Skanoni barkodin, pastaj shtypni Enter...',
  'Add Barcode': 'Shto barkodin',
  'Register Product': 'Regjistro produkt',
  'Clear Form': 'Pastro formularin',
  'Product': 'Produkti',
  'Purchase Price': 'Çmimi i blerjes',
  'Selling Price': 'Çmimi i shitjes',
  'Scan products to build the supplier invoice.': 'Skanoni produktet për të ndërtuar faturën e furnitorit.',
  'Edit product': 'Ndrysho produktin',
  'Product added to purchase invoice': 'Produkti u shtua në faturën e blerjes',
  'Product not registered. Complete product registration first.': 'Produkti nuk është i regjistruar. Regjistrojeni fillimisht produktin.',
  'Fill invoice number, company, date, and at least one product': 'Plotësoni numrin e faturës, furnitorin, datën dhe të paktën një produkt',
  'Purchase invoice saved and stock updated': 'Fatura e blerjes u ruajt dhe gjendja u përditësua',
  'Could not save purchase invoice': 'Fatura e blerjes nuk u ruajt',

  // Sales log
  'Transaction history': 'Historiku i transaksioneve',
  'Transaction history for all cashiers': 'Historiku i transaksioneve për të gjithë arkëtarët',
  'Your transaction history': 'Historiku i transaksioneve tuaja',
  'All time': 'Gjithë periudha',
  'Today': 'Sot',
  'This month': 'Ky muaj',
  'This year': 'Ky vit',
  'Custom dates': 'Data të zgjedhura',
  'Cashier name': 'Emri i arkëtarit',
  'Sale ID': 'Nr. shitjes',
  'Date & Time': 'Data dhe ora',
  'Profit': 'Fitimi',
  'Total Revenue': 'Të ardhurat totale',
  'Transactions': 'Transaksione',
  'Avg Sale': 'Shitja mesatare',
  'Total Items Sold': 'Artikuj të shitur',
  'No sales found for the selected filters.': 'Nuk u gjet asnjë shitje për filtrat e zgjedhur.',
  '{count} items': '{count} artikuj',
  'View Details': 'Shiko detajet',
  'Sale not found': 'Shitja nuk u gjet',
  'Sale #{id}': 'Shitja nr. {id}',
  'No items': 'Pa artikuj',
  'Total Amount': 'Shuma totale',
  'Print Receipt': 'Printo kuponin',
  'Unknown cashier': 'Arkëtar i panjohur',
  'Unknown product': 'Produkt i panjohur',

  // Reports
  'Income, revenue, tax, cashier, and product performance': 'Të ardhurat, TVSH-ja, arkëtarët dhe produktet',
  'Revenue Trend': 'Ecuria e të ardhurave',
  'Income grouped by selected period': 'Të ardhurat sipas periudhës së zgjedhur',
  'Tax Split': 'Ndarja e TVSH-së',
  'Net sales and tax amount': 'Shitjet pa TVSH dhe vlera e TVSH-së',
  'Cashier Sales': 'Shitjet sipas arkëtarit',
  'Revenue by account': 'Të ardhurat sipas llogarisë',
  'Top Products': 'Produktet më të shitura',
  'Best sellers by quantity': 'Më të shiturat sipas sasisë',
  'Monthly Report': 'Raporti mujor',
  'Revenue comparison by month': 'Krahasimi i të ardhurave sipas muajve',
  'Report Details': 'Detajet e raportit',
  'Filtered transactions with cashier and totals': 'Transaksionet e filtruara me arkëtarin dhe totalet',
  'Sale': 'Shitja',
  'Net': 'Pa TVSH',
  'Loading reports...': 'Duke ngarkuar raportet...',
  'Could not load reports: ': 'Raportet nuk u ngarkuan: ',
  'Total Income': 'Të ardhurat totale',
  'Gross Profit': 'Fitimi bruto',
  'Items Sold': 'Artikuj të shitur',
  'Average Sale': 'Shitja mesatare',
  'Report': 'Raporti',
  'No transactions for this report filter.': 'Nuk ka transaksione për këtë filtër.',
  'Income': 'Të ardhurat',
  'Net sales': 'Shitjet pa TVSH',
  'Monthly income': 'Të ardhurat mujore',
  'No income data yet': 'Ende pa të dhëna për të ardhurat',
  'No data for this chart': 'Nuk ka të dhëna për këtë grafik',
  'No products sold yet': 'Ende pa produkte të shitura',
  'No tax data yet': 'Ende pa të dhëna për TVSH-në',

  // Users
  'Create accounts and control cashier access': 'Krijoni llogari dhe kontrolloni qasjen e arkëtarëve',
  'Add User': 'Shto përdorues',
  'Edit User': 'Ndrysho përdoruesin',
  'Update User': 'Ruaj ndryshimet',
  'Save User': 'Ruaj përdoruesin',
  'Filter users': 'Filtro përdoruesit',
  'Role': 'Roli',
  'Loading users...': 'Duke ngarkuar përdoruesit...',
  '{count} users': '{count} përdorues',
  'No users found.': 'Nuk u gjet asnjë përdorues.',
  'Leave empty to keep current password': 'Lëreni bosh për të mbajtur fjalëkalimin aktual',
  'Minimum 4 characters': 'Të paktën 4 karaktere',
  'Name, username, and a password of at least 4 characters are required':
    'Kërkohen emri, emri i përdoruesit dhe një fjalëkalim me të paktën 4 karaktere',
  'User updated': 'Përdoruesi u ndryshua',
  'User created': 'Përdoruesi u krijua',
  'Could not save user': 'Përdoruesi nuk u ruajt',

  // Shifts and backups
  'Daily control': 'Kontrolli ditor',
  'Shifts & Backups': 'Turnet dhe kopjet rezervë',
  'All Shifts': 'Të gjitha turnet',
  'My Shift': 'Turni im',
  'Run Backup': 'Bëj kopje rezervë',
  'Shift Status': 'Gjendja e turnit',
  'Opened At': 'Hapur më',
  'Opening Cash': 'Paratë në hapje',
  'Last Backup': 'Kopja e fundit',
  'Open Shift': 'Hap turnin',
  'Start the cashier day with the cash already in drawer': 'Filloni ditën me paratë që janë në arkë',
  'Opening cash in drawer': 'Paratë në arkë në hapje',
  'Close Shift': 'Mbyll turnin',
  'Enter real cash counted from drawer': 'Shkruani paratë e numëruara në arkë',
  'Closing cash counted': 'Paratë e numëruara në mbyllje',
  'Total Sales': 'Shitjet totale',
  'Expected Cash': 'Paratë e pritura',
  'Difference': 'Diferenca',
  'Recent Shifts': 'Turnet e fundit',
  'Opening, closing, expected cash, and difference': 'Hapja, mbyllja, paratë e pritura dhe diferenca',
  'Opened': 'Hapur',
  'Opening': 'Në hapje',
  'Expected': 'Të pritura',
  'Closing': 'Në mbyllje',
  'Diff': 'Diferenca',
  'Backup History': 'Historiku i kopjeve rezervë',
  'Automatic backups run daily at 23:00': 'Kopjet automatike bëhen çdo ditë në orën 23:00',
  'File': 'Skedari',
  'Message': 'Mesazhi',
  'Could not load operations': 'Të dhënat e turnit nuk u ngarkuan',
  'Login again before opening a shift': 'Hyni përsëri para se të hapni turnin',
  'Shift opened': 'Turni u hap',
  'Could not open shift': 'Turni nuk u hap',
  'No open shift to close': 'Nuk ka turn të hapur për të mbyllur',
  'Shift closed': 'Turni u mbyll',
  'Could not close shift': 'Turni nuk u mbyll',
  'No open shift': 'Nuk ka turn të hapur',
  'No shifts yet.': 'Ende pa turne.',
  'Backup created': 'Kopja rezervë u krijua',
  'Backup failed': 'Kopja rezervë dështoi',
  'Could not run backup': 'Kopja rezervë nuk u krye',
  'No backups yet.': 'Ende pa kopje rezervë.',
  'No backup yet': 'Ende pa kopje rezervë',

  // Parked carts, quantities, deactivated products
  'Park cart': 'Parko shportën',
  'Parked carts': 'Shportat e parkuara',
  "A parked cart can be resumed on any till. The till's own cart must be empty first.":
    'Një shportë e parkuar mund të vazhdohet në çdo arkë. Shporta e arkës duhet të jetë fillimisht bosh.',
  'Note': 'Shënimi',
  'Parked at': 'Parkuar më',
  'Note to recognise this cart (optional)': 'Shënim për ta njohur këtë shportë (opsional)',
  'Cart parked': 'Shporta u parkua',
  'Could not park cart': 'Shporta nuk u parkua',
  'Resume': 'Vazhdo',
  'No parked carts.': 'Nuk ka shporta të parkuara.',
  'Cart resumed': 'Shporta u vazhdua',
  'Could not resume cart': 'Shporta nuk u vazhdua',
  'Enter a valid quantity': 'Shkruani një sasi të vlefshme',
  'Invoice no.': 'Nr. faturës',
  'Invoice {number}': 'Fatura {number}',
  'Deactivate product': 'Çaktivizo produktin',
  'Deactivate': 'Çaktivizo',
  'Inactive': 'Joaktiv',
  'Reactivate': 'Riaktivizo',
  'Deactivate "{name}"? It will no longer be sold, but stays in old sales and can be reactivated.':
    'Të çaktivizohet "{name}"? Nuk do të shitet më, por mbetet në shitjet e vjetra dhe mund të riaktivizohet.',
  'Product reactivated': 'Produkti u riaktivizua',
  'Product deactivated': 'Produkti u çaktivizua',

  // Payments, cash in/out, X and Z reports
  'To pay': 'Për të paguar',
  'Exact cash': 'Para të sakta',
  'All by card': 'E gjitha me kartë',
  'Cash (LEK)': 'Para në dorë (LEK)',
  'Foreign cash': 'Para të huaja',
  'Card (LEK)': 'Kartë (LEK)',
  'Paid': 'Paguar',
  'Still to pay': 'Mbetet për të paguar',
  'Complete payment': 'Përfundo pagesën',
  'Discount': 'Zbritje',
  'Sale completed. Change: {amount}': 'Shitja u përfundua. Kusuri: {amount}',
  'Cash': 'Para në dorë',
  'Could not load exchange rates': 'Kurset e këmbimit nuk u ngarkuan',
  'Exchange rates saved': 'Kurset e këmbimit u ruajtën',
  'Could not save exchange rates': 'Kurset e këmbimit nuk u ruajtën',
  'Count the drawer first. The expected amount is shown after the shift is closed.':
    'Numëroni fillimisht paratë në arkë. Shuma e pritur shfaqet pasi të mbyllet turni.',
  'Cash in / out': 'Hyrje / dalje parash',
  'Money put into or taken out of the drawer outside of sales': 'Para të futura ose të nxjerra nga arka jashtë shitjeve',
  'Type': 'Lloji',
  'Cash in': 'Hyrje parash',
  'Cash out': 'Dalje parash',
  'Amount (LEK)': 'Shuma (LEK)',
  'Reason': 'Arsyeja',
  'e.g. supplier paid from the drawer': 'p.sh. pagesë furnitori nga arka',
  'Save': 'Ruaj',
  'Open a shift first': 'Hapni fillimisht turnin',
  'Cash movement saved': 'Lëvizja e parave u regjistrua',
  'Shift summary': 'Përmbledhja e turnit',
  'Shift #{id}': 'Turni nr. {id}',
  'Print X report': 'Printo raportin X',
  'Cash (net)': 'Para në dorë (neto)',
  'Daily Z report': 'Raporti Z ditor',
  'Everything sold and received on one day, on all tills': 'Gjithçka e shitur dhe e arkëtuar në një ditë, në të gjitha arkat',
  'Show': 'Shfaq',

  // Approvals, refunds, audit log
  'Manager approval': 'Miratim nga menaxheri',
  'Manager PIN': 'PIN-i i menaxherit',
  'Approve': 'Mirato',
  'Approval PIN (managers)': 'PIN miratimi (menaxherët)',
  'PIN': 'PIN',
  'Leave empty to keep the current PIN': 'Lëreni bosh për të mbajtur PIN-in aktual',
  '4 to 8 digits': '4 deri në 8 shifra',
  'Refund': 'Kthim malli',
  'Refunds': 'Kthimet',
  'Find': 'Kërko',
  'Invoice number': 'Numri i faturës',
  'Sold': 'Shitur',
  'Already refunded': 'Kthyer më parë',
  'Price paid': 'Çmimi i paguar',
  'Refund qty': 'Sasia për kthim',
  'Refund method': 'Mënyra e kthimit',
  'To refund': 'Për t\'u kthyer',
  'Refund {number} completed: {amount}': 'Kthimi {number} u krye: {amount}',
  'Refund failed': 'Kthimi dështoi',
  'Audit log': 'Regjistri i veprimeve',
  'Who did what and when, and which manager approved it': 'Kush bëri çfarë dhe kur, dhe cili menaxher e miratoi',
  'User': 'Përdoruesi',
  'Action': 'Veprimi',
  'Details': 'Detaje',
  'Approved by': 'Miratoi',
  'All actions': 'Të gjitha veprimet',
  'Nothing recorded in this period.': 'Asgjë e regjistruar në këtë periudhë.',
  'Signed in': 'Hyrje në sistem',
  'Signed out': 'Dalje nga sistemi',
  'Failed sign-in': 'Hyrje e dështuar',
  'Account locked': 'Llogari e bllokuar',
  'Wrong manager PIN': 'PIN i gabuar menaxheri',
  'Price changed at till': 'Çmim i ndryshuar në arkë',
  'Line voided': 'Rresht i anuluar',
  'Product created': 'Produkt i krijuar',
  'Product changed': 'Produkt i ndryshuar',
  'Purchase invoice saved': 'Faturë blerjeje e ruajtur',
  'Exchange rate changed': 'Kurs këmbimi i ndryshuar',
  'User changed': 'Përdorues i ndryshuar',

  // Suppliers
  'Suppliers': 'Furnitorët',
  'Purchase invoices, payments and amount owed': 'Faturat e blerjes, pagesat dhe detyrimet',
  'Add supplier': 'Shto furnitor',
  'Edit supplier': 'Ndrysho furnitorin',
  'NIPT': 'NIPT',
  'Phone': 'Telefoni',
  'Email': 'Email',
  'Address': 'Adresa',
  'Notes': 'Shënime',
  'Invoiced': 'Faturuar',
  'Owed': 'Detyrim',
  'No suppliers yet.': 'Ende pa furnitorë.',
  'Supplier saved': 'Furnitori u ruajt',
  'Record a payment': 'Regjistro një pagesë',
  'Bank': 'Bankë',
  'Purchase invoices': 'Faturat e blerjes',
  'Payments': 'Pagesat',
  'Method': 'Mënyra',
  'Amount': 'Shuma',
  'Payment saved': 'Pagesa u regjistrua',
  'Supplier': 'Furnitori',
  'Invoice': 'Fatura',
  'Expiry date': 'Data e skadimit',

  // Inventory
  'Low stock, reorder list, expiry dates and stock counts': 'Gjendje e ulët, porositë, skadimet dhe numërimi i mallit',
  'Reorder': 'Porositë',
  'Expiring': 'Skadimet',
  'Stock count': 'Numërimi i mallit',
  'Reorder list': 'Lista e porosive',
  'Products at or below their minimum stock, grouped by the supplier they were last bought from':
    'Produktet në ose nën gjendjen minimale, sipas furnitorit nga i cili u blenë herën e fundit',
  'No supplier yet': 'Pa furnitor ende',
  'estimated {amount}': 'rreth {amount}',
  'To order': 'Për të porositur',
  'Last cost': 'Kosto e fundit',
  'Nothing to reorder: every product is above its minimum stock.': 'Asgjë për të porositur: të gjitha produktet janë mbi gjendjen minimale.',
  'Expiring soon': 'Skadojnë së shpejti',
  'Estimated from delivery dates: older deliveries are assumed to be sold first':
    'Vlerësim sipas datave të furnizimit: supozohet se malli më i vjetër shitet i pari',
  'Next 7 days': '7 ditët e ardhshme',
  'Next 30 days': '30 ditët e ardhshme',
  'Next 90 days': '90 ditët e ardhshme',
  'Days left': 'Ditë të mbetura',
  'Probably on shelf': 'Ndoshta në raft',
  'Expired': 'Skaduar',
  'Nothing expires in this period.': 'Asgjë nuk skadon në këtë periudhë.',
  'Start count': 'Fillo numërimin',
  'Apply differences': 'Apliko diferencat',
  'Cancel count': 'Anulo numërimin',
  'Scan barcode': 'Skano barkodin',
  'Quantity on shelf': 'Sasia në raft',
  'Counted': 'Numëruar',
  'In system': 'Në sistem',
  'Count started {date} by {name}: {lines} products counted, {diff} with a difference':
    'Numërimi filloi më {date} nga {name}: {lines} produkte të numëruara, {diff} me diferencë',
  'No count in progress. Start one, scan each product and type how many are on the shelf.':
    'Nuk ka numërim në vazhdim. Filloni një, skanoni çdo produkt dhe shkruani sa ka në raft.',
  'Scan a product and enter the quantity': 'Skanoni një produkt dhe shkruani sasinë',
  'Set the stock of the {count} counted products to what was found?': 'Të vendoset gjendja e {count} produkteve të numëruara sipas asaj që u gjet?',
  'Stock count applied': 'Numërimi u aplikua',
  'Cancel this count? Nothing will change.': 'Të anulohet ky numërim? Asgjë nuk do të ndryshojë.',
  'Minimum stock': 'Gjendja minimale',
  'Reorder quantity': 'Sasia për porosi',
  'Empty: no alert': 'Bosh: pa njoftim',
  'Empty: automatic': 'Bosh: automatike',
  'Adjust stock': 'Rregullo gjendjen',
  'Damaged': 'I dëmtuar',
  'Lost / stolen': 'I humbur / vjedhur',
  'Internal use': 'Përdorim i brendshëm',
  'Other': 'Tjetër',
  'Direction': 'Drejtimi',
  'Remove from stock': 'Hiq nga gjendja',
  'Add to stock': 'Shto në gjendje',
  'History': 'Historiku',
  'Stock after': 'Gjendja pas',
  'Stock adjusted': 'Gjendja u rregullua',
  'Export CSV': 'Eksporto CSV',
  'Import CSV': 'Importo CSV',
  'Import failed': 'Importimi dështoi',
  '{created} created, {updated} updated, {errors} lines with errors': '{created} të krijuara, {updated} të ndryshuara, {errors} rreshta me gabime',
  'Line': 'Rreshti',
  'Supplier created': 'Furnitor i krijuar',
  'Supplier changed': 'Furnitor i ndryshuar',
  'Supplier paid': 'Pagesë furnitori',
  'Stock count started': 'Numërimi filloi',
  'Stock count cancelled': 'Numërimi u anulua',
  'Products imported': 'Produkte të importuara',

  // Customers, loyalty and credit
  'Customers': 'Klientët',
  'Customer': 'Klienti',
  'Loyalty cards, points and accounts for buying on credit': 'Kartat e besnikërisë, pikët dhe blerjet në borxh',
  'Add customer': 'Shto klient',
  'Edit customer': 'Ndrysho klientin',
  'New customer': 'Klient i ri',
  'No customer': 'Pa klient',
  'No customer found.': 'Nuk u gjet asnjë klient.',
  'Customer saved': 'Klienti u ruajt',
  'Name, phone or card': 'Emri, telefoni ose karta',
  'Scan the card or type a name or phone': 'Skanoni kartën ose shkruani emrin ose telefonin',
  'Card number': 'Numri i kartës',
  'Empty: generated': 'Bosh: krijohet automatikisht',
  'Credit limit': 'Limiti i borxhit',
  'Credit limit (LEK)': 'Limiti i borxhit (LEK)',
  'Empty or 0: no buying on credit': 'Bosh ose 0: pa blerje në borxh',
  'Points': 'Pikë',
  '{points} points': '{points} pikë',
  'Debt': 'Borxhi',
  'On account': 'Në borxh',
  'Points (LEK)': 'Me pikë (LEK)',
  'On account (LEK)': 'Në borxh (LEK)',
  'max {amount}': 'deri në {amount}',
  'not allowed': 'nuk lejohet',
  'Receive a debt payment': 'Merr pagesë borxhi',
  "To the customer's account": 'Në llogarinë e klientit',
  'Discount %': 'Zbritje %',
  'Manual discount': 'Zbritje manuale',
  'Discount on the whole cart (%). 0 removes it.': 'Zbritje për gjithë shportën (%). 0 e heq.',
  'Enter a valid number': 'Shkruani një numër të vlefshëm',

  // Promotions
  'Promotions': 'Promocionet',
  'Applied automatically at the till; the best one for each product wins': 'Aplikohen automatikisht në arkë; për çdo produkt vlen më i miri',
  'Add promotion': 'Shto promocion',
  'Edit promotion': 'Ndrysho promocionin',
  'Promotion saved': 'Promocioni u ruajt',
  'No promotions yet.': 'Ende pa promocione.',
  'Offer': 'Oferta',
  'On': 'Për',
  'When': 'Kur',
  'Always': 'Gjithmonë',
  'e.g. Weekend drinks -15%': 'p.sh. Pijet e fundjavës -15%',
  'Percentage off': 'Zbritje në përqindje',
  'Buy X, get Y free': 'Bli X, merr Y falas',
  'Buy {buy}, get {free} free': 'Bli {buy}, merr {free} falas',
  'Buy': 'Bli',
  'Free': 'Falas',
  'Applies to': 'Vlen për',
  'One product (barcode)': 'Një produkt (barkodi)',
  'A whole category': 'Një kategori të tërë',
  'From date': 'Nga data',
  'To date': 'Deri në datën',
  'Days (empty: every day)': 'Ditët (bosh: çdo ditë)',
  'From hour': 'Nga ora',
  'To hour': 'Deri në orën',
  'Mon': 'E hënë',
  'Tue': 'E martë',
  'Wed': 'E mërkurë',
  'Thu': 'E enjte',
  'Fri': 'E premte',
  'Sat': 'E shtunë',
  'Sun': 'E diel',
  'Promotion created': 'Promocion i krijuar',
  'Promotion changed': 'Promocion i ndryshuar',
  'Customer created': 'Klient i krijuar',
  'Credit limit changed': 'Limit borxhi i ndryshuar',
  'Debt payment': 'Pagesë borxhi',

  // Units
  'pcs': 'copë',
  'kg': 'kg',
};

const SUPPORTED_LANGUAGES = ['sq', 'en'];
let currentLang = readSavedLanguage();
const missingTranslations = new Set();

function readSavedLanguage() {
  try {
    const saved = localStorage.getItem('lang');
    if (SUPPORTED_LANGUAGES.includes(saved)) return saved;
  } catch {}
  return 'sq';
}

function t(text, params) {
  let result = text;
  if (currentLang === 'sq') {
    if (Object.prototype.hasOwnProperty.call(SQ, text)) {
      result = SQ[text];
    } else if (!missingTranslations.has(text)) {
      missingTranslations.add(text);
      console.warn('Missing Albanian translation:', text);
    }
  }
  if (params) {
    result = result.replace(/\{(\w+)\}/g, (match, name) => (name in params ? String(params[name]) : match));
  }
  return result;
}

function uiLocale() {
  return currentLang === 'en' ? 'en-GB' : 'sq-AL';
}

/* Albanian dates are formatted here because many browsers ship without Albanian locale data. */
const SQ_MONTHS = ['jan', 'shk', 'mar', 'pri', 'maj', 'qer', 'korr', 'gush', 'sht', 'tet', 'nën', 'dhj'];
const SQ_WEEKDAYS = ['e diel', 'e hënë', 'e martë', 'e mërkurë', 'e enjte', 'e premte', 'e shtunë'];
const pad2 = n => String(n).padStart(2, '0');

function formatTime(value) {
  const d = new Date(value);
  return `${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
}

function formatDateTime(value) {
  const d = new Date(value);
  if (currentLang === 'en') {
    return d.toLocaleString('en-GB', { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });
  }
  return `${d.getDate()} ${SQ_MONTHS[d.getMonth()]} ${d.getFullYear()}, ${formatTime(d)}`;
}

function formatWeekdayDate(value) {
  const d = new Date(value);
  if (currentLang === 'en') return d.toLocaleDateString('en-GB', { weekday: 'long', day: 'numeric', month: 'short' });
  return `${SQ_WEEKDAYS[d.getDay()]}, ${d.getDate()} ${SQ_MONTHS[d.getMonth()]}`;
}

function formatMonthYear(value) {
  const d = new Date(value);
  if (currentLang === 'en') return d.toLocaleDateString('en-GB', { month: 'short', year: 'numeric' });
  return `${SQ_MONTHS[d.getMonth()]} ${d.getFullYear()}`;
}

function unitLabel(unit) {
  return t(unit || 'pcs');
}

function statusLabel(status) {
  const labels = { OPEN: 'Open', CLOSED: 'Closed', SUCCESS: 'Success', FAILED: 'Failed' };
  return t(labels[status] || status || '');
}

/* Translates static page text. The English original is kept in a data attribute, so switching back works. */
function applyLanguage(root = document) {
  document.documentElement.lang = currentLang;
  root.querySelectorAll('[data-i18n]').forEach(el => {
    if (!el.dataset.i18nKey) el.dataset.i18nKey = el.textContent.trim().replace(/\s+/g, ' ');
    el.textContent = t(el.dataset.i18nKey);
  });
  ['placeholder', 'title'].forEach(attr => {
    root.querySelectorAll(`[data-i18n-${attr}]`).forEach(el => {
      const keyName = 'i18nKey' + attr[0].toUpperCase() + attr.slice(1);
      if (!el.dataset[keyName]) el.dataset[keyName] = el.getAttribute(attr);
      el.setAttribute(attr, t(el.dataset[keyName]));
    });
  });
  document.querySelectorAll('.lang-select').forEach(select => { select.value = currentLang; });
}

function setLanguage(lang) {
  if (!SUPPORTED_LANGUAGES.includes(lang)) return;
  currentLang = lang;
  try { localStorage.setItem('lang', lang); } catch {}
  applyLanguage();
  if (typeof onLanguageChanged === 'function') onLanguageChanged();
}
