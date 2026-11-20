# Submission Notes: Deliberate Errors Identified & AI Tools Disclosure

## 1. Deliberate Errors Identified & Fixed
1. **Part A6.1 Trial Balance Sign Handling:** Computing `SUM(DEBIT) - SUM(CREDIT)` uniformly across all account types misrepresents normal credit balances (Liabilities, Equity, Revenue). Fixed by accounting for normal balance direction per `account_type` while verifying overall `SUM(debits) == SUM(credits)`.
2. **Part A3.2 / Floating-Point Precision (Revolut Case Study):** Avoided IEEE 754 `FLOAT`/`DOUBLE` completely by enforcing Java `BigDecimal` (scale 4, `RoundingMode.HALF_UP`) and PostgreSQL `NUMERIC(19,4)` / `NUMERIC(18,8)`.
3. **Concurrent Refund Race Condition (Razorpay Case Study):** Prevented via `UNIQUE (original_journal_id, idempotency_key)` constraint in `partial_refunds` (V9) and transactional sum verification (`already_refunded + requested <= original_amount`).

## 2. AI Tools Disclosure (Section E5 Compliance)
- **Tools Used:** Google Gemini (for architecture brainstorming, debugging Flyway/Hibernate mappings, JUnit test structuring, and documentation formatting).
- **Human Ownership:** All code, SQL migrations (V1–V9), Docker setup, test executions (`./mvnw test`), and debugging were executed, verified, and understood by Mayank Gome locally.
