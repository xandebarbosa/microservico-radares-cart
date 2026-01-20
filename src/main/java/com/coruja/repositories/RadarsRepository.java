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

    /**
     * ✅ BUSCA OTIMIZADA POR PLACA
     * Usa índice GIN (pg_trgm) para LIKE ultrarrápido
     * FETCH JOIN elimina N+1 problem
     */
    @Query(value = """
        SELECT r FROM Radars r 
        LEFT JOIN FETCH r.localizacao 
        WHERE r.placa ILIKE CONCAT('%', :placa, '%')
        ORDER BY r.data DESC, r.hora DESC
        """,
            countQuery = """
        SELECT COUNT(r) FROM Radars r 
        WHERE r.placa ILIKE CONCAT('%', :placa, '%')
        """)
    @QueryHints(@QueryHint(name = "org.hibernate.readOnly", value = "true"))
    Page<Radars> findByPlacaOtimizado(
            @Param("placa") String placa,
            Pageable pageable
    );

    /**
     * ✅ BUSCA COM FILTROS COMBINADOS
     * Query nativa para máxima performance.
     * REMOVIDO: LIMIT e OFFSET manuais (o Pageable gerencia isso automaticamente).
     */
    @Query(value = """
        SELECT DISTINCT ON (r.data, r.hora, r.placa) r.* FROM radars_cart r
        WHERE 1=1
        AND (CAST(:placa AS TEXT) IS NULL OR UPPER(r.placa) LIKE UPPER(CONCAT('%', CAST(:placa AS TEXT), '%')))
        AND (CAST(:praca AS TEXT) IS NULL OR UPPER(r.praca) LIKE UPPER(CONCAT('%', CAST(:praca AS TEXT), '%')))
        AND (CAST(:rodovia AS TEXT) IS NULL OR r.rodovia = CAST(:rodovia AS TEXT))
        AND (CAST(:km AS TEXT) IS NULL OR r.km = CAST(:km AS TEXT))
        AND (CAST(:sentido AS TEXT) IS NULL OR r.sentido = CAST(:sentido AS TEXT))
        AND (CAST(:data AS DATE) IS NULL OR r.data = CAST(:data AS DATE))
        AND (CAST(:horaInicial AS TIME) IS NULL OR r.hora >= CAST(:horaInicial AS TIME))
        AND (CAST(:horaFinal AS TIME) IS NULL OR r.hora <= CAST(:horaFinal AS TIME))
        AND r.data >= CURRENT_DATE - INTERVAL '90 days'
        ORDER BY r.data DESC, r.hora DESC, r.placa
        """,
            countQuery = """
        SELECT COUNT(DISTINCT (r.data, r.hora, r.placa))
        FROM radars_cart r
        WHERE 1=1
        AND (CAST(:placa AS TEXT) IS NULL OR UPPER(r.placa) LIKE UPPER(CONCAT('%', CAST(:placa AS TEXT), '%')))
        AND (CAST(:praca AS TEXT) IS NULL OR UPPER(r.praca) LIKE UPPER(CONCAT('%', CAST(:praca AS TEXT), '%')))
        AND (CAST(:rodovia AS TEXT) IS NULL OR r.rodovia = CAST(:rodovia AS TEXT))
        AND (CAST(:km AS TEXT) IS NULL OR r.km = CAST(:km AS TEXT))
        AND (CAST(:sentido AS TEXT) IS NULL OR r.sentido = CAST(:sentido AS TEXT))
        AND (CAST(:data AS DATE) IS NULL OR r.data = CAST(:data AS DATE))
        AND (CAST(:horaInicial AS TIME) IS NULL OR r.hora >= CAST(:horaInicial AS TIME))
        AND (CAST(:horaFinal AS TIME) IS NULL OR r.hora <= CAST(:horaFinal AS TIME))
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
        SELECT DISTINCT ON (r.data, r.hora, r.placa) r.* FROM radars_cart r
        INNER JOIN localizacao_radar l ON r.localizacao_id = l.id
        WHERE r.data = CAST(:data AS DATE)
        AND r.hora BETWEEN CAST(:horaInicio AS TIME) AND CAST(:horaFim AS TIME)
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
        WHERE r.data = CAST(:data AS DATE)
        AND r.hora BETWEEN CAST(:horaInicio AS TIME) AND CAST(:horaFim AS TIME)
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
