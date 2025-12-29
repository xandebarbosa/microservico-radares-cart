package com.coruja.services;

import com.coruja.entities.LocalizacaoRadar;
import com.coruja.entities.Radars;
import com.coruja.repositories.LocalizacaoRadarRepository;
import com.coruja.repositories.RadarsRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
@Slf4j
public class FtpService {
    private static final Logger logger = LoggerFactory.getLogger(FtpService.class);
    //private static final Logger LOGGER = Logger.getLogger(FtpService.class.getName());

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

    @Value("${ftp.local.directory}")
    private String LOCAL_DIRECTORY;

    // NOVO: Injeta o valor do intervalo do application.properties
    @Value("${ftp.schedule.rate.ms}")
    private long ftpScheduleRateMs;

    private LocalDateTime lastExecutionTime;

    private final RadarsRepository radarsRepository;
    private final RadarsService radarsService;
    private final LocalizacaoRadarRepository localizacaoRepository;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    // Regex para capturar os dados da linha. MUITO MAIS ROBUSTO!
    // Captura 6 grupos: 1=Data, 2=Hora, 3=Placa, 4=Praça/Sentido, 5=Rodovia, 6=KM
    private static final Pattern LINE_PATTERN = Pattern.compile(
            "^(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(.+?)\\s+(SP\\S+)\\s+(KM\\S+)$"
    );

    public FtpService(RadarsService radarsService, LocalizacaoRadarRepository localizacaoRepository, RadarsRepository radarsRepository) {
        this.radarsService = radarsService;
        this.localizacaoRepository = localizacaoRepository;
        this.radarsRepository = radarsRepository;
    }

