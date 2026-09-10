package com.coruja.services;

import com.coruja.entities.LocalizacaoRadar;
import com.coruja.entities.Radars;
import com.coruja.repositories.LocalizacaoRadarRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.commons.net.ftp.FTP.BINARY_FILE_TYPE;

@Service
@Slf4j
@RequiredArgsConstructor // Injeta automaticamente as variáveis 'final' (substitui o construtor manual)
public class FtpService {

    @Value("${ftp.host}")
    private String FTP_HOST;

    @Value("${ftp.port}")
    private int FTP_PORT;

    @Value("${ftp.user}")
    private String FTP_USER;

    @Value("${ftp.pass}")
    private String FTP_PASS;

    @Value("${ftp.directory}")
    private String FTP_DIRECTORY;

    @Value("${ftp.local.directory:/mnt/dados}")
    private String localDirectory;

    @Value("${ftp.schedule.rate.ms}")
    private long ftpScheduleRateMs;

    private LocalDateTime lastExecutionTime;

    private final RadarsService radarsService;
    private final LocalizacaoRadarRepository localizacaoRepository;
    private final GestaoRodoviaService gestaoRodoviaService;

    // Padrões estáticos pré-compilados para máxima performance
    private static final Pattern LINE_PATTERN = Pattern.compile(
            "^(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(.+?)\\s+(SP\\S+)\\s+(KM\\S+)$"
    );

    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{2}-\\d{2}-\\d{4}");

    private static final Pattern PLACA_MERCOSUL_PATTERN = Pattern.compile(
            "^(" +
                    "[A-Z]{3}[0-9]{4}|" +          // BR Antigo / UY Mercosul
                    "[A-Z]{3}[0-9][A-Z][0-9]{2}|" + // BR Carro Mercosul
                    "[A-Z]{3}[0-9]{2}[A-Z][0-9]|" + // BR Moto Mercosul
                    "[A-Z]{2}[0-9]{3}[A-Z]{2}|" +   // AR Carro Mercosul
                    "[A-Z][0-9]{3}[A-Z]{3}|" +      // AR Moto Mercosul
                    "[A-Z]{3}[0-9]{3}|" +           // AR Antigo (6 caracteres)
                    "[A-Z]{4}[0-9]{3}|" +           // PY Carro Mercosul (ex: AAGY936)
                    "[0-9]{3}[A-Z]{4}" +            // PY Moto Mercosul
                    ")$"
    );

    private static final DateTimeFormatter DATE_FILE_FORMAT = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter DATE_LINE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_LINE_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss[.SSS]");

