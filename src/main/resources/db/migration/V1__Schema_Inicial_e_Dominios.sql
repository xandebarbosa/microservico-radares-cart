-- 1. Habilitar Extensões de Performance e Geoespacial
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pg_trgm; -- Para busca rápida de placa (LIKE '%ABC%')
CREATE EXTENSION IF NOT EXISTS unaccent;

-- 2. Tabela de Localização (Geoespacial)
CREATE TABLE localizacao_radar (
    id BIGSERIAL PRIMARY KEY,
    concessionaria VARCHAR(255),
        rodovia VARCHAR(255),
        km VARCHAR(255),
        praca VARCHAR(255),

        -- Coluna geoespacial usando o tipo 'geography' para cálculos precisos (lat/lon)
        -- SRID 4326 é o padrão para WGS 84 (GPS)
        localizacao GEOGRAPHY(Point, 4326)
);

CREATE INDEX idx_localizacao_gist ON localizacao_radar USING GIST (localizacao);

-- 3. Tabelas de Domínio (Para os Filtros do Front-end)

-- Rodovias (Ex: SP-270, BR-153)
CREATE TABLE rodovias (
    id BIGSERIAL PRIMARY KEY,
    nome VARCHAR(50) NOT NULL UNIQUE
);
CREATE INDEX idx_rodovias_nome ON rodovias(nome);

-- KMs da Rodovia (Ex: 234, 110+500)
CREATE TABLE kms_rodovia (
    id BIGSERIAL PRIMARY KEY,
    valor VARCHAR(20) NOT NULL,
    rodovia_id BIGINT NOT NULL,
    CONSTRAINT fk_kms_rodovia FOREIGN KEY (rodovia_id) REFERENCES rodovias(id) ON DELETE CASCADE
);
CREATE INDEX idx_kms_rodovia_valor ON kms_rodovia(valor);
