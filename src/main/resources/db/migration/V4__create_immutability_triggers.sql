CREATE OR REPLACE FUNCTION prevent_ledger_mutation()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        -- Only allow changing status if a reversal happens, nothing else can change
        IF OLD.status = 'POSTED' AND NEW.status != 'REVERSED' THEN
            RAISE EXCEPTION 'Immutable ledger: Cannot update a POSTED entry';
END IF;
    ELSIF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Immutable ledger: Cannot delete ledger entries';
END IF;
RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER enforce_ledger_immutability
    BEFORE UPDATE OR DELETE ON ledger_entries
FOR EACH ROW EXECUTE FUNCTION prevent_ledger_mutation();