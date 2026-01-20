-- 1. Habilita a extensão de trigramas (necessário superuser ou permissão)
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 2. Índice para busca parcial SUPER RÁPIDA na PLACA
CREATE INDEX idx_radars_placa_trgm ON radars_cart USING gin (placa gin_trgm_ops);

-- 3. Índice para busca parcial na RODOVIA
CREATE INDEX idx_radars_rodovia_trgm ON radars_cart USING gin (rodovia gin_trgm_ops);

-- 4. Índice composto para pesquisa por local (já deve existir, mas reforçando)
CREATE INDEX idx_radars_local_composite ON radars_cart (rodovia, km, sentido);