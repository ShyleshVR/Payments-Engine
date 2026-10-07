-- RSA key pair used to sign access tokens; the public half is published at /oauth2/jwks.
-- Kept in the database so every instance signs with the same key and tokens survive restarts.
-- The private key is stored unencrypted: acceptable for local development only. It moves to a
-- KMS / secret store in the Kubernetes phase.
CREATE TABLE jwt_signing_keys
(
    kid VARCHAR(64) PRIMARY KEY,

    public_key TEXT NOT NULL,   -- base64 X.509 SubjectPublicKeyInfo
    private_key TEXT NOT NULL,  -- base64 PKCS#8

    -- ACTIVE | RETIRED. Exactly one ACTIVE key signs; rotation would add a new ACTIVE key and
    -- retire the old one once its tokens have expired.
    status VARCHAR(20) NOT NULL,

    created_at TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX uq_jwt_signing_keys_one_active
    ON jwt_signing_keys (status)
    WHERE status = 'ACTIVE';
