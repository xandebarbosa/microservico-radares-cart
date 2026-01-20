-- V8__Create_expression_indexes.sql
-- Índices para permitir que o UPDATE do Scheduler use busca indexada
-- em vez de calcular TRIM() em toda a tabela a cada execução.

CREATE INDEX IF NOT EXISTS idx_radars_trim_rodovia_km
ON radars_cart ((TRIM(rodovia)), (TRIM(km)));

CREATE INDEX IF NOT EXISTS idx_localizacao_trim_rodovia_km
ON localizacao_radar ((TRIM(rodovia)), (TRIM(km)));

ANALYZE radars_cart;
ANALYZE localizacao_radar;