    @Scheduled(fixedRateString = "${ftp.schedule.rate.ms}")
    public void processarFtp() {
        lastExecutionTime = LocalDateTime.now();
        log.debug("Iniciando verificação de arquivos no FTP às {}...", lastExecutionTime);

        FTPClient ftpClient = new FTPClient();

        try {
            conectarFTP(ftpClient);
            Path localPath = Path.of(localDirectory);

            // 1. Bloco específico para garantir a criação do diretório e tratar permissões do Docker/Linux
            try {
                if (!Files.exists(localPath)) {
                    Files.createDirectories(localPath);
                    log.info("Diretório local criado com sucesso: {}", localPath);
                }
            } catch (AccessDeniedException e) {
                log.error("ERRO GRAVE DE PERMISSÃO: O usuário do container Docker não tem permissão de escrita em '{}'. Ajuste as permissões do volume mapeado no host (Ubuntu).", localPath);
                return; // Fail-fast: Interrompe a execução, pois não será possível salvar os arquivos
            }

            Set<String> arquivosLocais = listarArquivosLocais(localPath);
            String[] arquivosRemotos = ftpClient.listNames();

            if (arquivosRemotos == null || arquivosRemotos.length == 0) {
                log.debug("Nenhum arquivo encontrado no diretório do FTP.");
                return;
            }

            LocalDate dataLimite = LocalDate.now().minusMonths(1);
            String dataHojeStr = LocalDate.now().format(DATE_FILE_FORMAT);

            // 2. FILTRAGEM INTELIGENTE DE ARQUIVOS
            List<String> arquivosParaProcessar = Arrays.stream(arquivosRemotos)
                    .filter(arquivo -> isDentroDoPeriodo(arquivo, dataLimite))
                    .filter(arquivo -> arquivo.contains(dataHojeStr) || !arquivosLocais.contains(arquivo))
                    .collect(Collectors.toList());

            // 3. Early return com log se não houver o que processar após o filtro
            if (arquivosParaProcessar.isEmpty()) {
                log.debug("Nenhum arquivo novo pendente de processamento neste ciclo.");
                return;
            }

            Map<String, LocalizacaoRadar> mapaLocalizacao = carregarMapaLocalizacao();
            Map<String, Set<String>> descobertasDoLote = new HashMap<>();
            List<Radars> todosOsRadares = new ArrayList<>();

            // Pré-carrega APENAS os dados de HOJE para evitar duplicatas rapidamente em memória
            Set<String> hashesSalvosHoje = radarsService.buscarHashesPorData(LocalDate.now());

            for (String nomeArquivo : arquivosParaProcessar) {
                baixarArquivo(ftpClient, nomeArquivo, localPath).ifPresent(arquivoLocal -> {
                    todosOsRadares.addAll(processarArquivo(arquivoLocal, mapaLocalizacao, descobertasDoLote, hashesSalvosHoje));

                    if (nomeArquivo.contains(dataHojeStr)) {
                        try {
                            Files.deleteIfExists(arquivoLocal);
                        } catch (IOException e) {
                            log.warn("Não foi possível apagar arquivo de hoje: {}", nomeArquivo);
                        }
                    }
                });
            }

            if (!todosOsRadares.isEmpty()) {
                log.info("Salvando {} novos registros de radares no banco de dados.", todosOsRadares.size());
                radarsService.saveRadars(todosOsRadares);
            }

            if (!descobertasDoLote.isEmpty()) {
                gestaoRodoviaService.registrarDescobertas(descobertasDoLote);
            }

        } catch (IOException e) {
            log.error("Erro de I/O durante o processamento do FTP.", e);
        } catch (Exception e) {
            // 4. Catch genérico para impedir que falhas imprevistas quebrem a thread do @Scheduled permanentemente
            log.error("Erro inesperado durante a execução do job FTP.", e);
        } finally {
            desconectarFtp(ftpClient);

            // Evita NullPointerException caso a variável não tenha sido injetada ou calculada
            if (lastExecutionTime != null) {
                LocalDateTime proximaExecucao = lastExecutionTime.plus(ftpScheduleRateMs, ChronoUnit.MILLIS);
                log.debug("Processo finalizado. Próxima execução agendada para: {}",
                        proximaExecucao.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")));
            }
        }
    }

    @Transactional
    public List<Radars> processarArquivo(Path arquivoLocal,
                                         Map<String, LocalizacaoRadar> mapaLocalizacao,
                                         Map<String, Set<String>> acumuladorDescobertas,
                                         Set<String> hashesSalvosHoje) {
        // try-with-resources garante que o arquivo não fique "preso" na memória/disco do Windows/Linux
        try (Stream<String> lines = Files.lines(arquivoLocal, StandardCharsets.ISO_8859_1)) {
            return lines
                    .map(linha -> parseLineWithRegex(linha, mapaLocalizacao, acumuladorDescobertas, hashesSalvosHoje))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("Falha ao ler o arquivo local: {}", arquivoLocal, e);
            return Collections.emptyList();
        }
    }

    private Radars parseLineWithRegex(String linha,
                                      Map<String, LocalizacaoRadar> mapaLocalizacao,
                                      Map<String, Set<String>> acumuladorDescobertas,
                                      Set<String> hashesSalvosHoje) {

        if (linha.trim().isEmpty() || linha.contains("Data_Transação") || linha.startsWith("Changed database") || linha.matches("[-\\s]+") || linha.matches("\\(\\d+ rows affected\\)")) {
            return null;
        }

        Matcher matcher = LINE_PATTERN.matcher(linha.trim());
        if (!matcher.matches()) {
            return null;
        }

        try {
            String dataStr = matcher.group(1);
            String horaStr = matcher.group(2);
            String placaBruta = matcher.group(3);
            String pracaESentido = matcher.group(4).trim();
            String rodovia = matcher.group(5);
            String km = matcher.group(6).replace("KM", "").trim();

            // Higienização e truncagem da placa
            String placa = sanitizarPlaca(placaBruta);

            // Validação de formato (opcional: descarte de registros com placas ilegíveis/inválidas)
            if (!isPlacaValida(placa)) {
                log.trace("Placa em formato desconhecido descartada: {}", placa);
                return null;
            }

            LocalDate data = LocalDate.parse(dataStr, DATE_LINE_FORMAT);

            // 1. PRIMEIRO: Lógica para separar praça e sentido
            String[] partesPraca = pracaESentido.split("\\s+");
            String sentido = "N/I";
            String praca = pracaESentido;

            if (partesPraca.length > 1) {
                sentido = partesPraca[partesPraca.length - 1];
                praca = String.join(" ", Arrays.copyOf(partesPraca, partesPraca.length - 1));
            }

            // 2. DEPOIS: Verificação de Duplicidade usando a 'praca' já limpa!
            if (data.isEqual(LocalDate.now())) {
                // Agora o hash fica perfeito (ex: CUM390812:57:51Ourinhos 03)
                String hashDaLinha = placa + horaStr + praca;

                if (hashesSalvosHoje.contains(hashDaLinha)) {
                    return null; // Linha repetida, pula silenciosamente!
                }

                hashesSalvosHoje.add(hashDaLinha);
            }

            LocalTime hora = LocalTime.parse(horaStr, TIME_LINE_FORMAT);
            LocalizacaoRadar localizacaoDoRadar = mapaLocalizacao.get(normalizeKey(praca));

            if (acumuladorDescobertas != null) {
                acumuladorDescobertas.computeIfAbsent(rodovia, k -> new HashSet<>()).add(km);
            }

            return new Radars(data, hora, placa, praca, rodovia, km, sentido, localizacaoDoRadar);

        } catch (Exception e) {
            log.error("Erro ao converter dados da linha: '{}'. Causa: {}", linha, e.getMessage());
            return null;
        }
    }

    private String sanitizarPlaca(String placaBruta) {
        if (placaBruta == null) return "";
        String limpa = placaBruta.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        return limpa.length() > 7 ? limpa.substring(0, 7) : limpa;
    }

    private boolean isPlacaValida(String placa) {
        return PLACA_MERCOSUL_PATTERN.matcher(placa).matches();
    }

    private Map<String, LocalizacaoRadar> carregarMapaLocalizacao() {
        try {
            log.debug("🗺️ Carregando cache de localizações do banco...");
            List<LocalizacaoRadar> todasLocs = localizacaoRepository.findAll();
            Map<String, LocalizacaoRadar> map = new HashMap<>();

            for (LocalizacaoRadar loc : todasLocs) {
                if (loc.getPraca() != null) {
                    map.put(normalizeKey(loc.getPraca()), loc);
                }
            }
            return map;
        } catch (Exception e) {
            log.error("❌ Erro ao carregar cache de localizações: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private String normalizeKey(String input) {
        return input == null ? "" : input.trim().toUpperCase();
    }

    private void conectarFTP(FTPClient ftpClient) throws IOException {
        log.debug("Tentando conectar ao FTP [{}:{}]", FTP_HOST, FTP_PORT);

        // 💡 CONFIGURAÇÕES DE TIMEOUT (Antes de conectar)
        ftpClient.setConnectTimeout(30000); // 30 segundos de limite para conseguir se conectar

        ftpClient.connect(FTP_HOST, FTP_PORT);

        if (!FTPReply.isPositiveCompletion(ftpClient.getReplyCode())) {
            throw new IOException("Falha ao conectar: " + ftpClient.getReplyString());
        }
        if (!ftpClient.login(FTP_USER, FTP_PASS)) {
            throw new IOException("Falha no login: " + ftpClient.getReplyString());
        }

        // 💡 CONFIGURAÇÕES DE PROTEÇÃO DE STREAM (Pós-login)
        ftpClient.enterLocalPassiveMode(); // Garante o modo passivo para furar o firewall do Docker

        // Mantém o canal de controle ativo enviando pacotes nulos a cada 30 segundos
        ftpClient.setControlKeepAliveTimeout(java.time.Duration.ofSeconds(30));

        // Timeout rígido de transferência: se o stream congelar por mais de 60s, o Java fecha o canal de forma limpa
        ftpClient.setDataTimeout(java.time.Duration.ofSeconds(60));
        ftpClient.setSoTimeout(60000);

        // Garante transferência binária (Indispensável para arquivos pesados não corromperem)
        ftpClient.setFileType(BINARY_FILE_TYPE);

        ftpClient.changeWorkingDirectory(FTP_DIRECTORY);
    }

    private void desconectarFtp(FTPClient ftpClient) {
        if (ftpClient != null && ftpClient.isConnected()) {
            try {
                ftpClient.logout();
                ftpClient.disconnect();
            } catch (IOException e) {
                log.error("Erro ao desconectar do FTP.", e);
            }
        }
    }

    private Optional<Path> baixarArquivo(FTPClient ftpClient, String nomeArquivo, Path diretorioLocal) {
        Path arquivoLocal = diretorioLocal.resolve(nomeArquivo);
        try (OutputStream outputStream = Files.newOutputStream(arquivoLocal)) {
            if (ftpClient.retrieveFile(nomeArquivo, outputStream)) {
                log.info("Download concluído: {}", nomeArquivo);
                return Optional.of(arquivoLocal);
            } else {
                log.warn("Falha no download do arquivo: {}", nomeArquivo);
                Files.deleteIfExists(arquivoLocal);
                return Optional.empty();
            }
        } catch (IOException e) {
            log.error("Erro de I/O ao baixar {}", nomeArquivo, e);
            return Optional.empty();
        }
    }

    private Set<String> listarArquivosLocais(Path diretorioLocal) {
        try (Stream<Path> stream = Files.list(diretorioLocal)) {
            return stream.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            return Collections.emptySet();
        }
    }

    private boolean isDentroDoPeriodo(String nomeArquivo, LocalDate dataLimite) {
        Optional<LocalDate> dataOpt = extrairDataDoNome(nomeArquivo);
        if (dataOpt.isEmpty()) return false;

        boolean dentroDoPeriodo = !dataOpt.get().isBefore(dataLimite);
        if (!dentroDoPeriodo) {
            log.debug("Arquivo '{}' ignorado (fora do período).", nomeArquivo);
        }
        return dentroDoPeriodo;
    }

    private Optional<LocalDate> extrairDataDoNome(String nomeArquivo) {
        try {
            Matcher matcher = DATE_PATTERN.matcher(nomeArquivo);
            if (matcher.find()) {
                return Optional.of(LocalDate.parse(matcher.group(), DATE_FILE_FORMAT));
            }
        } catch (DateTimeParseException e) {
            log.warn("Erro extraindo data do arquivo {}", nomeArquivo);
        }
        return Optional.empty();
    }
}
