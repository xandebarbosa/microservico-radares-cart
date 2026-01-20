-- 1. Habilita extensões necessárias (se ainda não existirem)
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS postgis;

-- 2. Limpeza de índices antigos/redundantes para não pesar na escrita
DROP INDEX IF EXISTS idx_radares_placa;
DROP INDEX IF EXISTS idx_radares_data_hora;
DROP INDEX IF EXISTS idx_placa_gin; -- Removemos para recriar corretamente

-- 3. ÍNDICE GIN PARA BUSCA DE PLACA (TOTAL OU PARCIAL)
-- Permite que 'LIKE %ABC%' seja indexado. Essencial para busca rápida sem filtro de data.
DROP INDEX IF EXISTS idx_radars_placa_trgm;
CREATE INDEX idx_radars_placa_trgm ON radars_cart USING gin (placa gin_trgm_ops);

-- 4. ÍNDICE COMPOSTO PARA DATA E HORA
-- Otimiza a ordenação padrão "ORDER BY data DESC, hora DESC"
CREATE INDEX IF NOT EXISTS idx_radars_data_hora ON radars_cart (data DESC, hora DESC);

-- 5. ÍNDICE ESPACIAL OTIMIZADO
-- Acelera a query ST_DWithin (Busca por Local)
CREATE INDEX IF NOT EXISTS idx_localizacao_geom ON localizacao_radar USING gist (localizacao);

-- 6. ATUALIZA ESTATÍSTICAS
ANALYZE radars_cart;
ANALYZE localizacao_radar;