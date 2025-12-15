package com.coruja.services;

import com.coruja.dto.FilterOptionsDTO;
import com.coruja.dto.RadarsDTO;
import com.coruja.entities.Radars;
import com.coruja.repositories.RadarsRepository;
import com.coruja.specifications.RadarsSpecification;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.modelmapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


@Service
@Slf4j
public class RadarsService {



    @Value("${rabbitmq.exchange.name}")
    private String exchangeName;

    @Value("${rabbitmq.routing.key}")
    private String routingKey;

    private final RadarsRepository radarsRepository;
    private final RabbitTemplate rabbitTemplate;
    private final ModelMapper modelMapper;

    // Thread Pool dedicada para evitar bloquear o servidor principal
    private final ExecutorService executorService = Executors.newFixedThreadPool(5);

    public  RadarsService(RadarsRepository radarsRepository, RabbitTemplate rabbitTemplate, ModelMapper modelMapper) {
        this.radarsRepository = radarsRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.modelMapper = modelMapper;
    }

    @jakarta.annotation.PostConstruct
    public void checkDatabase() {
        long count = radarsRepository.count();
        log.info("=================================================");
        log.info("📊 VERIFICAÇÃO DE BANCO DE DADOS (CART)");
        log.info("📊 Total de Radares encontrados: {}", count);
        log.info("=================================================");

        if (count == 0) {
            log.warn("⚠️ O BANCO ESTÁ VAZIO! As listas de filtro virão vazias.");
        }
    }

    /**
     * Método UNIFICADO para buscar radares com filtros dinâmicos e opcionais.
     * Este método substitui getAllRadars, buscarPorPlaca e buscarPorLocal.
     *
     * @param placa       Placa do veículo (opcional)
     * @param rodovia     Nome da rodovia (opcional)
     * @param km          Quilômetro da rodovia (opcional)
     * @param sentido     Sentido da via (opcional)
     * @param data        Data do registro (opcional)
     * @param horaInicial Hora inicial do intervalo (opcional)
     * @param horaFinal   Hora final do intervalo (opcional)
     * @param pageable    Informações de paginação
     * @return Uma página de RadarsDTO que corresponde aos filtros.
     */

//    public Page<RadarsDTO> buscarComFiltros(
//            String placa, String praca, String rodovia, String km, String sentido,
//            LocalDate data, LocalTime horaInicial, LocalTime horaFinal,
//            Pageable pageable
//    ) {
//        try {
//            if (placa != null) placa = URLDecoder.decode(placa, StandardCharsets.UTF_8);
//            if (praca != null) praca = URLDecoder.decode(praca, StandardCharsets.UTF_8);
//            if (rodovia != null) rodovia = URLDecoder.decode(rodovia, StandardCharsets.UTF_8);
//            if (km != null) km = URLDecoder.decode(km, StandardCharsets.UTF_8);
//            if (sentido != null) sentido = URLDecoder.decode(sentido, StandardCharsets.UTF_8);
//        } catch (Exception e) {
//            log.error("Erro ao decodificar parâmetros da URL.", e);
//        }
//
//        String finalPlaca = placa;
//        String finalPraca = praca;
//        String finalRodovia = rodovia;
//        String finalSentido = sentido;
//        String finalKm = km;
//        Specification<Radars> spec = (root, query, criteriaBuilder) -> {
//            List<Predicate> predicates = new ArrayList<>();
//
//            // Lógica de filtragem robusta que ignora maiúsculas/minúsculas e espaços
//            if (finalPlaca != null && !finalPlaca.isBlank()) {
//                predicates.add(criteriaBuilder.equal(
//                        criteriaBuilder.lower(root.get("placa")),
//                        finalPlaca.toLowerCase().trim()
//                ));
//            }
//            if (finalPraca != null && !finalPraca.isBlank()) {
//                predicates.add(criteriaBuilder.equal(
//                        criteriaBuilder.lower(root.get("praca")),
//                        finalPraca.toLowerCase().trim()
//                ));
//            }
//            if (finalRodovia != null && !finalRodovia.isBlank()) {
//                predicates.add(criteriaBuilder.equal(
//                        criteriaBuilder.lower(root.get("rodovia")),
//                        finalRodovia.toLowerCase().trim()
//                ));
//            }
//            if (finalSentido != null && !finalSentido.isBlank()) {
//                predicates.add(criteriaBuilder.equal(
//                        criteriaBuilder.lower(root.get("sentido")),
//                        finalSentido.toLowerCase().trim()
//                ));
//            }
//
//            // Lógica de comparação especial para o KM, que ignora espaços e o sinal de '+'
//            if (finalKm != null && !finalKm.isBlank()) {
//                String kmLimpo = finalKm.replaceAll("[\\s+]", "");
//                Expression<String> kmDoBanco = root.get("km");
//                Expression<String> kmSemEspacos = criteriaBuilder.function("REPLACE", String.class, kmDoBanco, criteriaBuilder.literal(" "), criteriaBuilder.literal(""));
//                Expression<String> kmSemEspacosOuSinal = criteriaBuilder.function("REPLACE", String.class, kmSemEspacos, criteriaBuilder.literal("+"), criteriaBuilder.literal(""));
//
//                predicates.add(criteriaBuilder.equal(
//                        criteriaBuilder.lower(kmSemEspacosOuSinal),
//                        kmLimpo.toLowerCase()
//                ));
//            }
//
//            // Filtros de data e hora
//            if (data != null) {
//                predicates.add(criteriaBuilder.equal(root.get("data"), data));
//            }
//            if (horaInicial != null) {
//                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("hora"), horaInicial));
//            }
//            if (horaFinal != null) {
//                predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("hora"), horaFinal));
//            }
//
//            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
//        };
//
//        return radarsRepository.findAll(spec, pageable).map(this::converterParaDTO);
//    }

