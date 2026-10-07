CREATE TABLE merchant_credentials
(
    id UUID PRIMARY KEY,

    merchant_id UUID NOT NULL REFERENCES merchants (id),

    -- OAuth2 client_id, public. The secret is only ever returned once, at creation.
    client_id VARCHAR(64) NOT NULL UNIQUE,
    client_secret_hash VARCHAR(255) NOT NULL,

    -- ACTIVE | REVOKED. At most payflow.auth.max-active-credentials ACTIVE per merchant,
    -- enforced by the service under a row lock on the merchant.
    status VARCHAR(20) NOT NULL,

    created_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP
);

CREATE INDEX idx_merchant_credentials_merchant_id
    ON merchant_credentials (merchant_id);
