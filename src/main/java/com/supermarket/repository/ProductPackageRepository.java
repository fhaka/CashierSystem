package com.supermarket.repository;

import com.supermarket.model.ProductPackage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductPackageRepository extends JpaRepository<ProductPackage, Long> {

    @Query("select p from ProductPackage p join fetch p.product where p.barcode = :barcode")
    Optional<ProductPackage> findByBarcode(@Param("barcode") String barcode);

    @Query("select p from ProductPackage p where p.product.id = :productId order by p.pieces")
    List<ProductPackage> findForProduct(@Param("productId") Long productId);

    @Query("select p from ProductPackage p join fetch p.product where p.active = true")
    List<ProductPackage> findAllActive();

    boolean existsByBarcode(String barcode);
}
