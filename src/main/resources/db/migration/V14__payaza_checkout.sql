-- Giving v2: pay inside the room with Payaza's checkout popup, confirmed by Payaza.
-- A room registers its church's Payaza PUBLIC key (made to be shared; it can't move money out) and the payment
-- links that belong to it. Only that room uses the registration: elsewhere the same link opens in a new tab as
-- before, so nobody can put their own key behind someone else's link in someone else's room.
CREATE TABLE payaza_accounts (
    space_id       UUID         PRIMARY KEY REFERENCES spaces (id) ON DELETE CASCADE,
    public_key     VARCHAR(200) NOT NULL,
    mode           VARCHAR(8)   NOT NULL CHECK (mode IN ('TEST', 'LIVE')),
    merchant_name  VARCHAR(200) NOT NULL,
    currency_code  CHAR(3)      NOT NULL DEFAULT 'NGN',
    updated_by     UUID         REFERENCES participants (id) ON DELETE SET NULL,
    updated_at     TIMESTAMPTZ  NOT NULL
);

CREATE TABLE payaza_account_links (
    space_id         UUID NOT NULL REFERENCES payaza_accounts (space_id) ON DELETE CASCADE,
    payment_link_id  UUID NOT NULL REFERENCES payment_links (id),
    PRIMARY KEY (space_id, payment_link_id)
);

-- A gift paid in the room: our reference for it, the key and mode the checkout used (so we can still check it
-- with Payaza if the room's registration changes), and whether Payaza confirmed the result.
ALTER TABLE gifts ADD COLUMN checkout_reference  VARCHAR(64) UNIQUE;
ALTER TABLE gifts ADD COLUMN checkout_public_key VARCHAR(200);
ALTER TABLE gifts ADD COLUMN checkout_mode       VARCHAR(8) CHECK (checkout_mode IN ('TEST', 'LIVE'));
ALTER TABLE gifts ADD COLUMN verified            BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX ix_gifts_checkout_pending ON gifts (opened_at) WHERE status = 'OPENED' AND checkout_reference IS NOT NULL;
