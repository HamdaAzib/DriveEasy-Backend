package com.driveeasy.vehicule;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

@Repository
public class VehiculeRepository {
    private final ConcurrentHashMap<Long, Vehicule> data = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public VehiculeRepository(@Value("${driveeasy.demo-data.enabled:true}") boolean demoDataEnabled) {
        if (!demoDataEnabled) return;
        add("197 TU 2481", "Toyota", "Yaris", 2023, "Blanche", "ESSENCE", "AUTOMATIQUE", 5, 18000, 95, "ECONOMIQUE", "DISPONIBLE");
        add("198 TU 7320", "Renault", "Clio", 2022, "Grise", "ESSENCE", "MANUELLE", 5, 26000, 90, "ECONOMIQUE", "DISPONIBLE");
        add("201 TU 4418", "Peugeot", "208", 2024, "Bleue", "ESSENCE", "AUTOMATIQUE", 5, 11000, 125, "COMPACTE", "DISPONIBLE");
        add("189 TU 6302", "Hyundai", "i20", 2021, "Noire", "DIESEL", "MANUELLE", 5, 39000, 110, "COMPACTE", "LOUE");
        add("205 TU 1093", "Volkswagen", "Golf 8", 2023, "Grise", "DIESEL", "AUTOMATIQUE", 5, 22000, 190, "BERLINE", "DISPONIBLE");
        add("203 TU 8751", "Kia", "Sportage", 2024, "Verte", "HYBRIDE", "AUTOMATIQUE", 5, 9000, 240, "SUV", "DISPONIBLE");
        add("194 TU 2207", "Dacia", "Duster", 2022, "Blanche", "DIESEL", "MANUELLE", 5, 31000, 175, "SUV", "DISPONIBLE");
        add("187 TU 5196", "Fiat", "500", 2020, "Rouge", "ESSENCE", "MANUELLE", 4, 52000, 85, "CITADINE", "MAINTENANCE");
    }

    public Collection<Vehicule> findAll() { return data.values(); }
    public Optional<Vehicule> findById(long id) { return Optional.ofNullable(data.get(id)); }
    public Vehicule save(Vehicule vehicule) { data.put(vehicule.id(), vehicule); return vehicule; }
    public void deleteById(long id) { data.remove(id); }
    public long nextId() { return sequence.incrementAndGet(); }

    private void add(String plate, String brand, String model, int year, String color, String fuel, String gearbox,
                     int seats, int mileage, int price, String category, String status) {
        long id = nextId();
        data.put(id, new Vehicule(id, plate, brand, model, year, color, fuel, gearbox, seats, mileage,
                BigDecimal.valueOf(price), category, status, null));
    }
}
