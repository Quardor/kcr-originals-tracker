package ru.quard0r.kcrbackend.clients.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import ru.quard0r.kcrbackend.clients.model.CallListItem;

import java.util.List;

public interface CallListItemRepository extends JpaRepository<CallListItem, Long>
{
    @Query("SELECT c FROM CallListItem c JOIN FETCH c.client LEFT JOIN FETCH c.client.assignee JOIN FETCH c.createdBy ORDER BY c.position ASC, c.id ASC")
    List<CallListItem> findAllWithClientsOrderByPositionAsc();

    @Modifying
    @Query("DELETE FROM CallListItem")
    void deleteAllFast();
}
