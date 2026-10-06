package com.supermarket.repository;

import com.supermarket.model.NumberSequence;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface NumberSequenceRepository extends JpaRepository<NumberSequence, String> {

    /** Locks the counter row until the transaction ends, so two tills never get the same number. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from NumberSequence s where s.name = :name")
    Optional<NumberSequence> findForUpdate(@Param("name") String name);
}
