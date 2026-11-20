-- List active monthly partitions of ledger_entries
SELECT inhrelid::regclass AS partition_name
FROM pg_inherits
WHERE inhparent = 'ledger_entries'::regclass
ORDER BY partition_name;
