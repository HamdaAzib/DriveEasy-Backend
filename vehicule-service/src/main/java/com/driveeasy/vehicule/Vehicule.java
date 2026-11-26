package com.driveeasy.vehicule;

import java.math.BigDecimal;

public record Vehicule(
        Long id,
        String immatriculation,
        String marque,
        String modele,
        int annee,
        String couleur,
        String carburant,
        String boite,
        int nbPlaces,
        int kilometrage,
        BigDecimal prixJournalier,
        String categorie,
        String statut,
        String imageUrl) {
}
