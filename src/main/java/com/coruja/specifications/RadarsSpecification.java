package com.coruja.specifications;

import com.coruja.entities.Radars;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.time.LocalTime;

public class RadarsSpecification {

    /**
     * Filtra pela placa exata.
     * PERFORMANCE: O uso de 'equal' permite que o Postgres use o índice 'idx_radares_placa'.
     * Funções como 'like' ou 'lower' aqui tornariam a busca muito mais lenta em grandes volumes.
     */
    public static Specification<Radars> comPlaca(String placa) {
        return (root, query, cb) -> {
            if (placa == null || placa.isBlank()) return null;
            return cb.like(cb.upper(root.get("placa")), "%" + placa.toUpperCase().trim() + "%");
        };
    }

    public static Specification<Radars> comPraca(String praca) {
        return (root, query, cb) -> {
            if (praca == null || praca.isBlank()) return null;
            return cb.like(cb.upper(root.get("praca")), "%" + praca.toUpperCase().trim() + "%");
        };
    }

    /**
     * Filtra pela Rodovia.
     * PERFORMANCE: Fundamental para ativar o índice composto (rodovia, km, sentido, data).
     */
    public static Specification<Radars> comRodovia(String rodovia) {
        return (root, query, cb) -> {
            if (rodovia == null || rodovia.isBlank()) return null;
            return cb.like(cb.upper(root.get("rodovia")), "%" + rodovia.toUpperCase().trim() + "%");
        };
    }

    public static Specification<Radars> comKm(String km) {
        return (root, query, cb) -> {
            if (km == null || km.isBlank()) return null;
            // ou usamos LIKE simples. Aqui optamos pelo LIKE simples para maior compatibilidade.
            return cb.like(root.get("km"), "%" + km.trim() + "%");
        };
    }

    public static Specification<Radars> comSentido(String sentido) {
        return (root, query, cb) -> {
            if (sentido == null || sentido.isBlank()) return null;
            // Usa Equal com Upper para ignorar case, pois sentido geralmente é uma palavra exata
            return cb.equal(cb.upper(root.get("sentido")), sentido.toUpperCase().trim());
        };
    }

    public static Specification<Radars> comData(LocalDate data) {
        return (root, query, cb) -> {
            if (data == null) return null;
            return cb.equal(root.get("data"), data);
        };
    }

    public static Specification<Radars> comHoraEntre(LocalTime inicio, LocalTime fim) {
        return (root, query, cb) -> {
            if (inicio == null || fim == null) return null;
            // O índice também ajuda no range scan (between) se a data já tiver filtrado a maior parte
            return cb.between(root.get("hora"), inicio, fim);
        };
    }
}