package com.driveeasy.client;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

@Repository
public class ClientRepository {
    private final ConcurrentHashMap<Long, Client> data = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public ClientRepository(@Value("${driveeasy.demo-data.enabled:true}") boolean demoDataEnabled) {
        if (!demoDataEnabled) return;
        add("Ben Salem", "Amira", "09876543", LocalDate.now().minusYears(29), "amira.bensalem@example.test", "+216 20 111 222", "Tunis", "TN-100201", LocalDate.now().plusYears(3), "VIP", 16);
        add("Trabelsi", "Youssef", "11223344", LocalDate.now().minusYears(34), "youssef.trabelsi@example.test", "+216 22 333 444", "Ariana", "TN-100202", LocalDate.now().plusYears(2), "FIDELE", 7);
        add("Mansour", "Salma", "12344321", LocalDate.now().minusYears(26), "salma.mansour@example.test", "+216 24 555 666", "Sousse", "TN-100203", LocalDate.now().plusYears(1), "STANDARD", 2);
        add("Gharbi", "Nour", "22334455", LocalDate.now().minusYears(19), "nour.gharbi@example.test", "+216 50 777 888", "Tunis", "TN-100204", LocalDate.now().plusYears(2), "STANDARD", 0);
        add("Khalfallah", "Sami", "33445566", LocalDate.now().minusYears(42), "sami.khalfallah@example.test", "+216 98 123 456", "Monastir", "TN-100205", LocalDate.now().minusDays(10), "STANDARD", 1);
    }

    public Collection<Client> findAll() { return data.values(); }
    public Optional<Client> findById(long id) { return Optional.ofNullable(data.get(id)); }
    public Client save(Client client) { data.put(client.id(), client); return client; }
    public void deleteById(long id) { data.remove(id); }
    public long nextId() { return sequence.incrementAndGet(); }

    private void add(String lastName, String firstName, String cin, LocalDate birthDate, String email, String phone,
                     String address, String licence, LocalDate expiry, String category, int completed) {
        long id = nextId();
        data.put(id, new Client(id, lastName, firstName, cin, birthDate, email, phone, address, licence,
                expiry, category, LocalDate.now().minusYears(1), completed));
    }
}
