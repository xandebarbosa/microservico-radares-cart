package com.coruja.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class KmRodoviaDTO implements Serializable {
    private Long id;
    private String valor;
    private Long rodoviaId; // Trazemos apenas o ID, evitando o loop/erro do Hibernate
}
