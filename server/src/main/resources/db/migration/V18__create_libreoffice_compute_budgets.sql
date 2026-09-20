-- Hard, shared LibreOffice compute allocation window for issue #9.
-- Reservations are append-only within a 30-minute session window; the Java
-- allocator atomically resets expired windows with an ON CONFLICT update.
CREATE TABLE libreoffice_compute_budgets (
    session_id UUID PRIMARY KEY,
    window_started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    reserved_usd NUMERIC(8, 2) NOT NULL
        CHECK (reserved_usd >= 0.00 AND reserved_usd <= 150.00),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_libreoffice_compute_budgets_updated_at
    ON libreoffice_compute_budgets (updated_at);
