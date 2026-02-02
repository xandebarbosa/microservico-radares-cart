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
    @Scheduled(fixedRate = 300000) // 5 minutos
    public void vincularLocalizacoes() {
        logger.info("Iniciando job de vinculação de localizações...");

        // QUERY EXPLICADA:
        // 1. REPLACE(..., '-', '') -> Iguala "SP-270" com "SP270".
        // 2. SPLIT_PART(..., '+', 1) -> Extrai "590" de "590+750".
        // 3. O WHERE final inclui 'data' para performance em partições.
        String sqlBatch = """
            WITH pending_batch AS (
                SELECT id, data, rodovia, km
                FROM radars_cart
                WHERE localizacao_id IS NULL
                LIMIT ?
            ),
            match_update AS (
                SELECT
                    pb.id AS radar_id,
                    pb.data AS radar_data,
                    lr.id AS loc_id
                FROM pending_batch pb
                JOIN localizacao_radar lr
                    ON REPLACE(TRIM(UPPER(pb.rodovia)), '-', '') = REPLACE(TRIM(UPPER(lr.rodovia)), '-', '')
                    AND TRIM(pb.km) = SPLIT_PART(TRIM(lr.km), '+', 1)
            )
            UPDATE radars_cart rc
            SET localizacao_id = mu.loc_id
            FROM match_update mu
            WHERE rc.id = mu.radar_id
              AND rc.data = mu.radar_data;
        """;

        try {
            long inicio = System.currentTimeMillis();
            long totalAtualizado = 0;
            int linhasAfetadas;

            do {
                linhasAfetadas = jdbcTemplate.update(sqlBatch, BATCH_SIZE);
                totalAtualizado += linhasAfetadas;

                if (linhasAfetadas > 0) {
                    logger.debug("Lote processado: {} radares vinculados.", linhasAfetadas);
                    Thread.sleep(50); // Pausa leve para respiro do DB
                }
            } while (linhasAfetadas >= BATCH_SIZE);

            long fim = System.currentTimeMillis();
            if (totalAtualizado > 0) {
                logger.info("✅ Sucesso! Total de {} radares vinculados em {} ms.", totalAtualizado, (fim - inicio));
            } else {
                logger.info("🏁 Nenhum novo vínculo encontrado com os critérios atuais.");
            }

        } catch (Exception e) {
            logger.error("❌ Erro crítico no job de localização: ", e);
        }
    }
}
