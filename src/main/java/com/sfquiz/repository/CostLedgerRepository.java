package com.sfquiz.repository;

import com.sfquiz.entity.CostLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface CostLedgerRepository extends JpaRepository<CostLedger, Long> {

    @Query("select coalesce(sum(c.usd), 0) from CostLedger c where c.day = :day")
    BigDecimal sumUsdByDay(@Param("day") LocalDate day);
}
