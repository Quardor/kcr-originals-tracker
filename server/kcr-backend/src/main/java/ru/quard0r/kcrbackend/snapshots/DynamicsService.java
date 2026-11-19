package ru.quard0r.kcrbackend.snapshots;

import org.springframework.stereotype.Service;
import ru.quard0r.kcrbackend.clients.model.Client;
import ru.quard0r.kcrbackend.snapshots.dto.ClientDebtInfo;
import ru.quard0r.kcrbackend.snapshots.model.DocumentTransferSnapshot;
import ru.quard0r.kcrbackend.snapshots.model.DynamicsCategory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;

/**
 * Детерминированный расчёт категорий динамики срезов клиента (§11.2 ARCHITECTURE.md).
 * Правила вычисления зеркалят ТЗ:
 * 1. not_transferred == 0                           -> NO_DEBT
 * 2. подключение < 6 месяцев до даты среза (долг > 0)-> NEWCOMER_RISK
 * 3. долг был ранее и был полностью погашен         -> REAPPEARED
 * 4. долга не было ни в одном более раннем срезе    -> FIRST_OCCURRENCE
 * 5. долг уменьшился по сравнению с предыдущим      -> DECREASING
 * 6. долг сохраняется из прошлых отчётных периодов  -> PERSISTENT
 * 7. иначе                                          -> UNCHANGED_OR_GROWING
 */
@Service
public class DynamicsService
{
    private static final int NEWCOMER_MONTHS = 6;

    private static final Comparator<DocumentTransferSnapshot> SNAPSHOT_ORDER =
            Comparator.comparing(DocumentTransferSnapshot::getSnapshotDate)
                    .thenComparing(DocumentTransferSnapshot::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    /**
     * Вычислить категорию динамики для последнего среза в переданном упорядоченном префиксе.
     */
    public DynamicsCategory classifySnapshot(List<DocumentTransferSnapshot> prefix, Instant clientCreatedAt)
    {
        if (prefix == null || prefix.isEmpty())
        {
            return DynamicsCategory.NO_DEBT;
        }

        DocumentTransferSnapshot current = prefix.get(prefix.size() - 1);
        if (current.getNotTransferredDocuments() == null || current.getNotTransferredDocuments() == 0)
        {
            return DynamicsCategory.NO_DEBT;
        }

        if (isNewcomer(clientCreatedAt, current.getSnapshotDate()))
        {
            return DynamicsCategory.NEWCOMER_RISK;
        }

        List<DocumentTransferSnapshot> earlier = prefix.subList(0, prefix.size() - 1);
        boolean seenDebt = false;
        boolean hadZeroAfterDebt = false;
        boolean debtInEarlierPeriod = false;

        for (DocumentTransferSnapshot snapshot : earlier)
        {
            int debt = snapshot.getNotTransferredDocuments() != null ? snapshot.getNotTransferredDocuments() : 0;
            if (debt > 0)
            {
                seenDebt = true;
                if (!snapshot.getReportingPeriod().equalsIgnoreCase(current.getReportingPeriod()))
                {
                    debtInEarlierPeriod = true;
                }
            }
            else if (seenDebt)
            {
                hadZeroAfterDebt = true;
            }
        }

        if (seenDebt && hadZeroAfterDebt)
        {
            return DynamicsCategory.REAPPEARED;
        }
        if (!seenDebt)
        {
            return DynamicsCategory.FIRST_OCCURRENCE;
        }

        DocumentTransferSnapshot previous = earlier.get(earlier.size() - 1);
        int prevDebt = previous.getNotTransferredDocuments() != null ? previous.getNotTransferredDocuments() : 0;
        int currentDebt = current.getNotTransferredDocuments();

        if (currentDebt < prevDebt)
        {
            return DynamicsCategory.DECREASING;
        }
        if (debtInEarlierPeriod)
        {
            return DynamicsCategory.PERSISTENT;
        }

        return DynamicsCategory.UNCHANGED_OR_GROWING;
    }

    /**
     * Вычислить сводную долговую информацию и текущую категорию динамики клиента.
     */
    public ClientDebtInfo getClientDebtInfo(Client client, List<DocumentTransferSnapshot> snapshots)
    {
        if (snapshots == null || snapshots.isEmpty())
        {
            return ClientDebtInfo.empty();
        }

        List<DocumentTransferSnapshot> sorted = snapshots.stream()
                .sorted(SNAPSHOT_ORDER)
                .toList();

        DocumentTransferSnapshot latest = sorted.get(sorted.size() - 1);
        DynamicsCategory category = classifySnapshot(sorted, client != null ? client.getCreatedAt() : null);

        Integer previousNotTransferred = null;
        if (sorted.size() > 1)
        {
            previousNotTransferred = sorted.get(sorted.size() - 2).getNotTransferredDocuments();
        }

        Double percentNotTransferred = calculatePercent(latest.getTotalDocuments(), latest.getNotTransferredDocuments());

        return ClientDebtInfo.builder()
                .totalDocuments(latest.getTotalDocuments())
                .notTransferredDocuments(latest.getNotTransferredDocuments())
                .percentNotTransferred(percentNotTransferred)
                .dynamicsCategory(category)
                .previousNotTransferred(previousNotTransferred)
                .reportingPeriod(latest.getReportingPeriod())
                .snapshotDate(latest.getSnapshotDate())
                .build();
    }

    public static Double calculatePercent(Integer total, Integer notTransferred)
    {
        if (total == null || total <= 0 || notTransferred == null)
        {
            return null;
        }
        return BigDecimal.valueOf(notTransferred * 100.0)
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    public static boolean isNewcomer(Instant clientCreatedAt, LocalDate snapshotDate)
    {
        if (clientCreatedAt == null || snapshotDate == null)
        {
            return false;
        }
        LocalDate createdDate = clientCreatedAt.atZone(ZoneOffset.UTC).toLocalDate();
        return monthsBetween(createdDate, snapshotDate) < NEWCOMER_MONTHS;
    }

    public static int monthsBetween(LocalDate start, LocalDate end)
    {
        int months = (end.getYear() - start.getYear()) * 12 + (end.getMonthValue() - start.getMonthValue());
        if (end.getDayOfMonth() < start.getDayOfMonth())
        {
            months--;
        }
        return Math.max(0, months);
    }
}
