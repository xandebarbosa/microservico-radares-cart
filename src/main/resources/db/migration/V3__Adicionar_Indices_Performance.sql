-- 1. Cria índice GIN para a coluna placa (usado em busca-placa e busca-local)
CREATE INDEX IF NOT EXISTS idx_radars_placa_trgm ON radars_cart USING gin (placa gin_trgm_ops);

-- 2. Cria índice GIN para a coluna rodovia (usado em busca-local)
CREATE INDEX IF NOT EXISTS idx_radars_rodovia_trgm ON radars_cart USING gin (rodovia gin_trgm_ops);