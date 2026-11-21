# Submission Notes — BED-6C Double-Entry Ledger System

## 1. Deliberate Errors & Specification Traps Identified in the Brief

During implementation and audit against Parts A, B, and C, the following errors in the project brief were caught and corrected in code:

1. **Part A1.3 — Unbalanced P2P Transfer Example (Page 5):**
    - *Error in brief:* Credits User A Wallet (`5,010.00`) + Credits Transaction Fee Revenue (`10.00`) = `5,020.00` total credits against only `5,000.00` debit (off by `20.00`).
    - *Fix in code:* `LedgerService.postTransaction()` rejects any journal where `SUM(Debits) != SUM(Credits)` per currency before commit.
2. **Part A1.2 — Customer Wallet Classified as an Asset (Pages 4–5):**
    - *Accounting flaw:* From the neo-bank's balance-sheet perspective, customer wallet balances are funds owed *to* customers (a **Liability**, just like `2001 Customer Deposit Liability`), while the bank's own nostro/cash reserve is the Asset. Treating `1001 Customer Wallet` as an Asset in the CoA table is a simplified simulation convention; in production, customer sub-wallets are Liability sub-ledgers backed by a bank Cash/Nostro Asset account.
3. **Part A2.3 — `UNIQUE` Constraint on `idempotency_key` per Ledger Line (Page 7):**
    - *Error in brief:* Placing a single `UNIQUE` constraint on `ledger_entries.idempotency_key` breaks multi-line journal entries (every journal has at least 2 lines sharing one client request key). Furthermore, on a range-partitioned table (`V5`), any `UNIQUE` index must include the partition key (`effective_date`), which cannot enforce global uniqueness across months.
    - *Fix in code:* Dedicated `idempotency_keys` table (`V7`) with `PRIMARY KEY (user_id, idempotency_key)` claimed atomically via `INSERT ... ON CONFLICT DO NOTHING`, plus per-line suffixed keys (`<key>-0`, `<key>-1`) on `ledger_entries`.
4. **Part A3.2 — Multi-Currency FX Example Imbalance (Page 10):**
    - *Error in brief:* On the INR leg, `FX Conversion Holding` is credited `8,342.00` and `FX Revenue` is credited `41.71` (`8,383.71` total credits), while `User B Wallet` is debited only `8,300.29` (unbalanced by `83.42`).
    - *Fix in code:* `ExchangeRateService.convertUsdToInr()` posts two independently balanced single-currency legs (`USD`: `100.00` debit = `100.00` credit; `INR`: `8,342.00` debit = `8,300.29 + 41.71` credits).
5. **Part A5.1 — Unbalanced Merchant Payment & Reversal Example (Page 15):**
    - *Error in brief:* Original entry credits Customer Wallet (`1,020.00`) + credits Fee Revenue (`20.00`) = `1,040.00` credits against `1,000.00` debit.
    - *Fix in code:* `LedgerService.revertTransaction()` mirrors strictly balanced posted journals, and `TransactionEngineService.processPartialRefund()` derives the true original principal and fee directly from PostgreSQL under a per-journal advisory lock.
6. **Part A6.1 — Trial Balance SQL Sign & Multi-Currency Bug (Page 17):**
    - *Error in brief:* The sample SQL computes `DEBIT - CREDIT` uniformly for every account type (inverting Credit-normal Liability, Equity, and Revenue accounts) and omits `currency` from `GROUP BY`, mixing USD and INR balances together.
    - *Fix in code:* `LedgerEntryRepository` groups by `TRIM(e.currency)`, filters by `effective_date <= :asOfDate`, and computes normal balances by account type (`Asset`/`Expense`: `DEBIT - CREDIT`; `Liability`/`Equity`/`Revenue`: `CREDIT - DEBIT`).
7. **Part A9.1 — Non-Immutable `NOW()` in Partial Index Predicate (Page 22):**
    - *Error in brief:* `CREATE UNIQUE INDEX ... WHERE expires_at > NOW()` fails in PostgreSQL because `NOW()` is `STABLE`, not `IMMUTABLE`, and PostgreSQL forbids non-immutable functions in index predicates.
    - *Fix in code:* Primary key `(user_id, idempotency_key)` on `idempotency_keys` (`V7`) without a `NOW()` predicate; expiry is checked in queries/cleanup jobs.
