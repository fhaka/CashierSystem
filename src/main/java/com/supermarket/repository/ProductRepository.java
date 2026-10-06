package com.supermarket.repository;

import com.supermarket.model.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findByNameContainingIgnoreCaseOrBarcodeContainingIgnoreCase(String name, String barcode);

    Optional<Product> findByBarcode(String barcode);

    List<Product> findByActiveTrue();

    /**
     * Locks the products until the checkout transaction ends, so two tills cannot both sell the last item.
     * Ordered by id so concurrent checkouts always lock in the same order and cannot deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id in :ids order by p.id")
    List<Product> findAllForUpdate(@Param("ids") Collection<Long> ids);
}
