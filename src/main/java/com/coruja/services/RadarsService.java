package com.coruja.services;

import com.coruja.dto.FilterOptionsDTO;
import com.coruja.dto.LocalizacaoRadarProjection;
import com.coruja.dto.RadarsDTO;
import com.coruja.entities.Radars;
import com.coruja.repositories.LocalizacaoRadarRepository;
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
import java.time.LocalDateTime;
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
    private final LocalizacaoRadarRepository localizacaoRadarRepository;

    // Thread Pool para tarefas assíncronas (RabbitMQ e Cache)
    private final ExecutorService executorService = Executors.newFixedThreadPool(10);

    public  RadarsService(RadarsRepository radarsRepository, RabbitTemplate rabbitTemplate, LocalizacaoRadarRepository localizacaoRadarRepository) {
        this.radarsRepository = radarsRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.localizacaoRadarRepository = localizacaoRadarRepository;
    }

    @PostConstruct
    public void checkDatabase() {
        // Log leve para não travar inicialização
        CompletableFuture.runAsync(() -> {
            long count = radarsRepository.count();
            log.info("📊 [INIT] Total de Registros no banco (CART): {}", count);
        });
        initCache();
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
    public Page<RadarsDTO> buscarComFiltros(String placa, String praca, String rodovia, String km, String sentido, LocalDate data, LocalTime horaInicial, LocalTime horaFinal, Pageable pageable) {
        Specification<Radars> spec = Specification.where(RadarsSpecification.comPlaca(normalize(placa)))
                .and(RadarsSpecification.comPraca(normalize(praca)))
                .and(RadarsSpecification.comRodovia(normalize(rodovia)))
                .and(RadarsSpecification.comKm(normalize(km)))
                .and(RadarsSpecification.comSentido(normalize(sentido)))
                .and(RadarsSpecification.comData(data))
                .and(RadarsSpecification.comHoraEntre(horaInicial, horaFinal));
        return radarsRepository.findAll(spec, pageable).map(this::converterParaDTO);
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
        List<Radars> savedRadars = radarsRepository.saveAll(radarsList);
        log.info("💾 Salvos {} registros.", savedRadars.size());

        // Envia para o RabbitMQ (assincronamente para não travar o banco)
        CompletableFuture.runAsync(() ->
                savedRadars.forEach(this::enviarMensagemParaRabbitMQ), executorService
        );
    }

    /**
     * NOVO: Método auxiliar para encapsular a lógica de envio e o tratamento de erro.
     * @param radar O objeto radar para o qual a mensagem será enviada.
     */
    //DEPOIS VOLTAR ESSE CODIGO DE ENVIO PELO RABBITMQ
//    private void enviarMensagemParaRabbitMQ(Radars radar) {
//        if (!isValidRadar(radar)) { // Lógica de validação em um método auxiliar
//            log.warn("Dados incompletos para a placa: {}. Mensagem não será enviada.", radar.getPlaca());
//            return;
//        }
//
//        String mensagem = formatMessage(radar);
//
//        try {
//            rabbitTemplate.convertAndSend(exchangeName, routingKey, mensagem);
//            log.info("Mensagem enviada para RabbitMQ com routingKey [{}]: {}", routingKey, mensagem);
//        } catch (AmqpException e) {
//            // Tratamento de erro resiliente
//            log.warn("Falha ao enviar mensagem para RabbitMQ - Placa: {}. Causa: {}", radar.getPlaca(), e.getMessage());
//        }
//    }

    private void enviarMensagemParaRabbitMQ(Radars radar) {
        if (!isValidRadar(radar)) return;

        // 1. REGRA DAS 5 HORAS
        // Verifica se a passagem do radar ocorreu nas últimas 5 horas
        LocalDateTime dataHoraRadar = LocalDateTime.of(radar.getData(), radar.getHora());
        LocalDateTime limite = LocalDateTime.now().minusHours(5);

        if (dataHoraRadar.isBefore(limite)) {
            // Se for antigo, apenas ignora o envio (mas já foi salvo no banco acima)
            return;
        }

        try {
            String msg = formatMessage(radar);
            rabbitTemplate.convertAndSend(exchangeName, routingKey, msg);
        } catch (AmqpException e) {
            log.warn("Falha RabbitMQ Placa {}: {}", radar.getPlaca(), e.getMessage());
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
    @Transactional(readOnly = true)
    public List<String> getKmsForRodovia(String rodovia) {
        if (rodovia == null || rodovia.isBlank()) return new ArrayList<>();
        try {
            // Usa query nativa que aproveita o índice idx_radars_rodovia_km
            List<String> kms = radarsRepository.findDistinctKmsByRodoviaNative(normalize(rodovia));
            return orEmpty(kms);
        } catch (Exception e) {
            log.error("❌ Erro ao buscar KMs para rodovia '{}': {}", rodovia, e.toString());
            // Retorna vazio para não causar erro 500 no front
            return new ArrayList<>();
        }
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
     * O usuário nunca sentirá a lentidão, pois o @CachePut atualiza o Redis silenciosamente.
     * CONFIGURAÇÃO: Roda todos os dias às 04:00 da manhã
     * Cron: Seg(0) Min(0) Hora(4) Dia(*) Mês(*) DiaSemana(*)
     */
    @Scheduled(cron = "0 0 4 * * *")
    @CachePut(value = "opcoes-filtro-cart-v2", unless = "#result == null || #result.rodovias.isEmpty()")
    public FilterOptionsDTO atualizarCacheFiltros() {
        log.info("🌙 [Cache Diário] Iniciando atualização de KMs e Filtros (Execução Programada)...");

        // 1. Busca os filtros gerais (Rodovias, Praças, Sentidos)
        FilterOptionsDTO filtros = buscarDadosNoBanco();

        if (filtros != null && filtros.getRodovias() != null) {
            log.info("🛣️ Encontradas {} rodovias. Atualizando KMs para cada uma...", filtros.getRodovias().size());

            // 2. Dispara a atualização dos KMs de cada rodovia em paralelo
            // Isso garante que quando o usuário entrar de manhã, o Redis já tenha os dados.
            List<CompletableFuture<Void>> futures = filtros.getRodovias().stream()
                    .map(rodovia -> CompletableFuture.runAsync(() -> {
                        try {
                            // Chama o método anotado com @CachePut para forçar a ida ao banco e atualização do Redis
                            atualizarCacheKms(rodovia);
                        } catch (Exception e) {
                            log.warn("Falha ao atualizar cache KMs da rodovia {}: {}", rodovia, e.getMessage());
                        }
                    }, executorService))
                    .toList();

            // Opcional: Aguarda todos terminarem para logar o fim
            // CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        log.info("✅ [Cache Diário] Processo de atualização finalizado.");
        return filtros;
    }

    /**
     * Inicializa o cache assim que o serviço sobe, para o primeiro usuário não esperar.
     */
    // Método auxiliar público para ser chamado pelo @PostConstruct
    public void initCache() {
        CompletableFuture.runAsync(this::atualizarCacheFiltros, executorService);
    }

    // Método privado com a lógica pesada de banco
    private FilterOptionsDTO buscarDadosNoBanco() {
        try {
            // Executa de forma sequencial mas segura para evitar sobrecarga simultânea
            List<String> rodovias = orEmpty(radarsRepository.findDistinctRodovias());
            List<String> pracas = orEmpty(radarsRepository.findDistinctPracas());
            List<String> kms = orEmpty(radarsRepository.findDistinctKms());
            List<String> sentidos = orEmpty(radarsRepository.findDistinctSentidos());

            return new FilterOptionsDTO(rodovias, pracas, kms, sentidos);
        } catch (Exception e) {
            log.error("❌ Erro ao buscar filtros no banco: {}", e.toString());
            return new FilterOptionsDTO(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
    }

    /**
     * NOVO MÉTODO: Retorna todas as localizações de radar para plotar no mapa.
     * Cache opcional: Como são dados estáticos, podemos cachear.
     */
    @Cacheable(value = "mapa-radares-cart", unless = "#result == null || #result.isEmpty()")
    public List<LocalizacaoRadarProjection> listarTodasLocalizacoes() {
        return localizacaoRadarRepository.findAllLocations();
    }

    private <T> List<T> orEmpty(List<T> list) { return list == null ? new ArrayList<>() : list; }
    private String normalize(String input) { return (input != null) ? input.trim() : null; }

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
