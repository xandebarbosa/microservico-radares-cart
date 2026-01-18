-- V4__Create_index_rodovia_km.sql

-- Este índice acelera drasticamente a busca "SELECT DISTINCT km FROM radars WHERE rodovia = ?"
-- O banco não precisará mais ler a tabela inteira, apenas este índice pequeno.
CREATE INDEX IF NOT EXISTS idx_radars_rodovia_km
ON radars_cart (rodovia, km);