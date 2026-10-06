package com.supermarket.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** A stock-taking session: products are counted, then the differences are applied together. */
@Entity
@Table(name = "inventory_counts")
public class InventoryCount {

    public enum Status { OPEN, APPLIED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "started_by_id", nullable = false)
    @JsonIgnore
    private Cashier startedBy;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status = Status.OPEN;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "finished_by_id")
    @JsonIgnore
    private Cashier finishedBy;

    private LocalDateTime finishedAt;

    private String note;

    @OneToMany(mappedBy = "count", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    @JsonIgnore
    private List<InventoryCountLine> lines = new ArrayList<>();

    protected InventoryCount() {
    }

    public InventoryCount(Cashier startedBy, String note, LocalDateTime startedAt) {
        this.startedBy = startedBy;
        this.note = note;
        this.startedAt = startedAt;
    }

    public Optional<InventoryCountLine> findLine(Long productId) {
        return lines.stream().filter(line -> line.getProduct().getId().equals(productId)).findFirst();
    }

    public void addLine(InventoryCountLine line) {
        line.setCount(this);
        lines.add(line);
    }

    public void finish(Status status, Cashier by, LocalDateTime at) {
        this.status = status;
        this.finishedBy = by;
        this.finishedAt = at;
    }

    public Long getId() {
        return id;
    }

    public String getStartedByName() {
        return startedBy.getFullName();
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public Status getStatus() {
        return status;
    }

    public String getFinishedByName() {
        return finishedBy == null ? null : finishedBy.getFullName();
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public String getNote() {
        return note;
    }

    public List<InventoryCountLine> getLines() {
        return lines;
    }
}
