package com.driveeasy.location;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/locations")
public class LocationController {
    private final LocationRepository repository;
    private final RestClient rest;

    @Value("${driveeasy.location.coefficients.STANDARD:1.0}")
    private BigDecimal standardCoefficient;
    @Value("${driveeasy.location.coefficients.FIDELE:1.0}")
    private BigDecimal loyalCoefficient;
    @Value("${driveeasy.location.coefficients.VIP:1.0}")
    private BigDecimal vipCoefficient;
    @Value("${driveeasy.location.remise-fidele:0.05}")
    private BigDecimal loyalDiscount;
    @Value("${driveeasy.location.remise-vip:0.10}")
    private BigDecimal vipDiscount;
    @Value("${driveeasy.location.facteur-penalite-retard:1.5}")
    private BigDecimal latePenaltyFactor;

    public LocationController(LocationRepository repository,
                              @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder builder) {
        this.repository = repository;
        this.rest = builder.build();
    }

    @GetMapping
    public PageResponse<Location> list(@RequestParam(required = false) String statut,
                                       @RequestParam(required = false) Long clientId,
                                       @RequestParam(required = false) Long vehiculeId,
                                       @RequestParam(required = false) LocalDate dateDebut,
                                       @RequestParam(required = false) LocalDate dateFin,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        validatePeriodFilters(dateDebut, dateFin);
        if (page < 0 || size < 1 || size > 1000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pagination invalide");
        List<Location> matching = repository.findAll().stream()
                .filter(l -> statut == null || statut.isBlank() || l.statut().equalsIgnoreCase(statut))
                .filter(l -> clientId == null || l.clientId().equals(clientId))
                .filter(l -> vehiculeId == null || l.vehiculeId().equals(vehiculeId))
                .filter(l -> dateDebut == null || !l.dateFinPrevue().isBefore(dateDebut))
                .filter(l -> dateFin == null || !l.dateDebut().isAfter(dateFin))
                .sorted(Comparator.comparing(Location::dateCreation).reversed()).toList();
        int from = Math.min(page * size, matching.size());
        int to = Math.min(from + size, matching.size());
        return new PageResponse<>(matching.subList(from, to), page, size, matching.size(),
                matching.isEmpty() ? 0 : (int) Math.ceil((double) matching.size() / size));
    }

    @GetMapping("/{id}")
    public LocationDetails get(@PathVariable long id) {
        Location location = require(id);
        return new LocationDetails(location, getClient(location.clientId()), getVehicle(location.vehiculeId()));
    }

    @PostMapping("/simulation")
    public Simulation simulation(@RequestBody RentalRequest request) {
        Quote quote = quote(request);
        return quote.simulation();
    }

    @PostMapping
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public Location create(@RequestBody RentalRequest request) {
        Quote quote = quote(request);
        long id = repository.nextId();
        Integer currentMileage = integer(quote.vehicle().get("kilometrage"));
        Location location = new Location(id, request.clientId(), request.vehiculeId(), request.dateDebut(),
                request.dateFin(), null, currentMileage, null, quote.simulation().prixJournalierApplique(),
                quote.simulation().montantEstime(), BigDecimal.ZERO.setScale(2), "RESERVEE", LocalDateTime.now());
        return repository.save(location);
    }

    @PatchMapping("/{id}/depart")
    public Location start(@PathVariable long id) {
        Location old = require(id);
        if (!"RESERVEE".equals(old.statut())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Seule une réservation peut démarrer");
        Map<String, Object> vehicle = getVehicle(old.vehiculeId());
        if (!"DISPONIBLE".equalsIgnoreCase(String.valueOf(vehicle.get("statut")))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Le véhicule n'est plus disponible");
        }
        updateVehicleStatus(old.vehiculeId(), "LOUE");
        Integer mileage = integer(vehicle.get("kilometrage"));
        Location updated = copy(old, old.dateRetourEffective(), mileage, old.kmRetour(), old.prixJournalierApplique(),
                old.montantTotal(), old.penalite(), "EN_COURS");
        return repository.save(updated);
    }

    @PatchMapping("/{id}/retour")
    public Location returnVehicle(@PathVariable long id, @RequestBody ReturnRequest request) {
        Location old = require(id);
        if (!"EN_COURS".equals(old.statut())) throw new ResponseStatusException(HttpStatus.CONFLICT, "La location n'est pas en cours");
        if (request == null || request.kmRetour() == null || request.kmRetour() < 0
                || (old.kmDepart() != null && request.kmRetour() < old.kmDepart())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Kilométrage de retour invalide");
        }
        LocalDate returnedAt = request.dateRetour() == null ? LocalDate.now() : request.dateRetour();
        if (returnedAt.isBefore(old.dateDebut())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La date de retour précède le départ");
        long lateDays = Math.max(0, ChronoUnit.DAYS.between(old.dateFinPrevue(), returnedAt));
        BigDecimal penalty = old.prixJournalierApplique().multiply(latePenaltyFactor)
                .multiply(BigDecimal.valueOf(lateDays)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = old.montantTotal().add(penalty).setScale(2, RoundingMode.HALF_UP);

        updateClientCategory(old.clientId(), 1);
        try {
            updateVehicleStatus(old.vehiculeId(), "DISPONIBLE");
        } catch (RuntimeException error) {
            try { updateClientCategory(old.clientId(), -1); } catch (RuntimeException rollbackError) { error.addSuppressed(rollbackError); }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Le retour du véhicule a échoué; le compteur fidélité a été compensé", error);
        }

        Location updated = copy(old, returnedAt, old.kmDepart(), request.kmRetour(), old.prixJournalierApplique(),
                total, penalty, "TERMINEE");
        return repository.save(updated);
    }

    @PatchMapping("/{id}/annulation")
    public Location cancel(@PathVariable long id) {
        Location old = require(id);
        if (!"RESERVEE".equals(old.statut())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Seule une réservation peut être annulée");
        return repository.save(copy(old, null, old.kmDepart(), old.kmRetour(), old.prixJournalierApplique(),
                old.montantTotal(), old.penalite(), "ANNULEE"));
    }

    @GetMapping("/statistiques")
    public Map<String, Object> statistics() {
        LocalDate today = LocalDate.now();
        YearMonth month = YearMonth.from(today);
        LocalDate monthStart = month.atDay(1);
        LocalDate monthEnd = month.atEndOfMonth();
        List<Location> rows = new ArrayList<>(repository.findAll());
        BigDecimal revenue = rows.stream().filter(l -> "TERMINEE".equals(l.statut()))
                .filter(l -> l.dateRetourEffective() != null && YearMonth.from(l.dateRetourEffective()).equals(month))
                .map(Location::montantTotal).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        long completedThisMonth = rows.stream().filter(l -> "TERMINEE".equals(l.statut()))
                .filter(l -> l.dateRetourEffective() != null && YearMonth.from(l.dateRetourEffective()).equals(month)).count();
        long active = rows.stream().filter(l -> List.of("RESERVEE", "EN_COURS").contains(l.statut())).count();
        int fleetSize = getVehicles().size();
        long occupiedDays = rows.stream().filter(l -> !"ANNULEE".equals(l.statut()))
                .mapToLong(l -> occupiedDaysInMonth(l, monthStart, monthEnd, today)).sum();
        double capacity = (double) fleetSize * month.lengthOfMonth();
        double occupancy = capacity == 0 ? 0 : Math.round(occupiedDays * 10000.0 / capacity) / 100.0;
        List<Map<String, Object>> topVehicles = rows.stream().filter(l -> !"ANNULEE".equals(l.statut()))
                .collect(java.util.stream.Collectors.groupingBy(Location::vehiculeId, java.util.stream.Collectors.counting()))
                .entrySet().stream().sorted(Map.Entry.<Long, Long>comparingByValue().reversed()).limit(3)
                .map(entry -> Map.<String, Object>of("vehiculeId", entry.getKey(), "locations", entry.getValue())).toList();
        return Map.of("chiffreAffaires", revenue, "chiffreAffairesMois", revenue,
                "tauxOccupation", occupancy, "vehiculesLesPlusLoues", topVehicles,
                "locationsActives", active, "locationsTermineesMois", completedThisMonth,
                "locationsTotales", rows.size());
    }

    private Quote quote(RentalRequest request) {
        validateDates(request);
        Map<String, Object> client = getClient(request.clientId());
        Map<String, Object> eligibility = rest.get().uri("http://client-service/clients/{id}/eligibilite", request.clientId())
                .retrieve().body(new ParameterizedTypeReference<>() {});
        if (!Boolean.TRUE.equals(eligibility.get("eligible"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, String.valueOf(eligibility.get("raison")));
        }
        Map<String, Object> vehicle = getVehicle(request.vehiculeId());
        List<Map<String, Object>> available = rest.get()
                .uri(uri -> uri.scheme("http").host("vehicule-service").path("/vehicules/disponibles")
                        .queryParam("dateDebut", request.dateDebut()).queryParam("dateFin", request.dateFin()).build())
                .retrieve().body(new ParameterizedTypeReference<>() {});
        boolean isAvailable = available != null && available.stream()
                .anyMatch(v -> request.vehiculeId().equals(number(v.get("id"))));
        if (!isAvailable) throw new ResponseStatusException(HttpStatus.CONFLICT, "Ce véhicule n'est pas disponible sur cette période");

        String category = String.valueOf(client.get("categorieClient")).toUpperCase(Locale.ROOT);
        BigDecimal coefficient = coefficientFor(category);
        BigDecimal discount = discountFor(category);
        BigDecimal rawDaily = new BigDecimal(String.valueOf(vehicle.get("prixJournalier")));
        BigDecimal appliedDaily = rawDaily.multiply(coefficient).setScale(2, RoundingMode.HALF_UP);
        long days = ChronoUnit.DAYS.between(request.dateDebut(), request.dateFin());
        BigDecimal estimate = appliedDaily.multiply(BigDecimal.valueOf(days)).multiply(BigDecimal.ONE.subtract(discount))
                .setScale(2, RoundingMode.HALF_UP);
        return new Quote(new Simulation(request.clientId(), request.vehiculeId(), request.dateDebut(), request.dateFin(),
                days, appliedDaily, coefficient, discount, estimate), vehicle);
    }

    private BigDecimal coefficientFor(String category) {
        return switch (category) {
            case "VIP" -> vipCoefficient;
            case "FIDELE" -> loyalCoefficient;
            default -> standardCoefficient;
        };
    }

    private BigDecimal discountFor(String category) {
        return switch (category) {
            case "VIP" -> vipDiscount;
            case "FIDELE" -> loyalDiscount;
            default -> BigDecimal.ZERO;
        };
    }

    private void updateClientCategory(long clientId, int delta) {
        rest.patch().uri("http://client-service/clients/{id}/categorie", clientId)
                .body(Map.of("locationsTermineesDelta", delta)).retrieve().toBodilessEntity();
    }

    private void updateVehicleStatus(long vehicleId, String status) {
        rest.patch().uri("http://vehicule-service/vehicules/{id}/statut", vehicleId)
                .body(Map.of("statut", status)).retrieve().toBodilessEntity();
    }

    private Map<String, Object> getVehicle(long id) {
        return rest.get().uri("http://vehicule-service/vehicules/{id}", id).retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    private Map<String, Object> getClient(long id) {
        return rest.get().uri("http://client-service/clients/{id}", id).retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    private List<Map<String, Object>> getVehicles() {
        return rest.get().uri("http://vehicule-service/vehicules").retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    private Location require(long id) {
        return repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Location introuvable"));
    }

    private static Location copy(Location old, LocalDate returnDate, Integer startMileage, Integer returnMileage,
                                 BigDecimal dailyRate, BigDecimal total, BigDecimal penalty, String status) {
        return new Location(old.id(), old.clientId(), old.vehiculeId(), old.dateDebut(), old.dateFinPrevue(),
                returnDate, startMileage, returnMileage, dailyRate, total, penalty, status, old.dateCreation());
    }

    private static long occupiedDaysInMonth(Location location, LocalDate monthStart, LocalDate monthEnd, LocalDate today) {
        LocalDate start = location.dateDebut().isAfter(monthStart) ? location.dateDebut() : monthStart;
        LocalDate bookedEnd = switch (location.statut()) {
            case "TERMINEE" -> location.dateRetourEffective();
            case "EN_COURS" -> today;
            default -> location.dateFinPrevue();
        };
        if (bookedEnd == null) bookedEnd = location.dateFinPrevue();
        LocalDate end = bookedEnd.isBefore(monthEnd) ? bookedEnd : monthEnd;
        return end.isBefore(start) ? 0 : ChronoUnit.DAYS.between(start, end) + 1;
    }

    private static Long number(Object value) { return value instanceof Number n ? n.longValue() : null; }
    private static Integer integer(Object value) { return value instanceof Number n ? n.intValue() : null; }

    private static void validateDates(RentalRequest request) {
        if (request == null || request.clientId() == null || request.vehiculeId() == null
                || request.dateDebut() == null || request.dateFin() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Client, véhicule et dates sont obligatoires");
        }
        if (request.dateDebut().isBefore(LocalDate.now()) || !request.dateFin().isAfter(request.dateDebut())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La période de location est invalide");
        }
    }

    private static void validatePeriodFilters(LocalDate start, LocalDate end) {
        if ((start == null) != (end == null) || (start != null && end.isBefore(start))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La période de recherche est invalide");
        }
    }

    public record RentalRequest(Long clientId, Long vehiculeId, LocalDate dateDebut, LocalDate dateFin) {}
    public record ReturnRequest(Integer kmRetour, LocalDate dateRetour) {}
    public record Simulation(Long clientId, Long vehiculeId, LocalDate dateDebut, LocalDate dateFin, long jours,
                             BigDecimal prixJournalierApplique, BigDecimal coefficientCategorie,
                             BigDecimal remiseFidelite, BigDecimal montantEstime) {}
    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {}
    public record LocationDetails(Location location, Map<String, Object> client, Map<String, Object> vehicule) {}
    private record Quote(Simulation simulation, Map<String, Object> vehicle) {}
}
