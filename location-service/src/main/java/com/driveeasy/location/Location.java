package com.driveeasy.location;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record Location(
        Long id,
        Long clientId,
        Long vehiculeId,
        LocalDate dateDebut,
        LocalDate dateFinPrevue,
        LocalDate dateRetourEffective,
        Integer kmDepart,
        Integer kmRetour,
        BigDecimal prixJournalierApplique,
        BigDecimal montantTotal,
        BigDecimal penalite,
        String statut,
        LocalDateTime dateCreation) {
}
