package com.coruja.schedulers;

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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Executa a cada 5 minutos (300.000 ms).
     * Atualiza a coluna localizacao_id na tabela radars_cart
     * cruzando dados com a tabela localizacao_radar.
     */
    @Scheduled(fixedRate = 300000)
    @Transactional
    public void vincularLocalizacoes() {
        logger.info("Iniciando job de vinculação de localizações (UPDATE SQL)...");

        // Esta query usa a extensão proprietária do PostgreSQL 'UPDATE ... FROM'
        // para fazer o join e atualizar em massa de forma muito eficiente.
        String sql = """
                UPDATE radars_cart r
                SET localizacao_id = l.id
                FROM localizacao_radar l
                WHERE r.rodovia = l.rodovia AND r.km = l.km
                    AND (r.localizacao_id IS NULL OR r.localizacao_id <> l.id)
                """;
        /*
           NOTA: O vinculo acima é feito pela coluna 'praca'.
           Se a sua lógica de negócio exigir validação também por 'rodovia' e 'km',
           altere o WHERE para:
           r.praca
           WHERE r.rodovia = l.rodovia AND r.km = l.km
        */

        try {
            int linhasAfetadas = jdbcTemplate.update(sql);
            if (linhasAfetadas > 0) {
                logger.info("Sucesso! {} registros de radares foram atualizados com a nova localização.", linhasAfetadas);
            } else {
                logger.info("Nenhum registro precisou ser atualizado nesta execução.");
            }
        } catch (Exception e) {
            logger.error("Erro ao executar o update de localizações: ", e);
        }
    }

}
