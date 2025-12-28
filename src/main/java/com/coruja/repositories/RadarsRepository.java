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

    // A mágica acontece aqui! Ao estender JpaSpecificationExecutor, ganhamos o método:
    // Page<Radars> findAll(Specification<Radars> spec, Pageable pageable);
    // Não precisamos mais de métodos customizados como findByFiltersLocal.

    // Spring Data JPA entende esse nome de método e cria a query:
    // SELECT * FROM radars WHERE placa = ?
    Page<Radars> findByPlaca(String placa, Pageable pageable);

    // E aqui também, ele cria a query com AND para todos os campos:
    // SELECT * FROM radars WHERE rodovia = ? AND km = ? AND sentido = ?
    Page<Radars> findByRodoviaAndKmAndSentido(String rodovia, String km, String sentido, Pageable pageable);

    @Query("SELECT DISTINCT r.rodovia FROM Radars r WHERE r.rodovia IS NOT NULL ORDER BY r.rodovia")
    List<String> findDistinctRodovias();

    @Query("SELECT DISTINCT r.praca FROM Radars r WHERE r.praca IS NOT NULL ORDER BY r.praca")
    List<String> findDistinctPracas();

    @Query("SELECT DISTINCT r.km FROM Radars r WHERE r.km IS NOT NULL ORDER BY r.km")
    List<String> findDistinctKms();

    @Query("SELECT DISTINCT r.sentido FROM Radars r WHERE r.sentido IS NOT NULL ORDER BY r.sentido")
    List<String> findDistinctSentidos();

    @Query("SELECT DISTINCT r.km FROM Radars r WHERE r.rodovia = :rodovia ORDER BY r.km")
    List<String> findDistinctKmsByRodovia(@Param("rodovia") String rodovia);

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
