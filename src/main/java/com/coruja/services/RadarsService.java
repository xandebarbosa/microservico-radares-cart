package com.coruja.services;

import com.coruja.dto.FilterOptionsDTO;
import com.coruja.dto.LocalizacaoRadarProjection;
import com.coruja.dto.RadarsDTO;
import com.coruja.entities.Radars;
import com.coruja.repositories.LocalizacaoRadarRepository;
import com.coruja.repositories.RadarsRepository;
import com.coruja.specifications.RadarsSpecification;
import io.micrometer.core.annotation.Timed;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@Slf4j
public class RadarsService {

    @Value("${rabbitmq.exchange.name}")
    private String exchangeName;

    @Value("${rabbitmq.routing.key}")
    private String routingKey;

    private final RadarsRepository radarsRepository;
    private final RabbitTemplate rabbitTemplate;
    private final LocalizacaoRadarRepository localizacaoRadarRepository;
    // Thread Pool para tarefas assíncronas (RabbitMQ e Cache)
    private final ExecutorService executorService = Executors.newFixedThreadPool(10);

    // ✅ LIMITE DE DADOS HISTÓRICOS (últimos 90 dias)
    private static final int DIAS_HISTORICO = 90;

    public  RadarsService(RadarsRepository radarsRepository, RabbitTemplate rabbitTemplate, LocalizacaoRadarRepository localizacaoRadarRepository) {
        this.radarsRepository = radarsRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.localizacaoRadarRepository = localizacaoRadarRepository;
    }

    @PostConstruct
    public void init() {
        log.info("🚀 Inicializando cache de filtros...");
        CompletableFuture.runAsync(this::atualizarCacheFiltros, executorService);
    }

    /**
     * ✅ BUSCA OTIMIZADA COM CACHE INTELIGENTE
     * TTL: 5 minutos para buscas genéricas, 1 hora para específicas
     */
    @Transactional(readOnly = true)
    @Cacheable(
            value = "radars-search",
            key = "#placa + '_' + #rodovia + '_' + #data + '_' + #pageable.pageNumber",
            unless = "#result == null || #result.isEmpty()",
            condition = "#placa != null && #placa.length() >= 3"
    )
    @Timed(value = "radares.busca.filtros", histogram = true)
    public Page<RadarsDTO> buscarComFiltros(
            String placa, String praca, String rodovia, String km,
            String sentido, LocalDate data, LocalTime horaInicial,
            LocalTime horaFinal, Pageable pageable) {

        log.debug("🔍 Buscando com filtros - Placa: {}, Rodovia: {}", placa, rodovia);

        LocalDate dataLimite = LocalDate.now().minusDays(DIAS_HISTORICO);
        int limit = pageable.getPageSize();
        int offset = pageable.getPageNumber() * pageable.getPageSize();

        Page<Radars> resultado = radarsRepository.findComFiltrosOtimizado(
                normalize(placa),
                normalize(praca),
                normalize(rodovia),
                normalize(km),
                normalize(sentido),
                data,
                horaInicial,
                horaFinal,
                limit,
                offset,
                pageable
        );

        log.debug("✅ Encontrados {} registros", resultado.getTotalElements());
        return resultado.map(this::converterParaDTO);
    }

    /**
     * ✅ BUSCA ESPECÍFICA POR PLACA (MAIS RÁPIDA)
     */
    @Transactional(readOnly = true)
    @Cacheable(
            value = "radars-placa",
            key = "#placa + '_' + #pageable.pageNumber",
            unless = "#result == null || #result.isEmpty()",
            condition = "#placa != null && #placa.length() >= 3"
    )
    @Timed(value = "radares.busca.placa", histogram = true)
    public Page<RadarsDTO> buscarPorPlaca(String placa, Pageable pageable) {
        if (placa == null || placa.length() < 3) {
            throw new IllegalArgumentException("Placa deve ter no mínimo 3 caracteres");
        }

        LocalDate dataLimite = LocalDate.now().minusDays(DIAS_HISTORICO);
        Page<Radars> resultado = radarsRepository.findByPlacaOtimizado(
                placa.trim(),
                dataLimite,
                pageable
        );

        return resultado.map(this::converterParaDTO);
    }

    /**
     * ✅ BUSCA GEOESPACIAL OTIMIZADA
     */
    @Transactional(readOnly = true)
    @Timed(value = "radares.busca.geo", histogram = true)
    public Page<RadarsDTO> buscarPorGeolocalizacao(
            Double latitude, Double longitude, Double raio,
            LocalDate data, LocalTime horaInicio, LocalTime horaFim,
            Pageable pageable) {

        if (latitude == null || longitude == null || data == null) {
            throw new IllegalArgumentException("Latitude, Longitude e Data são obrigatórios");
        }

        double raioMetros = (raio != null) ? raio : 15000.0;

        Page<Radars> resultado = radarsRepository.findByGeolocalizacaoOtimizada(
                latitude, longitude, raioMetros, data, horaInicio, horaFim, pageable
        );

        return resultado.map(this::converterParaDTO);
    }

