package com.coruja.schedulers;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class LocalizacaoScheduler {

    private static final Logger logger = LoggerFactory.getLogger(LocalizacaoScheduler.class);
    private static final int BATCH_SIZE = 1000; // Processa 5.000 registros por lote

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Este método roda assim que o Spring inicia.
    // Se não aparecer no log, a classe não está sendo lida (erro de pacote/scan).
    @PostConstruct
    public void init() {
        logger.info(">>> LocalizacaoScheduler carregado com sucesso! O primeiro job rodará em breve.");
    }

    /**
     * Executa a cada 5 minutos (300.000 ms).
     * Atualiza a coluna localizacao_id na tabela radars_cart
     * cruzando dados com a tabela localizacao_radar.
     */
    @Scheduled(fixedRate = 300000) // Executa a cada 5 minutos
    public void vincularLocalizacoes() {
        logger.info("Iniciando job de vinculação (Estratégia Leve)...");

        // QUERY OTIMIZADA:
        // 1. Seleciona APENAS radares sem localização (pending_batch)
        // 2. Busca a localização APENAS para esses radares
        // 3. Atualiza
        String sqlBatch = """
            WITH pending_batch AS (
                SELECT id, rodovia, km
                FROM radars_cart
                WHERE localizacao_id IS NULL -- Foco prioritário em novos registros
                LIMIT ?
            ),
            match_update AS (
                SELECT pb.id AS radar_id, lr.id AS loc_id
                FROM pending_batch pb
                JOIN localizacao_radar lr 
                    ON TRIM(pb.rodovia) = TRIM(lr.rodovia) 
                    AND TRIM(pb.km) = TRIM(lr.km)
            )
            UPDATE radars_cart rc
            SET localizacao_id = mu.loc_id
            FROM match_update mu
            WHERE rc.id = mu.radar_id;
        """;

        long totalAtualizado = 0;
        int linhasAfetadas;

        try {
            long inicio = System.currentTimeMillis();

            // Processa em loop até não haver mais registros pendentes no lote
            do {
                linhasAfetadas = jdbcTemplate.update(sqlBatch, BATCH_SIZE);
                totalAtualizado += linhasAfetadas;

                if (linhasAfetadas > 0) {
                    logger.debug("Lote processado: {} radares vinculados.", linhasAfetadas);
                    // Pausa de segurança para liberar I/O do banco
                    Thread.sleep(100);
                }

            } while (linhasAfetadas >= BATCH_SIZE); // Continua se o lote estava cheio

            long fim = System.currentTimeMillis();

            if (totalAtualizado > 0) {
                logger.info("✅ Sucesso! Total de {} radares vinculados em {} ms.", totalAtualizado, (fim - inicio));
            } else {
                logger.info("🏁 Nenhum novo vínculo pendente.");
            }

        } catch (Exception e) {
            logger.error("❌ Erro no job de localização: ", e);
        }
    }

}
