package com.coruja.repositories;

import com.coruja.entities.Radars;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Repository
public interface RadarsRepository extends JpaRepository<Radars, Long>, JpaSpecificationExecutor<Radars> {

    // O Spring cria automaticamente: SELECT * FROM radars WHERE placa LIKE %?%
    Page<Radars> findByPlacaContaining(String placa, Pageable pageable);

    // E aqui também, ele cria a query com AND para todos os campos:
    // SELECT * FROM radars WHERE rodovia = ? AND km = ? AND sentido = ?
    Page<Radars> findByRodoviaAndKmAndSentido(String rodovia, String km, String sentido, Pageable pageable);

    @Query(value = "SELECT DISTINCT rodovia FROM radars_cart WHERE rodovia IS NOT NULL AND rodovia <> '' ORDER BY rodovia", nativeQuery = true)
    List<String> findDistinctRodoviasNative();

    @Query(value = "SELECT DISTINCT praca FROM radars_cart WHERE praca IS NOT NULL AND praca <> '' ORDER BY praca", nativeQuery = true)
    List<String> findDistinctPracasNative();

    @Query(value = "SELECT DISTINCT km FROM radars_cart WHERE km IS NOT NULL AND km <> '' ORDER BY km", nativeQuery = true)
    List<String> findDistinctKmsNative();

    @Query(value = "SELECT DISTINCT sentido FROM radars_cart WHERE sentido IS NOT NULL AND sentido <> '' ORDER BY sentido", nativeQuery = true)
    List<String> findDistinctSentidosNative();

    @Query(value = "SELECT DISTINCT km FROM radars_cart WHERE rodovia = ?1 AND km IS NOT NULL AND km <> '' ORDER BY km", nativeQuery = true)
    List<String> findDistinctKmsByRodoviaNative(String rodovia);

    /**
     * Busca radares dentro de um raio (em metros) de uma coordenada, filtrando por data e hora.
     * Usa PostGIS para alta performance.
     * * Usa DISTINCT ON para remover duplicatas de Placa/Data/Hora.
     */
    @Query(value = """
        SELECT DISTINCT ON (r.data, r.hora, r.placa) r.* FROM radars_cart r
        JOIN localizacao_radar l ON r.localizacao_id = l.id
        WHERE r.data = :data
        AND r.hora BETWEEN :horaInicio AND :horaFim
        AND ST_DWithin(
            l.localizacao::geography,
            ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
            :raio
        )
        ORDER BY r.data, r.hora, r.placa
        """,

            countQuery = """
        SELECT count(DISTINCT (r.data, r.hora, r.placa))
        FROM radars_cart r
        JOIN localizacao_radar l ON r.localizacao_id = l.id
        WHERE r.data = :data
        AND r.hora BETWEEN :horaInicio AND :horaFim
        AND ST_DWithin(
            l.localizacao::geography,
            ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
            :raio
        )
        """,
            nativeQuery = true)
    Page<Radars> findByGeolocalizacao(
            @Param("latitude") Double latitude,
            @Param("longitude") Double longitude,
            @Param("raio") Double raio,
            @Param("data") LocalDate data,
            @Param("horaInicio") LocalTime horaInicio,
            @Param("horaFim") LocalTime horafim,
            Pageable pageable
    );
    
}