    @Transactional(readOnly = true)
    public Page<RadarsDTO> buscarComFiltros(String placa, String praca, String rodovia, String km, String sentido, LocalDate data, LocalTime horaInicial, LocalTime horaFinal, Pageable pageable) {
        // Usa a Specification que você criou anteriormente
        Specification<Radars> spec = Specification.where(RadarsSpecification.comPlaca(placa))
                .and(RadarsSpecification.comPraca(praca))
                .and(RadarsSpecification.comRodovia(rodovia))
                .and(RadarsSpecification.comKm(km))
                .and(RadarsSpecification.comSentido(sentido))
                .and(RadarsSpecification.comData(data))
                .and(RadarsSpecification.comHoraEntre(horaInicial, horaFinal));

        return radarsRepository.findAll(spec, pageable)
                .map(this::converterParaDTO);
    }

    /**
     * Busca ESPECÍFICA por placa.
     */
    @Transactional(readOnly = true)
    public Page<RadarsDTO> buscarApenasPorPlaca(String placa, Pageable pageable) {
        if (placa == null || placa.isBlank()) {
            throw new IllegalArgumentException("O parâmetro 'placa' é obrigatório.");
        }
        return radarsRepository.findByPlaca(placa, pageable).map(this::converterParaDTO);
    }

    /**
     * Busca ESPECÍFICA por local.
     */
    public Page<RadarsDTO> buscarApenasPorLocal(String rodovia, String km, String sentido, Pageable pageable) {
        if (rodovia == null || rodovia.isBlank() || km == null || km.isBlank() || sentido == null || sentido.isBlank()) {
            throw new IllegalArgumentException("Os parâmetros 'rodovia', 'km' e 'sentido' são obrigatórios.");
        }
        return radarsRepository.findByRodoviaAndKmAndSentido(rodovia, km, sentido, pageable).map(this::converterParaDTO);
    }

