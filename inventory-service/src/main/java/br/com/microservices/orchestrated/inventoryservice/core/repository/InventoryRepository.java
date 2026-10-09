package br.com.microservices.orchestrated.inventoryservice.core.repository;

import br.com.microservices.orchestrated.inventoryservice.core.model.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface InventoryRepository extends JpaRepository<Inventory, Integer> {
    Optional<Inventory> findByProductCode(String productCode);

    @Modifying(clearAutomatically = true)
    @Query("update Inventory i set i.available = i.available - :quantity where i.id = :id and i.available >= :quantity")
    int decrementIfAvailable(@Param("id") Integer id, @Param("quantity") int quantity);

    @Modifying(clearAutomatically = true)
    @Query("update Inventory i set i.available = i.available + :quantity where i.id = :id")
    int increment(@Param("id") Integer id, @Param("quantity") int quantity);
}
