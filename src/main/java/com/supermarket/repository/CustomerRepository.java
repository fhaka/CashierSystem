package com.supermarket.repository;

import com.supermarket.model.Customer;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findByCardNumber(String cardNumber);

    /** Search by card number, name or phone. */
    @Query("""
            select c from Customer c
            where lower(c.fullName) like lower(concat('%', :query, '%'))
               or c.cardNumber like concat('%', :query, '%')
               or c.phone like concat('%', :query, '%')
            order by c.fullName
            """)
    List<Customer> search(@Param("query") String query, Pageable page);

    List<Customer> findAllByOrderByFullNameAsc();
}
