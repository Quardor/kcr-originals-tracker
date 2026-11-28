package ru.quard0r.kcrbackend;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import ru.quard0r.kcrbackend.clients.events.ClientEvent;
import ru.quard0r.kcrbackend.clients.events.ClientEventType;
import ru.quard0r.kcrbackend.clients.model.Client;
import ru.quard0r.kcrbackend.snapshots.model.DocumentTransferSnapshot;
import ru.quard0r.kcrbackend.users.model.Role;
import ru.quard0r.kcrbackend.users.model.User;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class IdGenerationIntegrationTest
{
    private static final int HIBERNATE_POOL_SIZE = 50;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User author;

    private Client anchor;

    @BeforeEach
    void createReferences()
    {
        author = User.builder()
                .username("idgen-author")
                .password("idgen")
                .role(Role.ADMIN)
                .build();
        anchor = Client.builder()
                .kcrCode("IDG80000")
                .inn("123456789012")
                .organizationName("Id generation")
                .build();
        entityManager.persist(author);
        entityManager.persist(anchor);
        entityManager.flush();
    }

    @Test
    void idColumnsAreFedByTheirSequencesOnly()
    {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                "SELECT table_name, is_identity, column_default FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND column_name = 'id' "
                        + "AND table_name IN ('clients', 'client_events', 'document_transfer_snapshots')");

        assertEquals(3, columns.size());
        for (Map<String, Object> column : columns)
        {
            String table = (String) column.get("table_name");
            assertEquals("NO", column.get("is_identity"), table);
            assertTrue(((String) column.get("column_default")).startsWith("nextval("), table);
        }
    }

    @Test
    void clientIdsOfRawInsertsAndHibernateNeverCollide()
    {
        assertSingleIdGenerator(
                raw -> "INSERT INTO clients (kcr_code, inn, organization_name) "
                        + "VALUES ('IDG9000" + raw + "', '123456789012', 'Id generation')",
                row -> Client.builder()
                        .kcrCode("IDG" + String.format("%05d", row))
                        .inn("123456789012")
                        .organizationName("Id generation")
                        .build());
    }

    @Test
    void clientEventIdsOfRawInsertsAndHibernateNeverCollide()
    {
        assertSingleIdGenerator(
                raw -> "INSERT INTO client_events (client_id, author_id, created_at, type) "
                        + "VALUES (" + anchor.getId() + ", " + author.getId() + ", now(), 'COMMENT')",
                row -> ClientEvent.builder()
                        .client(anchor)
                        .author(author)
                        .type(ClientEventType.COMMENT)
                        .comment("Id generation")
                        .build());
    }

    @Test
    void snapshotIdsOfRawInsertsAndHibernateNeverCollide()
    {
        assertSingleIdGenerator(
                raw -> "INSERT INTO document_transfer_snapshots "
                        + "(client_id, reporting_period, snapshot_date, not_transferred_documents) "
                        + "VALUES (" + anchor.getId() + ", '2026', '2026-01-01', 1)",
                row -> DocumentTransferSnapshot.builder()
                        .client(anchor)
                        .reportingPeriod("2026")
                        .snapshotDate(LocalDate.of(2026, 1, 1))
                        .notTransferredDocuments(1)
                        .build());
    }

    private void assertSingleIdGenerator(IntFunction<String> rawInsertSql, IntFunction<?> rowFactory)
    {
        Set<Long> ids = new HashSet<>();

        ids.add(insertRaw(rawInsertSql.apply(0)));
        ids.addAll(persistThroughHibernate(rowFactory, 0, HIBERNATE_POOL_SIZE + 1));
        ids.add(insertRaw(rawInsertSql.apply(1)));
        ids.addAll(persistThroughHibernate(rowFactory, HIBERNATE_POOL_SIZE + 1, 2 * HIBERNATE_POOL_SIZE + 2));
        ids.add(insertRaw(rawInsertSql.apply(2)));

        assertEquals(3 + 2 * (HIBERNATE_POOL_SIZE + 1), ids.size());
    }

    private Long insertRaw(String sql)
    {
        return jdbcTemplate.queryForObject(sql + " RETURNING id", Long.class);
    }

    private List<Long> persistThroughHibernate(IntFunction<?> rowFactory, int fromInclusive, int toExclusive)
    {
        List<Object> entities = new ArrayList<>();
        for (int row = fromInclusive; row < toExclusive; row++)
        {
            Object entity = rowFactory.apply(row);
            entityManager.persist(entity);
            entities.add(entity);
        }
        entityManager.flush();

        List<Long> ids = new ArrayList<>();
        for (Object entity : entities)
        {
            ids.add((Long) entityManager.getEntityManagerFactory()
                    .getPersistenceUnitUtil()
                    .getIdentifier(entity));
        }
        return ids;
    }
}
