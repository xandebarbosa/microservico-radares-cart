package com.coruja.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class FilterOptionsDTO {
    private List<String> rodovias;
    private List<String> praca;
    private List<String> kms;
    private List<String> sentidos;
}
