CREATE TABLE payment
(
    id UUID PRIMARY KEY,

    amount NUMERIC(19,2) NOT NULL,

    currency VARCHAR(3) NOT NULL,

    merchant_id UUID NOT NULL,

    customer_id UUID,

    status VARCHAR(30) NOT NULL,

    description VARCHAR(255),

    created_at TIMESTAMP NOT NULL,

    updated_at TIMESTAMP NOT NULL
);