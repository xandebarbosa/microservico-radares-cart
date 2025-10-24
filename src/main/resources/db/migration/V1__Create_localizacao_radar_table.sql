-- Habilita a extensão PostGIS (necessário se ainda não estiver habilitada no banco)
CREATE EXTENSION IF NOT EXISTS postgis;

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

-- Opcional: Criar um índice espacial para acelerar consultas de localização
CREATE INDEX idx_localizacao_radar_geom ON localizacao_radar USING GIST (localizacao);