    // AJUSTE: A anotação @Scheduled agora lê o valor do application.properties
    @Scheduled(fixedRateString = "${ftp.schedule.rate.ms}")
    public void processarFtp() {

        // Armazenamos a hora de início para um cálculo mais preciso
        // LocalDateTime horaInicio = LocalDateTime.now();

        lastExecutionTime = LocalDateTime.now();
        logger.info("Iniciando verificação de arquivos no FTP às {}...",  lastExecutionTime);
        //horaInicio.format(DateTimeFormatter.ofPattern("HH:mm:ss"))

        FTPClient ftpClient = new FTPClient();

        try {
            conectarFTP(ftpClient);
            Path localPath = Path.of(LOCAL_DIRECTORY);
            Files.createDirectories(localPath);

            Set<String> arquivosLocais = listarArquivosLocais(localPath);
            String[] arquivosRemotos = ftpClient.listNames();

            if (arquivosRemotos == null || arquivosRemotos.length == 0) {
                logger.info("Nenhum arquivo encontrado no diretório do FTP.");
                return;
            }

            // AJUSTE: Definir a data limite para os últimos 2 meses.
            LocalDate dataLimite = LocalDate.now().minusDays(1);
            logger.info("Definida data limite para processamento: {}", dataLimite.format(DateTimeFormatter.ISO_LOCAL_DATE));

            List<String> novosArquivos = Arrays.stream(arquivosRemotos)
                    .filter(arquivo -> !arquivosLocais.contains(arquivo)) // Filtra arquivos que já foram baixados
                    .filter(arquivo -> isDentroDoPeriodo(arquivo, dataLimite)) // NOVO: Filtra arquivos pela data
                    .collect(Collectors.toList());

            if (novosArquivos.isEmpty()) {
                logger.info("Nenhum arquivo novo e dentro do período de 2 meses para processar.");
                return;
            }

            logger.info("Encontrados {} novos arquivos para processar.", novosArquivos.size());
            List<Radars> todosOsRadares = new ArrayList<>();

            for (String nomeArquivo : novosArquivos) {
                baixarArquivo(ftpClient, nomeArquivo, localPath).ifPresent(arquivoLocal -> {
                    logger.info("Processando arquivo: {}", nomeArquivo);
                    todosOsRadares.addAll(processarArquivo(arquivoLocal));
                });
            }

            if (!todosOsRadares.isEmpty()) {
                logger.info("Salvando {} novos registros de radares no banco de dados.", todosOsRadares.size());
                radarsService.saveRadars(todosOsRadares);
                logger.info("Banco de dados atualizado com sucesso.");
            } else {
                logger.info("Nenhum registro válido encontrado nos novos arquivos.");
            }

        } catch (IOException e) {
            logger.error("Erro de I/O durante o processamento do FTP.", e);
        } finally {
            desconectarFtp(ftpClient);

            // NOVO: Adiciona o log com a hora da próxima execução
            // O fixedRate começa a contar a partir do INÍCIO da execução anterior.
            LocalDateTime proximaExecucao = lastExecutionTime.plus(ftpScheduleRateMs, ChronoUnit.MILLIS);
            logger.info("Processo finalizado. Próxima execução agendada para: {}",
                    proximaExecucao.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")));
            logger.info("*******************************************\n");
        }
    }

    @Scheduled(fixedDelay = 1000) // Atualiza a cada segundo
    public void updateCountdown() {
        if (lastExecutionTime != null) {
            long secondsRemaining = 300 - ChronoUnit.SECONDS.between(lastExecutionTime, LocalDateTime.now());
            if (secondsRemaining > 0) {
                System.out.printf("\rPróxima execução em: %02d:%02d",
                        secondsRemaining / 60,
                        secondsRemaining % 60);
            } else {
                System.out.print("\rPróxima execução em: 00:00 - Iniciando...");
            }
        }
    }

    // NOVO: Método auxiliar para verificar se a data do arquivo está no período desejado.
    private boolean isDentroDoPeriodo(String nomeArquivo, LocalDate dataLimite) {
        Optional<LocalDate> dataArquivoOpt = extrairDataDoNome(nomeArquivo);
        if (dataArquivoOpt.isEmpty()) {
            logger.warn("Não foi possível extrair a data do arquivo '{}'. Ele será ignorado.", nomeArquivo);
            return false; // Ignora arquivos sem data no nome
        }
        // Retorna true se a data do arquivo NÃO for anterior à data limite.
        boolean dentroDoPeriodo = !dataArquivoOpt.get().isBefore(dataLimite);
        if (!dentroDoPeriodo) {
            logger.info("Arquivo '{}' está fora do período de 2 meses. Ignorando.", nomeArquivo);
        }
        return dentroDoPeriodo;
    }

    // AJUSTE: Método agora retorna um Optional para tratar melhor os casos de erro.
    private Optional<LocalDate> extrairDataDoNome(String nomeArquivo) {
        try {
            Pattern pattern = Pattern.compile("\\d{2}-\\d{2}-\\d{4}");
            Matcher matcher = pattern.matcher(nomeArquivo);
            if (matcher.find()) {
                String dataStr = matcher.group();
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy");
                return Optional.of(LocalDate.parse(dataStr, formatter));
            }
        } catch (DateTimeParseException e) {
            logger.warn("Erro ao extrair data do arquivo {}: {}", nomeArquivo, e.getMessage());
        }
        return Optional.empty();
    }

    private List<Radars> processarArquivo(Path arquivoLocal) {
        try (Stream<String> lines = Files.lines(arquivoLocal, StandardCharsets.ISO_8859_1)) {
            return lines
                    .map(this::parseLineWithRegex) // Usa o novo método com Regex
                    .filter(Objects::nonNull)      // Filtra linhas que falharam no parse
                    .collect(Collectors.toList());
        } catch (IOException e) {
            logger.error("Falha ao ler o arquivo local: {}", arquivoLocal, e);
            return Collections.emptyList();
        }
    }

    /**
     * NOVO MÉTODO DE PARSING - A Correção do Bug
     * Usa uma Expressão Regular para extrair os dados de forma segura.
     */
    private Radars parseLineWithRegex(String linha) {
        // Ignora linhas de cabeçalho, rodapé ou vazias
        if (linha.trim().isEmpty() || linha.contains("Data_Transação") || linha.startsWith("Changed database") || linha.matches("[-\\s]+") || linha.matches("\\(\\d+ rows affected\\)")) {
            return null;
        }

        Matcher matcher = LINE_PATTERN.matcher(linha.trim());
        if (!matcher.matches()) {
            logger.warn("Linha não corresponde ao padrão esperado, ignorando: '{}'", linha);
            return null;
        }

        try {
            String dataStr = matcher.group(1);
            String horaStr = matcher.group(2);
            String placaBruta = matcher.group(3);

            // CORREÇÃO DA LÓGICA DA PLACA: Agora é segura para qualquer tamanho.
            String placa = placaBruta.replaceAll("[^A-Za-z0-9]", "");
            if (placa.length() > 7) {
                placa = placa.substring(0, 7);
            }

            // CORREÇÃO PARA CAMPOS OPCIONAIS: Verificamos se os grupos foram capturados.
            String pracaESentido = matcher.group(4).trim();
            String rodovia = matcher.group(5); // Não é mais opcional na regex
            String km = matcher.group(6).replace("KM", "").trim();

            String[] partesPraca = pracaESentido.split("\\s+");
            String sentido = "N/I"; // Valor padrão
            String praca = pracaESentido; // Por padrão, tudo é a praça

            if (partesPraca.length > 1) {
                sentido = partesPraca[partesPraca.length - 1];
                praca = String.join(" ", Arrays.copyOf(partesPraca, partesPraca.length - 1));
            }

            LocalDate data = LocalDate.parse(dataStr, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            LocalTime hora = LocalTime.parse(horaStr, DateTimeFormatter.ofPattern("HH:mm:ss[.SSS]"));

            // 1. Busca na tabela "De-Para" pelo objeto de localização completo.
            LocalizacaoRadar localizacaoDoRadar = null; // 1. Começa como nulo
            try {
                // 2. Tenta buscar na tabela "De-Para"
                localizacaoDoRadar = localizacaoRepository.findByPraca(praca)
                        .orElse(null); // Se encontrar, atribui

            } catch (Exception e) {
                // 3. Se falhar (ex: Tabela não existe), avisa e continua
                logger.warn("Não foi possível consultar a tabela 'localizacao_radar' (ela pode não existir). O campo de localização ficará nulo. Causa: {}", e.getMessage());
            }
            // ***************************************************************

            // 4. Cria o objeto Radars com o que foi possível obter
            return new Radars(data, hora, placa, praca, rodovia, km, sentido, localizacaoDoRadar);

        } catch (Exception e) {
            logger.error("Erro ao converter dados da linha: '{}'. Causa: {}", linha, e.getMessage());
            return null;
        }
    }

    // Métodos de conexão e download foram levemente ajustados para clareza
    private void conectarFTP(FTPClient ftpClient) throws IOException {
        //logger.info("Conectando ao FTP: {}", FTP_HOST);
        logger.info("Tentando conectar ao FTP com HOST: [{}] e PORTA: [{}]", FTP_HOST, FTP_PORT);
        ftpClient.connect(FTP_HOST, FTP_PORT);
        if (!FTPReply.isPositiveCompletion(ftpClient.getReplyCode())) {
            throw new IOException("Falha ao conectar ao FTP: " + ftpClient.getReplyString());
        }
        if (!ftpClient.login(FTP_USER, FTP_PASS)) {
            throw new IOException("Falha ao logar no FTP: " + ftpClient.getReplyString());
        }
        ftpClient.enterLocalPassiveMode();
        ftpClient.changeWorkingDirectory(FTP_DIRECTORY);
        logger.info("Conectado com sucesso.");
    }

    private void desconectarFtp(FTPClient ftpClient) {
        if (ftpClient != null && ftpClient.isConnected()) {
            try {
                ftpClient.logout();
                ftpClient.disconnect();
                logger.info("Conexão FTP encerrada.");
            } catch (IOException e) {
                logger.error("Erro ao desconectar do FTP.", e);
            }
        }
    }

    private Optional<Path> baixarArquivo(FTPClient ftpClient, String nomeArquivo, Path diretorioLocal) {
        Path arquivoLocal = diretorioLocal.resolve(nomeArquivo);
        try (OutputStream outputStream = Files.newOutputStream(arquivoLocal)) {
            if (ftpClient.retrieveFile(nomeArquivo, outputStream)) {
                logger.info("Download concluído: {}", nomeArquivo);
                return Optional.of(arquivoLocal);
            } else {
                logger.warn("Falha no download do arquivo: {}", nomeArquivo);
                Files.deleteIfExists(arquivoLocal); // Deleta arquivo parcial se o download falhar
                return Optional.empty();
            }
        } catch (IOException e) {
            logger.error("Erro de I/O ao baixar o arquivo {}", nomeArquivo, e);
            return Optional.empty();
        }
    }

    private Set<String> listarArquivosLocais(Path diretorioLocal) {
        try (Stream<Path> stream = Files.list(diretorioLocal)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            logger.warn("Não foi possível listar arquivos locais. Um novo download pode ser tentado.", e);
            return Collections.emptySet();
        }
    }
}
