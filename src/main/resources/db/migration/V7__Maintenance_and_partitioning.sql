-- ============================================
-- MANUTENÇÃO E OTIMIZAÇÃO CONTÍNUA
-- ============================================

-- 1. VACUUM e ANALYZE automáticos (configure no postgresql.conf)
-- autovacuum = on
-- autovacuum_vacuum_scale_factor = 0.1
-- autovacuum_analyze_scale_factor = 0.05

-- 2. REMOÇÃO DE DADOS ANTIGOS (>6 meses)
-- Execute mensalmente via cron job
CREATE OR REPLACE FUNCTION limpar_dados_antigos()
RETURNS void AS $$
BEGIN
    DELETE FROM radars_cart
    WHERE data < CURRENT_DATE - INTERVAL '180 days';

    RAISE NOTICE 'Limpeza concluída: % registros removidos', ROW_COUNT;
END;
$$ LANGUAGE plpgsql;

-- 3. PARTICIONAMENTO POR DATA (Opcional - para tabelas MUITO grandes)
-- Aumenta drasticamente a performance de queries com filtro de data
/*
CREATE TABLE radars_cart_new (
    LIKE radars_cart INCLUDING ALL
) PARTITION BY RANGE (data);

-- Cria partições mensais
CREATE TABLE radars_cart_2025_01 PARTITION OF radars_cart_new
    FOR VALUES FROM ('2025-01-01') TO ('2025-02-01');

CREATE TABLE radars_cart_2025_02 PARTITION OF radars_cart_new
    FOR VALUES FROM ('2025-02-01') TO ('2025-03-01');

-- ... criar partições conforme necessário

-- Migrar dados (cuidado!)
-- INSERT INTO radars_cart_new SELECT * FROM radars_cart;
-- DROP TABLE radars_cart;
-- ALTER TABLE radars_cart_new RENAME TO radars_cart;
*/

-- 4. ESTATÍSTICAS OTIMIZADAS
ALTER TABLE radars_cart ALTER COLUMN placa SET STATISTICS 1000;
ALTER TABLE radars_cart ALTER COLUMN data SET STATISTICS 1000;
ALTER TABLE radars_cart ALTER COLUMN rodovia SET STATISTICS 500;

-- 5. REINDEX periódico (Execute mensalmente)
-- REINDEX TABLE CONCURRENTLY radars_cart;

-- 6. ATUALIZA ESTATÍSTICAS
ANALYZE radars_cart;
ANALYZE localizacao_radar;

-- 7. MONITORAMENTO DE ÍNDICES NÃO UTILIZADOS
-- Execute periodicamente para identificar índices desnecessários
CREATE OR REPLACE VIEW v_indices_nao_utilizados AS
SELECT
    schemaname,
    tablename,
    indexname,
    idx_scan,
    pg_size_pretty(pg_relation_size(indexrelid)) AS index_size
FROM pg_stat_user_indexes
WHERE idx_scan = 0
AND schemaname = 'public'
AND tablename IN ('radars_cart', 'localizacao_radar')
ORDER BY pg_relation_size(indexrelid) DESC;

-- 8. MONITORAMENTO DE PERFORMANCE DE QUERIES
-- Habilite pg_stat_statements para análise
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;