-- V3__Create_indexes_for_performance.sql

-- 1. Índice Composto para a busca mais comum (Local + Data)
-- Isso cobre: WHERE rodovia=? AND km=? AND sentido=? AND data=?
CREATE INDEX IF NOT EXISTS idx_radares_busca_local
ON radars_cart (rodovia, km, sentido, data, hora);

-- 2. Índice para busca apenas por Placa (caso seja usado isoladamente)
CREATE INDEX IF NOT EXISTS idx_radares_placa
ON radars_cart (placa);

-- 3. Índice para ordenação por Data/Hora (acelera o "ORDER BY data DESC, hora DESC")
CREATE INDEX IF NOT EXISTS idx_radares_data_hora
ON radars_cart (data DESC, hora DESC);

-- 4. Índices individuais para colunas de alta cardinalidade usadas em filtros isolados
CREATE INDEX IF NOT EXISTS idx_radares_rodovia ON radars_cart (rodovia);
CREATE INDEX IF NOT EXISTS idx_radares_data ON radars_cart (data);