    /**
     * Salva as leituras dos radares e publica as placas detectadas no RabbitMQ
     * de forma resiliente.
     */
    @Transactional
    public void saveRadars(List<Radars> radarsList) {
        if (radarsList == null || radarsList.isEmpty()) {
            return;
        }

        // 1. Salva todas as entidades e captura a lista de entidades salvas.
        //    A lista 'savedRadars' agora contém as entidades com os IDs preenchidos.
        List<Radars> savedRadars = radarsRepository.saveAll(radarsList);

        log.info("{} registros salvos no banco de dados com sucesso.", savedRadars.size());

        // 2. Itera sobre a lista de entidades JÁ SALVAS para enviar ao RabbitMQ.
        savedRadars.forEach(this::enviarMensagemParaRabbitMQ);
    }

    /**
     * NOVO: Método auxiliar para encapsular a lógica de envio e o tratamento de erro.
     * @param radar O objeto radar para o qual a mensagem será enviada.
     */
    private void enviarMensagemParaRabbitMQ(Radars radar) {
        if (!isValidRadar(radar)) { // Lógica de validação em um método auxiliar
            log.warn("Dados incompletos para a placa: {}. Mensagem não será enviada.", radar.getPlaca());
            return;
        }

        String mensagem = formatMessage(radar);

        try {
            rabbitTemplate.convertAndSend(exchangeName, routingKey, mensagem);
            log.info("Mensagem enviada para RabbitMQ com routingKey [{}]: {}", routingKey, mensagem);
        } catch (AmqpException e) {
            // Tratamento de erro resiliente
            log.warn("Falha ao enviar mensagem para RabbitMQ - Placa: {}. Causa: {}", radar.getPlaca(), e.getMessage());
        }
    }

    // Métodos auxiliares para manter o código limpo
    private boolean isValidRadar(Radars radar) {
        return radar != null && radar.getData() != null && radar.getHora() != null && radar.getPlaca() != null;
    }

    private String formatMessage(Radars radar) {
        String concessionaria = routingKey.split("\\.")[1].toUpperCase();
        return String.format("%s|%s|%s|%s|%s|%s|%s|%s",
                concessionaria, radar.getData(), radar.getHora(), radar.getPlaca(),
                radar.getPraca(), radar.getRodovia(), radar.getKm(), radar.getSentido());
    }

    /**
     * Busca opções de filtro.
     * Cacheado por 1 hora (conforme RedisConfig).
     * Se falhar, retorna vazio mas loga o erro real.
     */
    @Cacheable(value = "opcoes-filtro-cart-v2", unless = "#result == null || #result.rodovias.isEmpty()")
    @Transactional(readOnly = true) // Importante para performance no Postgres
    public FilterOptionsDTO getFilterOptions() {
        log.info("🔍 [Leitura] Buscando filtros. Se aparecer este log, foi Cache Miss (lento).");
        return buscarDadosNoBanco();
    }

    /**
     * Busca KMs por Rodovia.
     * 1. Tenta Redis (rápido).
     * 2. Se não tiver, busca no banco e salva.
     */
    @Cacheable(value = "kms-rodovia-cart-v2", key = "#rodovia", unless = "#result == null || #result.isEmpty()")
    public List<String> getKmsForRodovia(String rodovia) {
        if (rodovia == null || rodovia.isBlank()) {
            return new ArrayList<>(); // Retorna lista vazia se nenhuma rodovia for fornecida
        }
        return radarsRepository.findDistinctKmsByRodovia(rodovia);
    }

    /**
     * Método auxiliar para ATUALIZAR o cache de uma rodovia específica via @CachePut.
     * Usado pelo Scheduler.
     */
    @CachePut(value = "kms-rodovia-cart", key = "#rodovia", unless = "#result == null || #result.isEmpty()")
    public List<String> atualizarCacheKms(String rodovia) {
        return radarsRepository.findDistinctKmsByRodovia(rodovia);
    }

