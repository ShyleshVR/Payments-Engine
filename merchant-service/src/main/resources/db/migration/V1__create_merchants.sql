CREATE TABLE merchants
(
    id UUID PRIMARY KEY,

    name VARCHAR(200) NOT NULL,
    email VARCHAR(320) NOT NULL,

    -- ACTIVE | SUSPENDED. A suspended merchant can't obtain new tokens.
    status VARCHAR(20) NOT NULL,

    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX uq_merchants_email ON merchants (lower(email));
