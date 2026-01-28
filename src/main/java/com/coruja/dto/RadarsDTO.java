package com.coruja.dto;

import com.coruja.entities.Radars;
import com.coruja.enums.Sentido;
import lombok.*;
import org.springframework.beans.BeanUtils;

import java.time.LocalDate;
import java.time.LocalTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RadarsDTO {

    private Long id;
    private LocalDate data;
    private LocalTime hora;
    private String placa;
    private String praca;
    private String rodovia;
    private String km;
    private Sentido sentido;
}
