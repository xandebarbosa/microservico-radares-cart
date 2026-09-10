package com.coruja.services;

import com.coruja.dto.*;
import com.coruja.entities.Radars;
import com.coruja.enums.Sentido;
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
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

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

    // ✅ Injetamos o JdbcTemplate para Bulk Inserts de ultra-performance
    private final JdbcTemplate jdbcTemplate;

    // Thread Pool para tarefas assíncronas (RabbitMQ e Cache)
    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
    // Cache thread-safe para metadados frequentes (ex: nomes de praças)
    private final ConcurrentHashMap<String, String> normalizeCache = new ConcurrentHashMap<>();

    // LIMITE DE DADOS HISTÓRICOS (últimos 90 dias)
    private static final int DIAS_HISTORICO = 90;

    public RadarsService(RadarsRepository radarsRepository,
                         RabbitTemplate rabbitTemplate,
                         LocalizacaoRadarRepository localizacaoRadarRepository,
                         JdbcTemplate jdbcTemplate) {
        this.radarsRepository = radarsRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.localizacaoRadarRepository = localizacaoRadarRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Retorna os hashes (Placa + Hora + Praça) dos radares salvos em uma data específica.
     * Usado para evitar duplicatas no FtpService de forma rápida (em memória).
     */
    @Transactional(readOnly = true)
    public Set<String> buscarHashesPorData(LocalDate data) {
        return radarsRepository.findHashesByData(data);
    }

    /**
     * Busca por PLACA: Retorna histórico completo
     */
    @Transactional(readOnly = true)
    public Page<RadarsDTO> buscarPorPlaca(String placa, Pageable pageable) {
        return radarsRepository.findAllByPlaca(normalize(placa), pageable)
                .map(this::converterParaDTO);
    }

    /**
     * Busca por LOCAL: Filtros pré-definidos
     */
    @Transactional(readOnly = true)
    public RadarPageDTO buscarPorLocal(
            LocalDate data,
            LocalTime horaInicial,
            LocalTime horaFinal,
            String rodovia,
            String km,
            String sentido,
            Pageable pageable) {

        log.debug("🔎 Executando query no Banco: Data={}, Rodovia={}, Sentido={}", data, rodovia, sentido);

        Page<Radars> page = radarsRepository.findByLocalFilter(
                data,
                horaInicial,
                horaFinal,
                null,
                normalize(rodovia),
                normalize(km),
                sentido,
                pageable
        );

        return convertToPageDTO(page);
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
     * ✅ Lista as ultimas passagens registradas
     */
    public List<RadarsDTO> buscarUltimos(int limite) {
        //Ordena para pegar as passagens mais recentes
        Pageable pageable = PageRequest.of(0, limite,
                Sort.by(Sort.Direction.DESC, "data", "hora"));

        Page<Radars> pagina = radarsRepository.findAll(pageable);

        return pagina.getContent().stream()
                .map(this::converterParaDTO)
                .collect(Collectors.toList());
    }

    /**
     * ✅ INSERÇÃO EM LOTE NATIVA (BULK INSERT) DE ALTA PERFORMANCE
     * Ignora o gargalo do strategy=IDENTITY do Hibernate
     */
    @Transactional
    public void saveRadars(List<Radars> radarsList) {
        if (radarsList == null || radarsList.isEmpty()) return;

        // O comando ON CONFLICT protege o banco caso o arquivo FTP venha com linhas duplicadas (evita Crash)
        String sql = """
            INSERT INTO radars_cart (data, hora, placa, praca, rodovia, km, sentido, localizacao_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (data, hora, placa, praca) DO NOTHING
        """;

        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                Radars radar = radarsList.get(i);
                ps.setDate(1, java.sql.Date.valueOf(radar.getData()));
                ps.setTime(2, java.sql.Time.valueOf(radar.getHora()));
                ps.setString(3, radar.getPlaca());
                ps.setString(4, radar.getPraca());
                ps.setString(5, radar.getRodovia());
                ps.setString(6, radar.getKm());
                ps.setString(7, radar.getSentido());

                if (radar.getLocalizacao() != null && radar.getLocalizacao().getId() != null) {
                    ps.setLong(8, radar.getLocalizacao().getId());
                } else {
                    ps.setNull(8, java.sql.Types.BIGINT);
                }
            }

            @Override
            public int getBatchSize() {
                return radarsList.size();
            }
        });

        log.info("💾 Salvos {} registros em Lote de Alta Performance", radarsList.size());

        // Publica no RabbitMQ de forma assíncrona
        CompletableFuture.runAsync(() ->
                        radarsList.forEach(this::enviarMensagemParaRabbitMQ),
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
    @CacheEvict(value = {"radars-search", "radars-placa", "opcoes-filtro-cart", "lista-rodovias", "lista-kms"}, allEntries = true)
    public void limparCacheDiario() {
        log.info("🧹 Limpeza diária de cache executada");
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
        if (input == null) return null;
        return normalizeCache.computeIfAbsent(input, i -> i.trim().toUpperCase());
    }

    /**
     * Converte Page<Entity> para RadarPageDTO (Estrutura paginada para JSON)
     */
    private RadarPageDTO convertToPageDTO(Page<Radars> page) {
        List<RadarsDTO> content = page.getContent().stream()
                .map(this::converterParaDTOBuscaLocal) // ✅ Reutiliza o conversor centralizado
                .collect(Collectors.toList());

        PageMetadata metadata = new PageMetadata(
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );

        return new RadarPageDTO(content, metadata);
    }

    private RadarsDTO converterParaDTOBuscaLocal(Radars radars) {
        RadarsDTO dto = new RadarsDTO();
        dto.setId(radars.getId());
        dto.setData(radars.getData());
        dto.setHora(radars.getHora());
        dto.setPlaca(radars.getPlaca());
        dto.setPraca(radars.getPraca());
        dto.setRodovia(radars.getRodovia());
        dto.setKm(radars.getKm());

        // Conversão Segura de String -> Enum
        try {
            dto.setSentido(Sentido.fromString(radars.getSentido()));
        } catch (Exception e) {
            dto.setSentido(Sentido.NAO_IDENTIFICADO);
        }

        return dto;
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
                .sentido(Sentido.fromString(radars.getSentido()))
                .concessionaria("Cart")
                .build();
    }
}