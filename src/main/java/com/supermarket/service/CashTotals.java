package com.supermarket.service;

import com.supermarket.model.CashMovement;
import com.supermarket.model.Refund;
import com.supermarket.model.Sale;
import com.supermarket.model.SalePayment;

import java.math.BigDecimal;
import java.util.Collection;

/**
 * Money totals for a set of sales and drawer movements, in LEK. Shift closing, the X report and the
 * Z report all use this, so they always agree.
 *
 * @param totalSales   what the customers owed (after discount)
 * @param cashReceived all cash handed over, foreign cash converted at the rate used
 * @param changeGiven  change handed back (always LEK cash)
 * @param cardSales    paid by card
 * @param cashIn       money put into the drawer outside of sales
 * @param cashOut      money taken out of the drawer outside of sales
 * @param cashRefunds  refunds paid back in cash from the drawer
 * @param cardRefunds  refunds paid back to a card
 * @param creditSales  charged to customers' accounts ("në borxh")
 * @param pointsUsed   paid with loyalty points (LEK value)
 * @param creditRefunds refunds put back on a customer's account
 */
public record CashTotals(
        BigDecimal totalSales,
        BigDecimal cashReceived,
        BigDecimal changeGiven,
        BigDecimal cardSales,
        BigDecimal cashIn,
        BigDecimal cashOut,
        BigDecimal cashRefunds,
        BigDecimal cardRefunds,
        BigDecimal creditSales,
        BigDecimal pointsUsed,
        BigDecimal creditRefunds
) {

    public static CashTotals of(Collection<Sale> sales, Collection<CashMovement> movements, Collection<Refund> refunds) {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal cash = BigDecimal.ZERO;
        BigDecimal change = BigDecimal.ZERO;
        BigDecimal card = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        BigDecimal points = BigDecimal.ZERO;
        for (Sale sale : sales) {
            total = total.add(sale.getTotalAmount());
            change = change.add(sale.getChangeAmount());
            for (SalePayment payment : sale.getPayments()) {
                switch (payment.getMethod()) {
                    case CARD -> card = card.add(payment.getAmountLek());
                    case CREDIT -> credit = credit.add(payment.getAmountLek());
                    case POINTS -> points = points.add(payment.getAmountLek());
                    default -> cash = cash.add(payment.getAmountLek());
                }
            }
        }
        BigDecimal in = BigDecimal.ZERO;
        BigDecimal out = BigDecimal.ZERO;
        for (CashMovement movement : movements) {
            if (movement.getType() == CashMovement.Type.IN) {
                in = in.add(movement.getAmount());
            } else {
                out = out.add(movement.getAmount());
            }
        }
        BigDecimal refundedCash = BigDecimal.ZERO;
        BigDecimal refundedCard = BigDecimal.ZERO;
        BigDecimal refundedCredit = BigDecimal.ZERO;
        for (Refund refund : refunds) {
            switch (refund.getMethod()) {
                case CARD -> refundedCard = refundedCard.add(refund.getTotalAmount());
                case CREDIT -> refundedCredit = refundedCredit.add(refund.getTotalAmount());
                default -> refundedCash = refundedCash.add(refund.getTotalAmount());
            }
        }
        return new CashTotals(total, cash, change, card, in, out, refundedCash, refundedCard, credit, points, refundedCredit);
    }

    /** Cash kept from sales: what customers handed over minus the change given back. */
    public BigDecimal cashSales() {
        return cashReceived.subtract(changeGiven);
    }

    /** What should be in the drawer: opening float + cash from sales + cash in - cash out - cash refunds. */
    public BigDecimal expectedCash(BigDecimal openingCash) {
        return openingCash.add(cashSales()).add(cashIn).subtract(cashOut).subtract(cashRefunds);
    }

    public BigDecimal totalRefunds() {
        return cashRefunds.add(cardRefunds).add(creditRefunds);
    }
}
