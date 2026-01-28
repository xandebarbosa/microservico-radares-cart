package com.coruja.services;

import com.coruja.entities.KmRodovia;
import com.coruja.entities.Rodovia;
import com.coruja.repositories.KmRodoviaRepository;
import com.coruja.repositories.RodoviaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class GestaoRodoviaService {
    private final RodoviaRepository rodoviaRepository;
    private final KmRodoviaRepository kmRepository;

    @Cacheable(value = "lista-rodovias")
    public List<Rodovia> listarRodovias() {
        return rodoviaRepository.findAll();
    }

    @Transactional
    @CacheEvict(value = "lista-rodovias", allEntries = true)
    public Rodovia salvarRodovia(Rodovia rodovia) {
        if (rodoviaRepository.existsByNome(rodovia.getNome())) {
            throw new IllegalArgumentException("Rodovia já existe.");
        }
        return rodoviaRepository.save(rodovia);
    }

    @Transactional
    @CacheEvict(value = "lista-rodovias", allEntries = true)
    public void deletarRodovia(Long id) {
        rodoviaRepository.deleteById(id);
    }

    // --- KMs ---
    @Cacheable(value = "lista-kms", key = "#rodoviaId")
    public List<KmRodovia> listarKmsPorRodovia(Long rodoviaId) {
        return kmRepository.findByRodoviaId(rodoviaId);
    }

    @Transactional
    @CacheEvict(value = "lista-kms", key = "#km.rodovia.id")
    public KmRodovia salvarKm(KmRodovia km) {
        return kmRepository.save(km);
    }

    @Transactional
    @CacheEvict(value = "lista-kms", allEntries = true)
    public void deletarKm(Long id){
        kmRepository.deleteById(id);
    }

    /**
     * ✅ MÉTODO NOVO: APRENDIZADO EM LOTE
     * Recebe um Mapa: Chave = Nome da Rodovia, Valor = Lista de KMs encontrados
     */
    @Transactional
    @CacheEvict(value = {"lista-rodovias", "lista-kms"}, allEntries = true)
    public void registrarDescobertas(Map<String, Set<String>> descobertas) {
        if (descobertas.isEmpty()) return;

        log.info("🧠 Processando aprendizado de domínio: {} rodovias encontradas...", descobertas.size());

        // 1. Carrega tudo que já existe no banco para memória (evita N+1 selects)
        // Mapeia Nome -> Entidade Rodovia
        Map<String, Rodovia> rodoviasExistentes = rodoviaRepository.findAll().stream()
                .collect(Collectors.toMap(Rodovia::getNome, r -> r));

        // Mapeia ID_Rodovia -> Lista de Kms (String)
        Map<Long, Set<String>> kmsExistentes = kmRepository.findAll().stream()
                .collect(Collectors.groupingBy(
                        k -> k.getRodovia().getId(),
                        Collectors.mapping(KmRodovia::getValor, Collectors.toSet())
                ));

        List<Rodovia> novasRodovias = new ArrayList<>();
        List<KmRodovia> novosKms = new ArrayList<>();

        // 2. Itera sobre o que veio do FTP
        descobertas.forEach((nomeRodovia, listaKms) -> {
            // A. Trata Rodovia
            Rodovia rodovia = rodoviasExistentes.get(nomeRodovia);

            if (rodovia == null) {
                // Se não existe, cria e já salva para ter o ID
                rodovia = Rodovia.builder().nome(nomeRodovia).build();
                rodovia = rodoviaRepository.save(rodovia); // Save imediato para gerar ID
                rodoviasExistentes.put(nomeRodovia, rodovia);
                log.info("🆕 Nova Rodovia descoberta: {}", nomeRodovia);
            }

            // B. Trata KMs dessa Rodovia
            Set<String> kmsNoBanco = kmsExistentes.getOrDefault(rodovia.getId(), new HashSet<>());

            for (String valorKm : listaKms) {
                if (!kmsNoBanco.contains(valorKm)) {
                    novosKms.add(KmRodovia.builder()
                            .valor(valorKm)
                            .rodovia(rodovia)
                            .build());
                    // Adiciona ao set local para evitar duplicata dentro do mesmo loop
                    kmsNoBanco.add(valorKm);
                }
            }
        });

        // 3. Salva todos os KMs novos de uma vez (Batch Insert)
        if (!novosKms.isEmpty()) {
            kmRepository.saveAll(novosKms);
            log.info("💾 Salvos {} novos KMs no banco de dados de domínio.", novosKms.size());
        }
    }
}