    /**
     * TAREFA AGENDADA (Cache Warmer):
     * Roda a cada 10 minutos (600000ms) para atualizar o cache em SEGUNDO PLANO.
     * O usuário nunca sentirá a lentidão, pois o @CachePut atualiza o Redis silenciosamente.
     */
    @Scheduled(fixedRate = 600000) // 10 minutos
    @CachePut(value = "opcoes-filtro-cart-v2", unless = "#result == null || #result.rodovias.isEmpty()")
    public FilterOptionsDTO atualizarCacheFiltros() {
        log.info("🔄 [Background] Iniciando atualização completa de caches (Filtros + KMs)...");

        FilterOptionsDTO filtros = buscarDadosNoBanco(); // Busca filtros gerais

        if (filtros != null && filtros.getRodovias() != null) {
            // 🔥 A MÁGICA: Para cada rodovia encontrada, atualiza o cache de KMs em paralelo
            // Isso garante que quando o usuário selecionar a rodovia, os KMs JÁ ESTARÃO NO REDIS.
            List<CompletableFuture<Void>> futures = filtros.getRodovias().stream()
                    .map(rodovia -> CompletableFuture.runAsync(() -> {
                        try {
                            List<String> kms = atualizarCacheKms(rodovia);
                            log.debug("   -> Cache KMs atualizado para {}: {} registros", rodovia, kms.size());
                        } catch (Exception e) {
                            log.warn("Falha ao aquecer cache KMs para {}: {}", rodovia, e.getMessage());
                        }
                    }, executorService))
                    .toList();

            // Não precisamos esperar todos terminarem para retornar os filtros gerais,
            // mas é bom para garantir que o log de "concluído" seja real.
            // CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        return filtros;
    }

    /**
     * Inicializa o cache assim que o serviço sobe, para o primeiro usuário não esperar.
     */
    @PostConstruct
    public void initCache() {
        CompletableFuture.runAsync(() -> {
            log.info("🚀 [Startup] Iniciando aquecimento do cache de filtros...");
            atualizarCacheFiltros();
        });
    }

    // Método privado com a lógica pesada de banco
    private FilterOptionsDTO buscarDadosNoBanco() {
        long start = System.currentTimeMillis();
        try {
            var rodoviasFuture = CompletableFuture.supplyAsync(radarsRepository::findDistinctRodovias, executorService);
            var pracasFuture = CompletableFuture.supplyAsync(radarsRepository::findDistinctPracas, executorService);
            var kmsFuture = CompletableFuture.supplyAsync(radarsRepository::findDistinctKms, executorService);
            var sentidosFuture = CompletableFuture.supplyAsync(radarsRepository::findDistinctSentidos, executorService);

            CompletableFuture.allOf(rodoviasFuture, pracasFuture, kmsFuture, sentidosFuture)
                    .get(45, TimeUnit.SECONDS); // Timeout igual ao do BFF

            FilterOptionsDTO dto = new FilterOptionsDTO(
                    rodoviasFuture.get(),
                    pracasFuture.get(),
                    kmsFuture.get(),
                    sentidosFuture.get()
            );

            log.info("✅ [Banco de Dados] Filtros carregados e cacheados em {}ms", (System.currentTimeMillis() - start));
            return dto;
        } catch (Exception e) {
            log.error("❌ Erro ao buscar filtros no banco: {}", e.getMessage());
            // Retorna null para NÃO cachear o erro
            return null;
        }
    }

    private RadarsDTO converterParaDTO(Radars radars) {
        return RadarsDTO.builder()
                .id(radars.getId())
                .data(radars.getData())
                .hora(radars.getHora())
                .placa(radars.getPlaca())
                .praca(radars.getPraca())
                .rodovia(radars.getRodovia())
                .km(radars.getKm())
                .sentido(radars.getSentido())
                .build();
    }
}
