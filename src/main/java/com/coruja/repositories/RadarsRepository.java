package com.coruja.repositories;

import com.coruja.entities.Radars;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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

    @Query("SELECT DISTINCT r.rodovia FROM Radars r WHERE r.rodovia IS NOT NULL AND r.rodovia != '' ORDER BY r.rodovia")
    List<String> findDistinctHighways();

    @Query("SELECT DISTINCT r.praca FROM Radars r WHERE r.praca IS NOT NULL AND r.praca != '' ORDER BY r.praca")
    List<String> findDistinctPlaza();

    @Query("SELECT DISTINCT r.km FROM Radars r WHERE r.km IS NOT NULL AND r.km != '' ORDER BY r.km")
    List<String> findDistinctKms();

    @Query("SELECT DISTINCT r.sentido FROM Radars r WHERE r.sentido IS NOT NULL AND r.sentido != '' ORDER BY r.sentido")
    List<String> findDisntictSenses();

    @Query("SELECT DISTINCT r.km FROM Radars r WHERE r.rodovia = :rodovia AND r.km IS NOT NULL AND r.km <> '' ORDER BY r.km")
    List<String> findDistinctKmsByRodovia(@Param("rodovia") String rodovia);
}
