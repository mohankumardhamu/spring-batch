package com.example.batchservice.batch;

import com.example.batchservice.client.UserClient;
import com.example.batchservice.config.PropagationProperties;
import com.example.batchservice.entity.BulkCursor;
import com.example.batchservice.entity.PropagationRequest;
import com.example.batchservice.repository.PropagationRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Step 1 of bulkPropagateUsersJob: claims the next N users that have not been propagated yet.
 * <ol>
 *   <li>Locks the bulk cursor row (SELECT ... FOR UPDATE), so concurrent bulk requests queue here and
 *       never claim the same users.</li>
 *   <li>Pages through user-service's {@code GET /api/users/ids?afterId=} (keyset pagination) from the cursor.</li>
 *   <li>Skips users that already have a SUCCESS or PENDING item from any request (e.g. propagated manually).</li>
 *   <li>Inserts one PENDING item per user with userId/email already filled in, so the propagate step
 *       skips the user lookup.</li>
 *   <li>Moves the cursor to the last examined user id and stores the real item count on the request.</li>
 * </ol>
 * Runs in the step's transaction: if anything fails, no items are inserted and the cursor doesn't move.
 */
@Component
public class BulkUserSelectionTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(BulkUserSelectionTasklet.class);
    private static final String INSERT_ITEM_SQL = """
            INSERT INTO propagation_item (request_id, identifier_type, identifier, status, user_id, user_email)
            VALUES (?, 'USER_ID', ?, 'PENDING', ?, ?)""";

    private final UserClient userClient;
    private final JdbcTemplate jdbcTemplate;
    private final PropagationRequestRepository requestRepository;
    private final int pageSize;

    public BulkUserSelectionTasklet(UserClient userClient, JdbcTemplate jdbcTemplate,
                                    PropagationRequestRepository requestRepository, PropagationProperties props) {
        this.userClient = userClient;
        this.jdbcTemplate = jdbcTemplate;
        this.requestRepository = requestRepository;
        this.pageSize = props.bulk().selectionPageSize();
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        Long requestId = (Long) chunkContext.getStepContext().getJobParameters().get(PropagationJobConfig.REQUEST_ID_PARAM);
        PropagationRequest request = requestRepository.findById(requestId).orElseThrow();
        int wanted = request.getRequestedCount();

        jdbcTemplate.update("INSERT INTO bulk_propagation_cursor (id, last_user_id) VALUES (?, 0) ON CONFLICT (id) DO NOTHING",
                BulkCursor.SINGLETON_ID);
        long cursor = jdbcTemplate.queryForObject(
                "SELECT last_user_id FROM bulk_propagation_cursor WHERE id = ? FOR UPDATE", Long.class,
                BulkCursor.SINGLETON_ID);
        long startCursor = cursor;

        int selected = 0;
        int skipped = 0;
        while (selected < wanted) {
            List<UserClient.UserKey> page = userClient.getKeysAfter(cursor, Math.min(pageSize, wanted - selected));
            if (page.isEmpty()) {
                break; // reached the end of the users table
            }
            Set<Long> alreadyPropagated = alreadyPropagated(page);
            List<Object[]> rows = new ArrayList<>(page.size());
            for (UserClient.UserKey user : page) {
                if (selected + rows.size() >= wanted) {
                    break;
                }
                cursor = user.id();
                if (alreadyPropagated.contains(user.id())) {
                    skipped++;
                    continue;
                }
                rows.add(new Object[]{requestId, user.id().toString(), user.id(), user.email()});
            }
            jdbcTemplate.batchUpdate(INSERT_ITEM_SQL, rows);
            selected += rows.size();
            contribution.incrementWriteCount(rows.size());
        }

        jdbcTemplate.update("UPDATE bulk_propagation_cursor SET last_user_id = ? WHERE id = ?", cursor,
                BulkCursor.SINGLETON_ID);
        requestRepository.updateTotalItems(requestId, selected);
        log.info("Bulk request {}: selected {} of {} requested users (ids {}..{}, skipped {} already propagated)",
                requestId, selected, wanted, startCursor + 1, cursor, skipped);
        return RepeatStatus.FINISHED;
    }

    private Set<Long> alreadyPropagated(List<UserClient.UserKey> page) {
        Long[] ids = page.stream().map(UserClient.UserKey::id).toArray(Long[]::new);
        List<Long> found = jdbcTemplate.query(con -> {
            var ps = con.prepareStatement("SELECT DISTINCT user_id FROM propagation_item "
                    + "WHERE user_id = ANY(?) AND status IN ('SUCCESS', 'PENDING')");
            Array array = con.createArrayOf("bigint", ids);
            ps.setArray(1, array);
            return ps;
        }, (rs, n) -> rs.getLong(1));
        return new HashSet<>(found);
    }
}
