package com.supermarket.repository;

import com.supermarket.model.CustomerTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CustomerTransactionRepository extends JpaRepository<CustomerTransaction, Long> {

    @Query("select t from CustomerTransaction t where t.customer.id = :customerId order by t.createdAt desc, t.id desc")
    List<CustomerTransaction> findForCustomer(@Param("customerId") Long customerId);
}