    /**
     * ✅ FILTROS METADATA - Cache de 2 horas
     */
    @Cacheable(
            value = "opcoes-filtro-cart",
            unless = "#result == null || #result.rodovias.isEmpty()"
    )
    @Transactional(readOnly = true)
    public FilterOptionsDTO getFilterOptions() {
        log.info("📋 Buscando opções de filtro (Cache Miss)");

        return FilterOptionsDTO.builder()
                .rodovias(orEmpty(radarsRepository.findDistinctRodoviasOtimizado()))
                .pracas(orEmpty(radarsRepository.findDistinctPracasOtimizado()))
                .sentidos(orEmpty(radarsRepository.findDistinctSentidosOtimizado()))
                .build();
    }

    /**
     * ✅ KMS POR RODOVIA - Cache de 30 minutos
     */
    @Cacheable(
            value = "kms-rodovia-cart",
            key = "#rodovia",
            unless = "#result == null || #result.isEmpty()"
    )
    @Transactional(readOnly = true)
    public List<String> getKmsForRodovia(String rodovia) {
        if (rodovia == null || rodovia.isBlank()) {
            return new ArrayList<>();
        }
        return orEmpty(radarsRepository.findDistinctKmsByRodoviaOtimizado(rodovia));
    }

    /**
     * ✅ LOCALIZAÇÕES PARA MAPA - Cache de 24 horas
     */
    @Cacheable(
            value = "mapa-radares-cart",
            unless = "#result == null || #result.isEmpty()"
    )
    @Transactional(readOnly = true)
    public List<LocalizacaoRadarProjection> listarTodasLocalizacoes() {
        return localizacaoRadarRepository.findAllLocations();
    }

    /**
     * ✅ SALVAR RADARES COM PUBLICAÇÃO ASYNC
     */
    @Transactional
    public void saveRadars(List<Radars> radarsList) {
        if (radarsList == null || radarsList.isEmpty()) return;

        // Salva em batch para performance
        List<Radars> saved = radarsRepository.saveAll(radarsList);
        log.info("💾 Salvos {} registros", saved.size());

        // Publica no RabbitMQ de forma assíncrona
        CompletableFuture.runAsync(() ->
                        saved.forEach(this::enviarMensagemParaRabbitMQ),
                executorService
        );

        // Limpa cache relevante
        limparCachesRelacionados();
    }

    /**
     * ✅ LIMPEZA DE CACHE PROGRAMADA
     * Roda às 3:00 AM todos os dias
     */
    @Scheduled(cron = "0 0 3 * * *")
    @CacheEvict(value = {"radars-search", "radars-placa", "opcoes-filtro-cart"}, allEntries = true)
    public void limparCacheDiario() {
        log.info("🧹 Limpeza diária de cache executada");
    }

    /**
     * ✅ ATUALIZAÇÃO DE CACHE DE FILTROS
     * Roda às 4:00 AM todos os dias
     */
    @Scheduled(cron = "0 0 4 * * *")
    public FilterOptionsDTO atualizarCacheFiltros() {
        log.info("🔄 Atualizando cache de filtros...");
        limparCachesRelacionados();
        return getFilterOptions();
    }

    // ==================== MÉTODOS AUXILIARES ====================

    private void enviarMensagemParaRabbitMQ(Radars radar) {
        if (!isValidRadar(radar)) return;

        LocalDateTime dataHoraRadar = LocalDateTime.of(radar.getData(), radar.getHora());
        LocalDateTime limite = LocalDateTime.now().minusHours(5);

        if (dataHoraRadar.isBefore(limite)) {
            return; // Ignora dados antigos
        }

        try {
            String msg = formatMessage(radar);
            rabbitTemplate.convertAndSend(exchangeName, routingKey, msg);
        } catch (AmqpException e) {
            log.warn("⚠️ Falha RabbitMQ - Placa {}: {}", radar.getPlaca(), e.getMessage());
        }
    }

    private void limparCachesRelacionados() {
        // Implementar lógica de limpeza seletiva se necessário
    }

    private boolean isValidRadar(Radars radar) {
        return radar != null && radar.getData() != null
                && radar.getHora() != null && radar.getPlaca() != null;
    }

    private String formatMessage(Radars radar) {
        String concessionaria = routingKey.split("\\.")[1].toUpperCase();
        return String.format("%s|%s|%s|%s|%s|%s|%s|%s",
                concessionaria, radar.getData(), radar.getHora(), radar.getPlaca(),
                radar.getPraca(), radar.getRodovia(), radar.getKm(), radar.getSentido());
    }

    private <T> List<T> orEmpty(List<T> list) {
        return list == null ? new ArrayList<>() : list;
    }

    private String normalize(String input) {
        return (input != null) ? input.trim().toUpperCase() : null;
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
