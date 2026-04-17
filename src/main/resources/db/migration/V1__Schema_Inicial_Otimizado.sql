-- ==============================================================================
-- 1. HABILITAR EXTENSÕES
-- ==============================================================================
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS unaccent;

-- ==============================================================================
-- 2. TABELAS DE DOMÍNIO E LOCALIZAÇÃO
-- ==============================================================================
CREATE TABLE localizacao_radar (
    id BIGSERIAL PRIMARY KEY,
    concessionaria VARCHAR(255),
    rodovia VARCHAR(255),
    km VARCHAR(255),
    praca VARCHAR(255),
    localizacao GEOGRAPHY(Point, 4326)
);
CREATE INDEX idx_localizacao_gist ON localizacao_radar USING GIST (localizacao);

CREATE TABLE rodovias (
    id BIGSERIAL PRIMARY KEY,
    nome VARCHAR(50) NOT NULL UNIQUE
);
CREATE INDEX idx_rodovias_nome ON rodovias(nome);

CREATE TABLE kms_rodovia (
    id BIGSERIAL PRIMARY KEY,
    valor VARCHAR(20) NOT NULL,
    rodovia_id BIGINT NOT NULL,
    CONSTRAINT fk_kms_rodovia FOREIGN KEY (rodovia_id) REFERENCES rodovias(id) ON DELETE CASCADE
);
CREATE INDEX idx_kms_rodovia_valor ON kms_rodovia(valor);

-- ==============================================================================
-- 3. TABELA MESTRA DE PASSAGENS (PARTICIONADA)
-- ==============================================================================
CREATE TABLE radars_cart (
    id BIGSERIAL,
    data DATE NOT NULL,
    hora TIME NOT NULL,
    placa VARCHAR(7) NOT NULL,
    praca VARCHAR(255),
    rodovia VARCHAR(255) NOT NULL,
    km VARCHAR(255) NOT NULL,
    sentido VARCHAR(255) NOT NULL,
    localizacao_id BIGINT,

    -- Chave primária precisa incluir a chave de particionamento (data)
    CONSTRAINT pk_radars_cart PRIMARY KEY (id, data),

    -- Trava de Unicidade: Evita duplicatas e HABILITA o "ON CONFLICT DO NOTHING" do Batch Insert
    CONSTRAINT uk_radar_passagem UNIQUE (data, hora, placa, praca),

    CONSTRAINT fk_radars_localizacao FOREIGN KEY (localizacao_id) REFERENCES localizacao_radar(id)
) PARTITION BY RANGE (data);

-- ==============================================================================
-- 4. CRIAÇÃO DAS PARTIÇÕES
-- ==============================================================================
CREATE TABLE radars_cart_history PARTITION OF radars_cart FOR VALUES FROM (MINVALUE) TO ('2025-01-01');
CREATE TABLE radars_cart_2025 PARTITION OF radars_cart FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE radars_cart_2026 PARTITION OF radars_cart FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
CREATE TABLE radars_cart_2027 PARTITION OF radars_cart FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');
CREATE TABLE radars_cart_default PARTITION OF radars_cart DEFAULT;

-- ==============================================================================
-- 5. ÍNDICES DE ALTA PERFORMANCE
-- ==============================================================================

-- A. "Bala de Prata" para paginação global e DISTINCT ON (zera o Sort em memória)
CREATE INDEX idx_radars_cart_data_hora_placa ON radars_cart (data DESC, hora DESC, placa);

-- B. Busca de Placa Instantânea via ILIKE
CREATE INDEX idx_radars_cart_placa_gin ON radars_cart USING GIN (placa gin_trgm_ops);

-- C. Índice para Buscas Locais Combinadas (Busca exata do B-Tree)
CREATE INDEX idx_radars_cart_rodovia_km_sentido ON radars_cart (rodovia, km, sentido);

-- D. Índice de chave estrangeira
CREATE INDEX idx_radars_cart_loc_id ON radars_cart (localizacao_id);