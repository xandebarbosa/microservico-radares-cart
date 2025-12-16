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
    @Scheduled(fixedRate = 10000)
    @Transactional
    public void vincularLocalizacoes() {
        logger.info("Iniciando job de vinculação de localizações (UPDATE SQL)...");

        // Esta query usa a extensão proprietária do PostgreSQL 'UPDATE ... FROM'
        // para fazer o join e atualizar em massa de forma muito eficiente.
        String sql = """
                UPDATE radars_cart AS rc
                SET
                    localizacao_id = lr.id
                FROM
                    localizacao_radar AS lr
                WHERE
                    TRIM(rc.rodovia) = TRIM(lr.rodovia)
                    AND TRIM(rc.km) = TRIM(lr.km)
                    AND (rc.localizacao_id IS NULL OR rc.localizacao_id <> lr.id)
                """;
        /*
           NOTA: O vinculo acima é feito pela coluna 'praca'.
           Se a sua lógica de negócio exigir validação também por 'rodovia' e 'km',
           altere o WHERE para:
           r.praca
           WHERE r.rodovia = l.rodovia AND r.km = l.km
        */

        try {
            long inicio = System.currentTimeMillis();
            int linhasAfetadas = jdbcTemplate.update(sql);
            long fim = System.currentTimeMillis();
            if (linhasAfetadas > 0) {
                logger.info("Sucesso! {} radares foram vinculados à sua localização geográfica em {} ms.", linhasAfetadas, (fim - inicio));
            } else {
                logger.info("Job executado em {} ms. Nenhum novo vínculo necessário no momento.", (fim - inicio));
            }
        } catch (Exception e) {
            logger.error("Erro ao executar o update de localizações: ", e);
        }
    }

}
