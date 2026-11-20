CREATE OR REPLACE FUNCTION prevent_ledger_mutation()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Immutable ledger: Cannot delete ledger entries (%)', OLD.entry_id;
END IF;

    IF TG_OP = 'UPDATE' THEN
        IF OLD.status IN ('POSTED', 'REVERSED') THEN
            RAISE EXCEPTION 'Immutable ledger: Cannot update a % entry (%)', OLD.status, OLD.entry_id;
END IF;
        IF NEW.amount <> OLD.amount OR NEW.account_id <> OLD.account_id
           OR NEW.entry_type <> OLD.entry_type OR NEW.currency <> OLD.currency
           OR NEW.hash <> OLD.hash OR NEW.created_by <> OLD.created_by THEN
            RAISE EXCEPTION 'Immutable ledger: Core fields cannot be modified (%)', OLD.entry_id;
END IF;
END IF;

RETURN NEW;
END;
$$ LANGUAGE plpgsql;