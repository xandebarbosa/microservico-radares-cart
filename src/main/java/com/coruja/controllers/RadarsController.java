package com.coruja.controllers;

import com.coruja.dto.FilterOptionsDTO;
import com.coruja.dto.RadarsDTO;
import com.coruja.repositories.RadarsRepository;
import com.coruja.services.RadarsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@CrossOrigin(origins = "${cors.origins}")
@RestController
@RequestMapping(value = "/radares")
public class RadarsController {

    @Autowired
    private RadarsRepository radarsRepository;

    private final RadarsService radarsService;

    // Injeção de dependência via construtor é a melhor prática
    public RadarsController(RadarsService radarsService) {
        this.radarsService = radarsService;
    }

    /**
     * Endpoint UNIFICADO para buscar radares com filtros opcionais.
     * O BFF usará este endpoint para todas as suas consultas.
     * Exemplo de uso pelo BFF: /radares/filtros?placa=ABC1234&page=0&size=20
     * Exemplo 2: /radares/filtros?rodovia=SP-300&data=2025-06-06&page=0&size=20
     * Se nenhum parâmetro for passado, ele retorna todos os radares paginados.
     */
    @GetMapping("/filtros")
    public ResponseEntity<Page<RadarsDTO>> buscarComFiltros(
            @RequestParam(required = false) String placa,
            @RequestParam(required = false) String praca,
            @RequestParam(required = false) String rodovia,
            @RequestParam(required = false) String km,
            @RequestParam(required = false) String sentido,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime horaInicial,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime horaFinal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageRequest pageRequest = PageRequest.of(page, size);
        Page<RadarsDTO> resultado = radarsService.buscarComFiltros(
                placa, praca,rodovia, km, sentido, data, horaInicial, horaFinal, pageRequest
        );
        return  ResponseEntity.ok(resultado);
    }

    // O endpoint GET / que retorna TODOS os dados pode ser removido, pois
    // chamar /filtros sem parâmetros tem o mesmo efeito. Vamos mantê-lo por compatibilidade.
    @GetMapping
    public ResponseEntity<Page<RadarsDTO>> getAllRadars(Pageable pageable) {
        // Reutilizamos a nova lógica para manter o código DRY (Don't Repeat Yourself)
        Page<RadarsDTO> result = radarsService.buscarComFiltros(null, null, null, null, null, null, null, null,pageable);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/opcoes-filtro")
    public ResponseEntity<FilterOptionsDTO> getFiltersOptions() {
        return ResponseEntity.ok(radarsService.getFilterOptions());
    }

    @GetMapping("/kms-por-rodovia")
    public ResponseEntity<List<String>> getKmsByRodovia(@RequestParam String rodovia) {
        return ResponseEntity.ok(radarsService.getKmsForRodovia(rodovia));
    }

}
