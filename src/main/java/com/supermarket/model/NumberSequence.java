package com.supermarket.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A named counter (for example invoice numbers). Read with a row lock and incremented in the same transaction. */
@Entity
@Table(name = "number_sequences")
public class NumberSequence {

    @Id
    @Column(length = 40)
    private String name;

    @Column(name = "current_value", nullable = false)
    private long currentValue;

    protected NumberSequence() {
    }

    public long next() {
        return ++currentValue;
    }

    public String getName() {
        return name;
    }

    public long getCurrentValue() {
        return currentValue;
    }
}
