package com.driveeasy.client;

import java.time.LocalDate;
import java.time.Period;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/clients")
public class ClientController {
    private final ClientRepository repository;
    private final RestClient rest;

    @Value("${driveeasy.client.age-minimum:21}")
    private int ageMinimum;

    public ClientController(ClientRepository repository,
                            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder builder) {
        this.repository = repository;
        this.rest = builder.build();
    }

    @GetMapping
    public List<Client> list(@RequestParam(required = false) String q) {
        String query = q == null ? "" : q.toLowerCase(Locale.ROOT).trim();
        return repository.findAll().stream()
                .filter(c -> query.isBlank() || (c.nom() + " " + c.prenom() + " " + c.cin() + " " + c.email()).toLowerCase(Locale.ROOT).contains(query))
                .sorted(Comparator.comparing(Client::id)).toList();
    }

    @GetMapping("/{id}")
    public Client get(@PathVariable long id) { return require(id); }

    @GetMapping("/{id}/eligibilite")
    public Map<String, Object> eligibility(@PathVariable long id) {
        Client client = require(id);
        int age = Period.between(client.dateNaissance(), LocalDate.now()).getYears();
        boolean licenceValid = client.dateExpirationPermis() != null && !client.dateExpirationPermis().isBefore(LocalDate.now());
        boolean eligible = age >= ageMinimum && licenceValid;
        String reason = eligible ? "Client éligible" : age < ageMinimum
                ? "Le client doit avoir au moins " + ageMinimum + " ans" : "Le permis est expiré";
        return Map.of("eligible", eligible, "age", age, "permisValide", licenceValid, "raison", reason);
    }

    @PostMapping
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public Client create(@RequestBody ClientRequest request) {
        validate(request);
        ensureUnique(request, null);
        Client client = toClient(repository.nextId(), request, "STANDARD", LocalDate.now(), 0);
        return repository.save(client);
    }

    @PutMapping("/{id}")
    public Client update(@PathVariable long id, @RequestBody ClientRequest request) {
        Client old = require(id);
        validate(request);
        ensureUnique(request, id);
        return repository.save(toClient(id, request, old.categorieClient(), old.dateInscription(), old.locationsTerminees()));
    }

    @PatchMapping("/{id}/categorie")
    public Client setCategory(@PathVariable long id, @RequestBody CategoryRequest request) {
        Client old = require(id);
        int completed = old.locationsTerminees() + (request.locationsTermineesDelta() == null ? 0 : request.locationsTermineesDelta());
        if (completed < 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Le nombre de locations terminées ne peut pas être négatif");
        String category;
        if (request.locationsTermineesDelta() != null) {
            category = categoryFor(completed);
        } else {
            category = request.categorieClient() == null ? "" : request.categorieClient().trim().toUpperCase(Locale.ROOT);
            if (!List.of("STANDARD", "FIDELE", "VIP").contains(category)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Catégorie invalide");
            }
        }
        Client updated = new Client(old.id(), old.nom(), old.prenom(), old.cin(), old.dateNaissance(), old.email(),
                old.telephone(), old.adresse(), old.numeroPermis(), old.dateExpirationPermis(), category,
                old.dateInscription(), completed);
        return repository.save(updated);
    }

    @DeleteMapping("/{id}")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        require(id);
        if (hasActiveRental(id)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Un client avec une location active ne peut pas être supprimé");
        repository.deleteById(id);
    }

    private boolean hasActiveRental(long clientId) {
        try {
            Map<String, Object> page = rest.get()
                    .uri(uri -> uri.scheme("http").host("location-service").path("/locations")
                            .queryParam("clientId", clientId).queryParam("size", 1000).build())
                    .retrieve().body(new ParameterizedTypeReference<>() {});
            Object content = page == null ? null : page.get("content");
            if (!(content instanceof Collection<?> rows)) return false;
            return rows.stream().filter(Map.class::isInstance).map(Map.class::cast)
                    .anyMatch(row -> List.of("RESERVEE", "EN_COURS").contains(String.valueOf(row.get("statut"))));
        } catch (RestClientException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Impossible de vérifier les locations actives", error);
        }
    }

    private Client require(long id) {
        return repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Client introuvable"));
    }

    private void ensureUnique(ClientRequest request, Long exceptId) {
        boolean duplicate = repository.findAll().stream().anyMatch(c -> !c.id().equals(exceptId)
                && (c.cin().equalsIgnoreCase(request.cin().trim())
                || c.email().equalsIgnoreCase(request.email().trim())
                || c.numeroPermis().equalsIgnoreCase(request.numeroPermis().trim())));
        if (duplicate) throw new ResponseStatusException(HttpStatus.CONFLICT, "CIN, email et numéro de permis doivent être uniques");
    }

    private static void validate(ClientRequest r) {
        if (r == null || blank(r.nom()) || blank(r.prenom()) || blank(r.cin()) || blank(r.email())
                || blank(r.numeroPermis()) || r.dateNaissance() == null || r.dateExpirationPermis() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Les informations du client et du permis sont obligatoires");
        }
        if (!r.email().contains("@") || r.dateNaissance().isAfter(LocalDate.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email ou date de naissance invalide");
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static String categoryFor(int completed) {
        if (completed >= 15) return "VIP";
        if (completed >= 5) return "FIDELE";
        return "STANDARD";
    }

    private static Client toClient(long id, ClientRequest r, String category, LocalDate registered, int completed) {
        return new Client(id, r.nom().trim(), r.prenom().trim(), r.cin().trim(), r.dateNaissance(),
                r.email().trim().toLowerCase(Locale.ROOT), r.telephone(), r.adresse(), r.numeroPermis().trim(),
                r.dateExpirationPermis(), category, registered, completed);
    }

    public record ClientRequest(String nom, String prenom, String cin, LocalDate dateNaissance, String email,
                                String telephone, String adresse, String numeroPermis, LocalDate dateExpirationPermis) {}
    public record CategoryRequest(String categorieClient, Integer locationsTermineesDelta) {}
}
