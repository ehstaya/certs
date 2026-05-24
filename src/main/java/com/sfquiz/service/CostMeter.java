package com.sfquiz.service;

import com.sfquiz.entity.CostLedger;
import com.sfquiz.repository.CostLedgerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

/** Tracks Anthropic spend and enforces the daily USD budget. Writes one
 *  CostLedger row per call so {@code /admin/uploads} can show what extraction
 *  is costing today. */
@Service
public class CostMeter {

    private static final Logger log = LoggerFactory.getLogger(CostMeter.class);

    /** Per-million-token prices in USD. Mirrored from claude-api skill (cached 2026-04-29). */
    private static final Map<String, ModelPrice> PRICES = Map.of(
            "claude-opus-4-7",   new ModelPrice("5.00", "25.00", "0.50", "6.25"),
            "claude-opus-4-6",   new ModelPrice("5.00", "25.00", "0.50", "6.25"),
            "claude-sonnet-4-6", new ModelPrice("3.00", "15.00", "0.30", "3.75"),
            "claude-haiku-4-5",  new ModelPrice("1.00",  "5.00", "0.10", "1.25")
    );

    /** Used when we encounter a model not in the table — assume Sonnet pricing. */
    private static final ModelPrice FALLBACK = PRICES.get("claude-sonnet-4-6");

    private final CostLedgerRepository ledger;
    private final BigDecimal dailyBudgetUsd;

    public CostMeter(CostLedgerRepository ledger,
                     @Value("${app.extraction.daily-budget-usd:2.0}") double dailyBudgetUsd) {
        this.ledger = ledger;
        this.dailyBudgetUsd = BigDecimal.valueOf(dailyBudgetUsd);
    }

    public BigDecimal dailyBudgetUsd() { return dailyBudgetUsd; }

    /** Spend so far today, UTC calendar day. Read-only. */
    public BigDecimal spentToday() {
        BigDecimal v = ledger.sumUsdByDay(LocalDate.now(ZoneOffset.UTC));
        return v == null ? BigDecimal.ZERO : v;
    }

    public BigDecimal remainingToday() {
        return dailyBudgetUsd.subtract(spentToday()).max(BigDecimal.ZERO);
    }

    /** True if today's spend has already hit the daily cap. */
    public boolean budgetExhausted() {
        return spentToday().compareTo(dailyBudgetUsd) >= 0;
    }

    /** Compute USD cost for a finished call and persist a ledger row.
     *  Returns the computed USD so the caller can log it. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BigDecimal record(String purpose, String model, long inputTokens, long outputTokens,
                             long cacheCreationTokens, long cacheReadTokens, Long uploadId) {
        ModelPrice price = PRICES.getOrDefault(model, FALLBACK);
        BigDecimal usd = price.cost(inputTokens, outputTokens, cacheCreationTokens, cacheReadTokens);
        CostLedger row = new CostLedger();
        row.setOccurredAt(Instant.now());
        row.setDay(LocalDate.now(ZoneOffset.UTC));
        row.setPurpose(purpose);
        row.setModel(model);
        row.setInputTokens(inputTokens);
        row.setOutputTokens(outputTokens);
        row.setCacheCreationTokens(cacheCreationTokens);
        row.setCacheReadTokens(cacheReadTokens);
        row.setUsd(usd);
        row.setUploadId(uploadId);
        ledger.save(row);
        log.info("anthropic call: purpose={} model={} in={} out={} cache_w={} cache_r={} cost=${}",
                purpose, model, inputTokens, outputTokens, cacheCreationTokens, cacheReadTokens, usd);
        return usd;
    }

    private record ModelPrice(BigDecimal input, BigDecimal output, BigDecimal cacheRead, BigDecimal cacheWrite) {
        ModelPrice(String input, String output, String cacheRead, String cacheWrite) {
            this(new BigDecimal(input), new BigDecimal(output), new BigDecimal(cacheRead), new BigDecimal(cacheWrite));
        }
        BigDecimal cost(long in, long out, long cacheCreate, long cacheRead_) {
            BigDecimal perMillion = new BigDecimal(1_000_000);
            return input.multiply(BigDecimal.valueOf(in))
                    .add(output.multiply(BigDecimal.valueOf(out)))
                    .add(cacheWrite.multiply(BigDecimal.valueOf(cacheCreate)))
                    .add(cacheRead.multiply(BigDecimal.valueOf(cacheRead_)))
                    .divide(perMillion, 6, RoundingMode.HALF_UP);
        }
    }
}
