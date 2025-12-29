package com.coruja.services;

import com.coruja.dto.FilterOptionsDTO;
import com.coruja.dto.RadarsDTO;
import com.coruja.entities.Radars;
import com.coruja.repositories.RadarsRepository;
import com.coruja.specifications.RadarsSpecification;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.modelmapper.ModelMapper;
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

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
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

    @PostConstruct
    public void checkDatabase() {
        long count = radarsRepository.count();
        log.info("=================================================");
        log.info("📊 VERIFICAÇÃO DE BANCO DE DADOS (CART)");
        log.info("📊 [INIT] Total de Radares encontrados: {}", count);
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
     * * A anotação @Transactional(readOnly = true) aumenta a performance no Postgres.
     */
    @Transactional(readOnly = true)
    public Page<RadarsDTO> buscarComFiltros(
            String placa, String praca, String rodovia, String km, String sentido,
            LocalDate data, LocalTime horaInicial, LocalTime horaFinal,
            Pageable pageable
    ) {
        // 1. Limpeza de Strings (Trim) para evitar falhas por espaços em branco
        // Ex: "SP270 " vira "SP270"
        String placaLimpa = normalize(placa);
        String pracaLimpa = normalize(praca);
        String rodoviaLimpa = normalize(rodovia);
        String kmLimpo = normalize(km);
        String sentidoLimpo = normalize(sentido);

        // 2. Montagem da Query Dinâmica (Specification)
        // O banco usará os índices criados na migração V3
        Specification<Radars> spec = Specification.where(RadarsSpecification.comPlaca(placaLimpa))
                .and(RadarsSpecification.comPraca(pracaLimpa))
                .and(RadarsSpecification.comRodovia(rodoviaLimpa))
                .and(RadarsSpecification.comKm(kmLimpo))
                .and(RadarsSpecification.comSentido(sentidoLimpo))
                .and(RadarsSpecification.comData(data))
                .and(RadarsSpecification.comHoraEntre(horaInicial, horaFinal));

        // 3. Execução e Conversão
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
     * Busca veículos que passaram próximos a uma coordenada geográfica.
     * @param latitude Latitude (ex: -22.1234)
     * @param longitude Longitude (ex: -49.5678)
     * @param raio Raio em metros (opcional, default = 1000m)
     * @param data Data da passagem (Obrigatório)
     * @param horaInicio Hora inicial (Obrigatório)
     * @param horaFim Hora final (Obrigatório)
     */
    @Transactional(readOnly = true)
    public Page<RadarsDTO> buscarPorGeolocalizacao(
            Double latitude, Double longitude, Double raio,
            LocalDate data, LocalTime horaInicio, LocalTime horaFim,
            Pageable pageable
    ) {
        //Validação básica
        if (latitude == null || longitude == null || data == null || horaInicio == null || horaFim == null) {
            throw new IllegalArgumentException("Latitude, Longitude, Data, Hora Inicial e Hora Final são obrigatórios para a busca geoespacial.");
        }

        // Se o raio não for informado, assume 10km (10000 metros)
        double raioMetros = (raio != null) ? raio : 15000.0;

        // Chama o repositório com a nova query nativa
        Page<Radars> radarsPage = radarsRepository.findByGeolocalizacao(
                latitude, longitude, raioMetros, data, horaInicio, horaFim, pageable
        );
        // Converte a Entidade para DTO
        return radarsPage.map(this::converterParaDTO);
    }

    /**
     * Salva as leituras dos radares e publica as placas detectadas no RabbitMQ
     * de forma resiliente.
     */
    @Transactional
    public void saveRadars(List<Radars> radarsList) {
        if (radarsList == null || radarsList.isEmpty()) return;

        // 1. Salva todas as entidades e captura a lista de entidades salvas.
        //    A lista 'savedRadars' agora contém as entidades com os IDs preenchidos.
        List<Radars> savedRadars = radarsRepository.saveAll(radarsList);

        log.info("💾 Salvos {} registros no banco de dados com sucesso.", savedRadars.size());

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
        return radarsRepository.findDistinctKmsByRodovia(normalize(rodovia));
    }

    /**
     * Método auxiliar para ATUALIZAR o cache de uma rodovia específica via @CachePut.
     * Usado pelo Scheduler.
     */
    @CachePut(value = "kms-rodovia-cart-v2", key = "#rodovia", unless = "#result == null || #result.isEmpty()")
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

        if (filtros.getRodovias() != null && !filtros.getRodovias().isEmpty()) {
            // Atualiza KMs em paralelo sem bloquear a thread principal do Scheduler por muito tempo
            CompletableFuture.runAsync(() -> {
                filtros.getRodovias().forEach(rodovia -> {
                    try {
                        atualizarCacheKms(rodovia);
                    } catch (Exception e) {
                        log.warn("Erro ao atualizar cache KM para {}: {}", rodovia, e.getMessage());
                    }
                });
            }, executorService);
        }
        return filtros;
    }

    /**
     * Inicializa o cache assim que o serviço sobe, para o primeiro usuário não esperar.
     */
    @PostConstruct
    public void initCache() {
        CompletableFuture.runAsync(() -> {
            try {
                // Pequeno delay para garantir que o banco já subiu totalmente
                log.info("🚀 [Startup] Iniciando aquecimento do cache de filtros...");
                Thread.sleep(5000);
                atualizarCacheFiltros();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    // Método privado com a lógica pesada de banco
    private FilterOptionsDTO buscarDadosNoBanco() {
        long start = System.currentTimeMillis();
        try {
            log.info("🔍 [Banco] Executando queries de distinção...");
            // Executa queries em paralelo
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
            // CORREÇÃO CRUCIAL: Loga o erro mas retorna objeto VAZIO em vez de NULL
            log.error("❌ ERRO FATAL buscando filtros: {}", e.toString());
            return new FilterOptionsDTO(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
    }

    private String normalize(String input) {
        return (input != null) ? input.trim() : null;
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
