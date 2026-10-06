package com.supermarket.service;

import com.supermarket.dto.CashMovementRequest;
import com.supermarket.dto.CustomerDtos.CustomerDetail;
import com.supermarket.dto.CustomerDtos.CustomerPaymentRequest;
import com.supermarket.dto.CustomerDtos.CustomerRequest;
import com.supermarket.exception.PermissionDeniedException;
import com.supermarket.exception.ValidationException;
import com.supermarket.model.Cashier;
import com.supermarket.model.Customer;
import com.supermarket.model.CustomerTransaction;
import com.supermarket.model.Refund;
import com.supermarket.model.Sale;
import com.supermarket.model.Shift;
import com.supermarket.repository.CustomerRepository;
import com.supermarket.repository.CustomerTransactionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Customers: loyalty card and points, and an account for buying on credit. Every change of points or debt
 * is written to the customer's history. Points: one point per pos.loyalty.lek-per-point spent;
 * one point pays pos.loyalty.point-value LEK.
 */
@Service
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final CustomerTransactionRepository transactionRepository;
    private final ShiftService shiftService;
    private final AuditService auditService;
    private final BigDecimal lekPerPoint;
    private final BigDecimal pointValue;
    private final SecureRandom random = new SecureRandom();

    public CustomerService(
            CustomerRepository customerRepository,
            CustomerTransactionRepository transactionRepository,
            ShiftService shiftService,
            AuditService auditService,
            @Value("${pos.loyalty.lek-per-point:100}") BigDecimal lekPerPoint,
            @Value("${pos.loyalty.point-value:1}") BigDecimal pointValue
    ) {
        this.customerRepository = customerRepository;
        this.transactionRepository = transactionRepository;
        this.shiftService = shiftService;
        this.auditService = auditService;
        this.lekPerPoint = lekPerPoint;
        this.pointValue = pointValue;
    }

    public BigDecimal pointValue() {
        return pointValue;
    }

    /** Points earned for an amount paid (not counting what was paid with points). */
    public int pointsEarnedFor(BigDecimal amount) {
        return lekPerPoint.signum() <= 0 || amount.signum() <= 0 ? 0 : amount.divide(lekPerPoint, 0, RoundingMode.DOWN).intValue();
    }

    @Transactional(readOnly = true)
    public List<Customer> search(String query) {
        if (query == null || query.isBlank()) {
            return customerRepository.findAllByOrderByFullNameAsc();
        }
        return customerRepository.search(query.trim(), PageRequest.of(0, 50));
    }

    @Transactional(readOnly = true)
    public Customer findByCard(String cardNumber) {
        return customerRepository.findByCardNumber(cardNumber == null ? "" : cardNumber.trim())
                .orElseThrow(() -> new ValidationException("customer.cardNotFound", cardNumber));
    }

    public Customer find(Long id) {
        return customerRepository.findById(id).orElseThrow(() -> new ValidationException("customer.notFound", id));
    }

    @Transactional(readOnly = true)
    public CustomerDetail detail(Long id) {
        return new CustomerDetail(find(id), transactionRepository.findForCustomer(id));
    }

    @Transactional
    public Customer create(CustomerRequest request, Cashier actor) {
        String card = request.cardNumber() == null || request.cardNumber().isBlank() ? newCardNumber() : request.cardNumber().trim();
        requireFreeCard(card, null);
        Customer customer = new Customer(card, requireName(request), LocalDateTime.now());
        apply(customer, request);
        if (request.creditLimit() != null) {
            requireManager(actor);
            customer.setCreditLimit(validLimit(request.creditLimit()));
        }
        Customer saved = customerRepository.save(customer);
        auditService.record(actor, "CUSTOMER_CREATED", "CUSTOMER", saved.getId(), saved.getFullName() + ", card " + saved.getCardNumber()
                + (saved.getCreditLimit() == null ? "" : ", credit limit " + saved.getCreditLimit()));
        return saved;
    }

    @Transactional
    public Customer update(Long id, CustomerRequest request, Cashier actor) {
        Customer customer = find(id);
        if (request.cardNumber() != null && !request.cardNumber().isBlank()) {
            requireFreeCard(request.cardNumber().trim(), id);
            customer.setCardNumber(request.cardNumber().trim());
        }
        customer.setFullName(requireName(request));
        apply(customer, request);
        // No credit limit in the request: unchanged. 0 removes the limit.
        BigDecimal newLimit = request.creditLimit() == null ? customer.getCreditLimit() : validLimit(request.creditLimit());
        if (!Objects.equals(stripped(customer.getCreditLimit()), stripped(newLimit))) {
            requireManager(actor);
            auditService.record(actor, "CUSTOMER_CREDIT_LIMIT", "CUSTOMER", id,
                    customer.getFullName() + ": " + customer.getCreditLimit() + " -> " + newLimit);
            customer.setCreditLimit(newLimit);
        }
        return customer;
    }

    /** The customer pays off (part of) their debt. Cash goes into the drawer of the cashier's open shift. */
    @Transactional
    public Customer receivePayment(Long id, CustomerPaymentRequest request, Cashier actor) {
        Customer customer = find(id);
        if (request.amount() == null || request.amount().signum() <= 0) {
            throw new ValidationException("payment.amountPositive");
        }
        BigDecimal amount = request.amount().setScale(2, RoundingMode.HALF_UP);
        String method = request.method() == null ? "CASH" : request.method().trim().toUpperCase();
        if (!method.equals("CASH") && !method.equals("CARD")) {
            throw new ValidationException("customer.invalidPaymentMethod");
        }
        if (method.equals("CASH")) {
            Shift shift = shiftService.findOpenShift(actor.getId()).orElseThrow(() -> new ValidationException("customer.shiftRequired"));
            shiftService.addCashMovement(shift.getId(), new CashMovementRequest("IN", amount,
                    "Pagesë borxhi / debt payment: " + customer.getFullName()), actor);
        }
        customer.changeBalance(amount.negate());
        transactionRepository.save(new CustomerTransaction(customer, actor, CustomerTransaction.Type.PAYMENT, 0,
                amount.negate(), null, null, blankToNull(request.note()) == null ? method : method + " - " + request.note().trim(),
                LocalDateTime.now()));
        auditService.record(actor, "CUSTOMER_PAYMENT", "CUSTOMER", id, customer.getFullName() + ": " + amount + " LEK " + method);
        return customer;
    }

    /** After a sale: points earned and used, and any amount bought on credit. */
    void recordSale(Customer customer, Cashier cashier, Sale sale, int pointsEarned, int pointsUsed, BigDecimal credit) {
        customer.changePoints(pointsEarned - pointsUsed);
        customer.changeBalance(credit);
        if (pointsEarned != 0 || pointsUsed != 0 || credit.signum() != 0) {
            transactionRepository.save(new CustomerTransaction(customer, cashier, CustomerTransaction.Type.SALE,
                    pointsEarned - pointsUsed, credit, sale, null, null, LocalDateTime.now()));
        }
    }

    /** After a refund: earned points taken back, and the amount put back on the account if refunded to credit. */
    void recordRefund(Customer customer, Cashier cashier, Refund refund, int pointsTakenBack, BigDecimal creditRefunded) {
        customer.changePoints(-pointsTakenBack);
        customer.changeBalance(creditRefunded.negate());
        if (pointsTakenBack != 0 || creditRefunded.signum() != 0) {
            transactionRepository.save(new CustomerTransaction(customer, cashier, CustomerTransaction.Type.REFUND,
                    -pointsTakenBack, creditRefunded.negate(), refund.getSale(), refund, null, LocalDateTime.now()));
        }
    }

    private String newCardNumber() {
        String card;
        do {
            card = "99" + String.format("%08d", random.nextInt(100_000_000));
        } while (customerRepository.findByCardNumber(card).isPresent());
        return card;
    }

    private void requireFreeCard(String card, Long ownId) {
        customerRepository.findByCardNumber(card).filter(other -> !other.getId().equals(ownId)).ifPresent(other -> {
            throw new ValidationException("customer.cardTaken");
        });
    }

    private static void requireManager(Cashier actor) {
        if (!actor.getRole().isOperationalManager()) {
            throw new PermissionDeniedException("customer.creditLimitManagers");
        }
    }

    private static BigDecimal validLimit(BigDecimal limit) {
        if (limit.signum() < 0) {
            throw new ValidationException("customer.creditLimitNegative");
        }
        return limit.signum() == 0 ? null : limit.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal stripped(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros();
    }

    private static String requireName(CustomerRequest request) {
        if (request.fullName() == null || request.fullName().isBlank()) {
            throw new ValidationException("customer.nameRequired");
        }
        return request.fullName().trim();
    }

    private static void apply(Customer customer, CustomerRequest request) {
        customer.setPhone(blankToNull(request.phone()));
        customer.setEmail(blankToNull(request.email()));
        customer.setNotes(blankToNull(request.notes()));
        customer.setActive(request.active() == null || request.active());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
