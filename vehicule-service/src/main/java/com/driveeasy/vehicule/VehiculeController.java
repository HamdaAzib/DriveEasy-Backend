package com.driveeasy.vehicule;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Year;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/vehicules")
public class VehiculeController {
    private final VehiculeRepository repository;
    private final RestClient rest;

    public VehiculeController(VehiculeRepository repository,
                              @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder builder) {
        this.repository = repository;
        this.rest = builder.build();
    }

    @GetMapping
    public List<Vehicule> list(@RequestParam(required = false) String q,
                               @RequestParam(required = false) String categorie,
                               @RequestParam(required = false) String carburant,
                               @RequestParam(required = false) String statut,
                               @RequestParam(required = false) BigDecimal prixMax) {
        String query = q == null ? "" : q.toLowerCase(Locale.ROOT).trim();
        return repository.findAll().stream()
                .filter(v -> query.isBlank() || (v.marque() + " " + v.modele() + " " + v.immatriculation()).toLowerCase(Locale.ROOT).contains(query))
                .filter(v -> matches(v.categorie(), categorie))
                .filter(v -> matches(v.carburant(), carburant))
                .filter(v -> matches(v.statut(), statut))
                .filter(v -> prixMax == null || v.prixJournalier().compareTo(prixMax) <= 0)
                .sorted(Comparator.comparing(Vehicule::id)).toList();
    }

    @GetMapping("/disponibles")
    public List<Vehicule> available(@RequestParam(required = false) LocalDate dateDebut,
                                    @RequestParam(required = false) LocalDate dateFin) {
        if ((dateDebut == null) != (dateFin == null) || (dateDebut != null && !dateFin.isAfter(dateDebut))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La période de disponibilité est invalide");
        }
        Set<Long> reserved = dateDebut == null ? Set.of() : reservationsDuring(dateDebut, dateFin);
        return repository.findAll().stream()
                .filter(v -> "DISPONIBLE".equals(v.statut()))
                .filter(v -> !reserved.contains(v.id()))
                .sorted(Comparator.comparing(Vehicule::id)).toList();
    }

    @GetMapping("/{id}")
    public Vehicule get(@PathVariable long id) { return require(id); }

    @PostMapping
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public Vehicule create(@RequestBody VehiculeRequest request) {
        validate(request);
        ensureUniquePlate(request.immatriculation(), null);
        Vehicule vehicle = toVehicle(repository.nextId(), request, "DISPONIBLE");
        return repository.save(vehicle);
    }

    @PutMapping("/{id}")
    public Vehicule update(@PathVariable long id, @RequestBody VehiculeRequest request) {
        Vehicule old = require(id);
        validate(request);
        ensureUniquePlate(request.immatriculation(), id);
        return repository.save(toVehicle(id, request, old.statut()));
    }

    @PatchMapping("/{id}/statut")
    public Vehicule changeStatus(@PathVariable long id, @RequestBody StatusRequest request) {
        Vehicule old = require(id);
        String status = normalizeStatus(request.statut());
        return repository.save(new Vehicule(old.id(), old.immatriculation(), old.marque(), old.modele(), old.annee(),
                old.couleur(), old.carburant(), old.boite(), old.nbPlaces(), old.kilometrage(), old.prixJournalier(),
                old.categorie(), status, old.imageUrl()));
    }

    @DeleteMapping("/{id}")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        Vehicule vehicle = require(id);
        if ("LOUE".equals(vehicle.statut())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Un véhicule loué ne peut pas être supprimé");
        }
        repository.deleteById(id);
    }

    private Set<Long> reservationsDuring(LocalDate start, LocalDate end) {
        Map<String, Object> page = rest.get()
                .uri(uri -> uri.scheme("http").host("location-service").path("/locations")
                        .queryParam("dateDebut", start).queryParam("dateFin", end).queryParam("size", 1000).build())
                .retrieve().body(new ParameterizedTypeReference<>() {});
        Object content = page == null ? null : page.get("content");
        if (!(content instanceof Collection<?> rows)) return Set.of();
        return rows.stream().filter(Map.class::isInstance).map(Map.class::cast)
                .filter(row -> List.of("RESERVEE", "EN_COURS").contains(String.valueOf(row.get("statut"))))
                .map(row -> row.get("vehiculeId")).filter(Number.class::isInstance).map(Number.class::cast)
                .map(Number::longValue).collect(Collectors.toSet());
    }

    private Vehicule require(long id) {
        return repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Véhicule introuvable"));
    }

    private void ensureUniquePlate(String plate, Long exceptId) {
        boolean duplicate = repository.findAll().stream()
                .anyMatch(v -> !v.id().equals(exceptId) && v.immatriculation().equalsIgnoreCase(plate.trim()));
        if (duplicate) throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette immatriculation existe déjà");
    }

    private static boolean matches(String value, String filter) {
        return filter == null || filter.isBlank() || value.equalsIgnoreCase(filter.trim());
    }

    private static void validate(VehiculeRequest r) {
        if (r == null || blank(r.immatriculation()) || blank(r.marque()) || blank(r.modele()) || blank(r.couleur())
                || blank(r.carburant()) || blank(r.boite()) || blank(r.categorie())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Les caractéristiques du véhicule sont obligatoires");
        }
        if (r.prixJournalier() == null || r.prixJournalier().signum() <= 0 || r.kilometrage() < 0
                || r.nbPlaces() < 2 || r.nbPlaces() > 9 || r.annee() < 1900 || r.annee() > Year.now().getValue()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Prix, année, nombre de places ou kilométrage invalide");
        }
        if (!List.of("ESSENCE", "DIESEL", "HYBRIDE", "ELECTRIQUE").contains(r.carburant().toUpperCase(Locale.ROOT))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Type de carburant invalide");
        }
        if (!List.of("MANUELLE", "AUTOMATIQUE").contains(r.boite().toUpperCase(Locale.ROOT))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Type de boîte invalide");
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static String normalizeStatus(String status) {
        if (status == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Statut obligatoire");
        String value = status.trim().toUpperCase(Locale.ROOT);
        if (!List.of("DISPONIBLE", "LOUE", "MAINTENANCE").contains(value)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Statut invalide");
        return value;
    }

    private static Vehicule toVehicle(long id, VehiculeRequest r, String status) {
        return new Vehicule(id, r.immatriculation().trim().toUpperCase(Locale.ROOT), r.marque().trim(), r.modele().trim(),
                r.annee(), r.couleur().trim(), r.carburant().toUpperCase(Locale.ROOT), r.boite().toUpperCase(Locale.ROOT),
                r.nbPlaces(), r.kilometrage(), r.prixJournalier(), r.categorie().toUpperCase(Locale.ROOT), status, r.imageUrl());
    }

    public record VehiculeRequest(String immatriculation, String marque, String modele, int annee, String couleur,
                                  String carburant, String boite, int nbPlaces, int kilometrage,
                                  BigDecimal prixJournalier, String categorie, String imageUrl) {}
    public record StatusRequest(String statut) {}
}