8. **Part B Scenario 2 — TCS Posting Direction & LRS / Section 206C(1G) Figures (Pages 34–35):**
    - *Errors in brief:*
        - The brief says **"Debit TCS Payable"** when collecting TCS from a customer. Collecting tax owed to the government *increases* a Liability (`2020 TCS Payable`), which must be a **CREDIT** (not a Debit).
        - Under RBI's **Liberalised Remittance Scheme (LRS)**, the statutory annual limit for resident individuals is **USD 250,000 per financial year** (the brief's USD 500/month and USD 2,500/year figures are internal neo-bank Tier-2 KYC caps, not statutory LRS limits).
        - Under **Section 206C(1G)** of the Income Tax Act, the brief cites a flat **5% TCS above INR 7 lakh**; in practice, rates vary by remittance purpose (e.g., 20% for general LRS remittances above the statutory threshold vs. 0.5%/5% for education/medical), and statutory thresholds are periodically updated by India's Finance Acts (e.g., revised to INR 10 lakh in Finance Act 2025—verify current FY applicability for production deployments).

---

## 2. Architecture Decisions, Trade-Offs & Verified Status

- **Stack:** Java 21, Spring Boot 4.1.2, PostgreSQL 15, Flyway (`V1`–`V13`, `validate-on-migrate` enabled).
- **Immutability:** Two enforced layers—database trigger `prevent_ledger_mutation()` (`V11`, superseding `V4`) blocking `UPDATE`/`DELETE` on posted rows + global 9-field SHA-256 hash chain ordered by `chain_seq` under `pg_advisory_xact_lock`.
- **Concurrency:** Deadlock-free `SELECT ... FOR UPDATE` in sorted UUID order + per-account/per-journal `pg_advisory_xact_lock`.
- **Multi-Currency & Revaluation:** Immutable `exchange_rate_snapshots` with stale-rate rejection; persistent `fx_revaluation_state` (`V12`) posting only incremental deltas to `4004`/`5011` via a separate `@Scheduled` bean (`FxRevaluationScheduler`); separate rounding accounts `4005`/`5012` (`V13`).
- **Verified Build:** `./mvnw clean verify` passes **46/46 JUnit tests** (unit, JPA/PostgreSQL integration, MockMvc controller, 50-thread withdrawal, 10-thread concurrent partial refund, 10-thread concurrent reversal, trigger, and real DB tamper tests) and meets the **>= 80% JaCoCo line coverage** gate.
- **Known Scope Trade-Offs:** Authentication/JWT and per-customer sub-wallet onboarding are outside this core ledger scope; the 20 transaction types use configurable fee/tax ratios (`application.properties`) and `TransactionValidator` rules against the seeded Chart of Accounts.
- **Partition Scope (V5 + Maintenance Scripts):** `V5__partition_ledger_entries.sql` pre-creates monthly partitions for Nov 2026–Jan 2027 plus a `DEFAULT` partition so inserts never fail on fresh setup; future monthly partitions are provisioned idempotently by `scripts/manage-partitions.sql` and old partitions are detached non-blockingly via `scripts/archive-partition.sql`.
- **REST-First Hash Chain Verification:** Instead of a standalone CLI script, full cryptographic chain verification is exposed via `GET /api/v1/audit/verify` (invocable from any terminal via `curl`).
- **Java/Maven Repository Layout:** Per the brief's allowance for Java/Maven (`pom.xml`), migrations and seed data live under `src/main/resources/db/migration/` (`V1`–`V13`) rather than a Node-style `migrations/` + `seeds/` root layout.
- **Dev-Only Local Credentials:** Default credentials (`root` / `rootpassword`) in `src/main/resources/application.properties` and `docker-compose.yml` are strictly for local development and CI; production deployments override them via environment variables (documented in `.env.example`, with `.env` excluded in `.gitignore`).
---

## 3. AI Tools Usage Disclosure (Section E5)

- **Tools Used:** Google Gemini and Anthropic Claude.
- **Areas of Assistance:** Auditing the codebase against the 1,000-point rubric, identifying deliberate errors in the brief, resolving Flyway/PostgreSQL partitioning and trigger edge cases, structuring MockMvc/concurrency tests, and reviewing Part C case studies.
- **Ownership & Verification:** Every file, migration (`V1`–`V13`), and test was reviewed, executed locally against PostgreSQL 15 via Docker Compose and Maven (`46/46` passing, JaCoCo `>= 80%` check met), and verified for viva defense.