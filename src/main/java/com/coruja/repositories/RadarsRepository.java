package com.coruja.repositories;

import com.coruja.entities.Radars;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
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

    //**************************************************************************
    /**
     * ✅ BUSCA OTIMIZADA POR PLACA
     * Usa índice GIN (pg_trgm) para LIKE ultrarrápido
     * FETCH JOIN elimina N+1 problem
     */
    @Query(value = """
        SELECT r FROM Radars r 
        LEFT JOIN FETCH r.localizacao 
        WHERE UPPER(r.placa) LIKE UPPER(CONCAT('%', :placa, '%'))
        AND r.data >= :dataLimite
        ORDER BY r.data DESC, r.hora DESC
        """)
    @QueryHints(@QueryHint(name = "org.hibernate.readOnly", value = "true"))
    Page<Radars> findByPlacaOtimizado(
            @Param("placa") String placa,
            @Param("dataLimite") LocalDate dataLimite,
            Pageable pageable
    );

    /**
     * ✅ BUSCA COM FILTROS COMBINADOS
     * Query nativa para máxima performance
     */
    @Query(value = """
        SELECT DISTINCT ON (r.data, r.hora, r.placa) r.* 
        FROM radars_cart r
        WHERE 1=1
        AND (:placa IS NULL OR UPPER(r.placa) LIKE UPPER(CONCAT('%', CAST(:placa AS TEXT), '%')))
        AND (:praca IS NULL OR UPPER(r.praca) LIKE UPPER(CONCAT('%', CAST(:praca AS TEXT), '%')))
        AND (:rodovia IS NULL OR r.rodovia = :rodovia)
        AND (:km IS NULL OR r.km = :km)
        AND (:sentido IS NULL OR r.sentido = :sentido)
        AND (:data IS NULL OR r.data = :data)
        AND (:horaInicial IS NULL OR r.hora >= :horaInicial)
        AND (:horaFinal IS NULL OR r.hora <= :horaFinal)
        AND r.data >= CURRENT_DATE - INTERVAL '90 days'
        ORDER BY r.data DESC, r.hora DESC, r.placa
        LIMIT :limit OFFSET :offset
        """,
            countQuery = """
        SELECT COUNT(DISTINCT (r.data, r.hora, r.placa))
        FROM radars_cart r
        WHERE 1=1
        AND (:placa IS NULL OR UPPER(r.placa) LIKE UPPER(CONCAT('%', CAST(:placa AS TEXT), '%')))
        AND (:praca IS NULL OR UPPER(r.praca) LIKE UPPER(CONCAT('%', CAST(:praca AS TEXT), '%')))
        AND (:rodovia IS NULL OR r.rodovia = :rodovia)
        AND (:km IS NULL OR r.km = :km)
        AND (:sentido IS NULL OR r.sentido = :sentido)
        AND (:data IS NULL OR r.data = :data)
        AND (:horaInicial IS NULL OR r.hora >= :horaInicial)
        AND (:horaFinal IS NULL OR r.hora <= :horaFinal)
        AND r.data >= CURRENT_DATE - INTERVAL '90 days'
        """,
            nativeQuery = true
    )
    Page<Radars> findComFiltrosOtimizado(
            @Param("placa") String placa,
            @Param("praca") String praca,
            @Param("rodovia") String rodovia,
            @Param("km") String km,
            @Param("sentido") String sentido,
            @Param("data") LocalDate data,
            @Param("horaInicial") LocalTime horaInicial,
            @Param("horaFinal") LocalTime horaFinal,
            @Param("limit") int limit,
            @Param("offset") int offset,
            Pageable pageable
    );

    /**
     * ✅ METADATA DE FILTROS - Query otimizada com CTE
     */
    @Query(value = """
        WITH dados_recentes AS (
            SELECT rodovia, praca, km, sentido 
            FROM radars_cart 
            WHERE data >= CURRENT_DATE - INTERVAL '30 days'
        )
        SELECT DISTINCT rodovia FROM dados_recentes WHERE rodovia IS NOT NULL ORDER BY rodovia
        """, nativeQuery = true)
    List<String> findDistinctRodoviasOtimizado();

    @Query(value = """
        SELECT DISTINCT praca FROM radars_cart 
        WHERE data >= CURRENT_DATE - INTERVAL '30 days' 
        AND praca IS NOT NULL 
        ORDER BY praca
        """, nativeQuery = true)
    List<String> findDistinctPracasOtimizado();

    @Query(value = """
        SELECT DISTINCT km FROM radars_cart 
        WHERE rodovia = :rodovia 
        AND data >= CURRENT_DATE - INTERVAL '30 days'
        AND km IS NOT NULL 
        ORDER BY CAST(REGEXP_REPLACE(km, '[^0-9.]', '', 'g') AS NUMERIC)
        """, nativeQuery = true)
    List<String> findDistinctKmsByRodoviaOtimizado(@Param("rodovia") String rodovia);

    @Query(value = """
        SELECT DISTINCT sentido FROM radars_cart 
        WHERE data >= CURRENT_DATE - INTERVAL '30 days' 
        AND sentido IS NOT NULL 
        ORDER BY sentido
        """, nativeQuery = true)
    List<String> findDistinctSentidosOtimizado();

    /**
     * ✅ BUSCA GEOESPACIAL OTIMIZADA
     */
    @Query(value = """
        SELECT DISTINCT ON (r.data, r.hora, r.placa) r.* 
        FROM radars_cart r
        INNER JOIN localizacao_radar l ON r.localizacao_id = l.id
        WHERE r.data = :data
        AND r.hora BETWEEN :horaInicio AND :horaFim
        AND ST_DWithin(
            l.localizacao,
            ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
            :raio
        )
        ORDER BY r.data DESC, r.hora DESC, r.placa
        """,
            countQuery = """
        SELECT COUNT(DISTINCT (r.data, r.hora, r.placa))
        FROM radars_cart r
        INNER JOIN localizacao_radar l ON r.localizacao_id = l.id
        WHERE r.data = :data
        AND r.hora BETWEEN :horaInicio AND :horaFim
        AND ST_DWithin(
            l.localizacao,
            ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
            :raio
        )
        """,
            nativeQuery = true
    )
    Page<Radars> findByGeolocalizacaoOtimizada(
            @Param("latitude") Double latitude,
            @Param("longitude") Double longitude,
            @Param("raio") Double raio,
            @Param("data") LocalDate data,
            @Param("horaInicio") LocalTime horaInicio,
            @Param("horaFim") LocalTime horaFim,
            Pageable pageable
    );
    
}
