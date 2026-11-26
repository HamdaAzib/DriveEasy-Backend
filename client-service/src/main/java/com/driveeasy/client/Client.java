package com.driveeasy.client;

import java.time.LocalDate;

public record Client(
        Long id,
        String nom,
        String prenom,
        String cin,
        LocalDate dateNaissance,
        String email,
        String telephone,
        String adresse,
        String numeroPermis,
        LocalDate dateExpirationPermis,
        String categorieClient,
        LocalDate dateInscription,
        int locationsTerminees) {
}
