package com.driveeasy.location;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Repository;

@Repository
public class LocationRepository {
    private final ConcurrentHashMap<Long, Location> data = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public Collection<Location> findAll() { return data.values(); }
    public Optional<Location> findById(long id) { return Optional.ofNullable(data.get(id)); }
    public Location save(Location location) { data.put(location.id(), location); return location; }
    public long nextId() { return sequence.incrementAndGet(); }
}
