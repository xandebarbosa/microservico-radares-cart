-- ============================================
-- OTIMIZAÇÃO CRÍTICA DE ÍNDICES
-- Remove índices redundantes e cria os corretos
-- ============================================

-- 1. REMOVE índices redundantes (estão atrapalhando o planner)
DROP INDEX IF EXISTS idx_radares_busca_local;
DROP INDEX IF EXISTS idx_radares_data_hora;
DROP INDEX IF EXISTS idx_radares_rodovia;
DROP INDEX IF EXISTS idx_radares_data;
DROP INDEX IF EXISTS idx_radars_local_composite;

-- 2. ÍNDICE PRINCIPAL - Busca por Placa (mais comum)
-- GIN com pg_trgm para LIKE '%ABC%' ultrarrápido
CREATE INDEX IF NOT EXISTS idx_placa_gin ON radars_cart USING gin (placa gin_trgm_ops);

-- 3. ÍNDICE para filtros combinados (rodovia + data)
-- Cobre: WHERE rodovia=X AND data BETWEEN Y AND Z
CREATE INDEX IF NOT EXISTS idx_rodovia_data
ON radars_cart (rodovia, data DESC, hora DESC);

-- 4. ÍNDICE para busca geoespacial otimizada
-- Acelera JOIN com localizacao_radar
CREATE INDEX IF NOT EXISTS idx_localizacao_fk
ON radars_cart (localizacao_id) WHERE localizacao_id IS NOT NULL;

-- 5. ÍNDICE parcial para buscas recentes (últimos 30 dias)
-- 90% das buscas são de dados recentes
CREATE INDEX IF NOT EXISTS idx_recentes
ON radars_cart (data DESC, hora DESC, placa);

-- 6. ÍNDICE composto para a query de filtros do BFF
-- Ordem otimizada baseada em cardinalidade
CREATE INDEX IF NOT EXISTS idx_filtros_completos
ON radars_cart (data, placa, rodovia, km);

-- 7. ESTATÍSTICAS atualizadas (crítico!)
ANALYZE radars_cart;
ANALYZE localizacao_radar;

-- 8. OTIMIZAÇÃO: Aumenta work_mem para queries complexas (ajuste conforme RAM disponível)
-- Isso melhora sorts e hash joins
-- NOTA: Execute isso no postgresql.conf ou via ALTER SYSTEM
-- ALTER SYSTEM SET work_mem = '64MB';
-- SELECT pg_reload_conf();