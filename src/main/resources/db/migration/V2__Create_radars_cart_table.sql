CREATE TABLE radars_cart (
    id BIGSERIAL PRIMARY KEY,
    data DATE NOT NULL,
    hora TIME NOT NULL,
    placa VARCHAR(7) NOT NULL,
    praca VARCHAR(255) NOT NULL,
    rodovia VARCHAR(255) NOT NULL,
    km VARCHAR(255) NOT NULL,
    sentido VARCHAR(255) NOT NULL,
    localizacao_id BIGINT,

        -- Constraint da Chave Estrangeira
        CONSTRAINT fk_radars_localizacao
            FOREIGN KEY(localizacao_id)
            REFERENCES localizacao_radar(id)
            ON DELETE SET NULL -- Ou ON DELETE CASCADE, dependendo da sua regra de negócio
);

CREATE INDEX idx_radars_cart_placa ON radars_cart(placa);