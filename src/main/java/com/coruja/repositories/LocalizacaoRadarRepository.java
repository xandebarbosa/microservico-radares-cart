package com.coruja.repositories;

import com.coruja.entities.LocalizacaoRadar;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LocalizacaoRadarRepository extends JpaRepository<LocalizacaoRadar, Long> {
    // Busca uma localização pela combinação de rodovia e km
    Optional<LocalizacaoRadar> findByRodoviaAndKm(String rodovia, String km);

    // Busca uma localização pela praça de pedágio
    Optional<LocalizacaoRadar> findByPraca(String praca);
}
