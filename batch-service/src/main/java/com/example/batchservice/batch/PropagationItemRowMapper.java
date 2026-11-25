package com.example.batchservice.batch;

import com.example.batchservice.entity.IdentifierType;
import com.example.batchservice.entity.ItemStatus;
import com.example.batchservice.entity.PropagationItem;
import com.example.batchservice.entity.Stage;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;

final class PropagationItemRowMapper implements RowMapper<PropagationItem> {

    @Override
    public PropagationItem mapRow(ResultSet rs, int rowNum) throws SQLException {
        String failedStage = rs.getString("failed_stage");
        OffsetDateTime processedAt = rs.getObject("processed_at", OffsetDateTime.class);
        return PropagationItem.restore(
                rs.getLong("id"),
                rs.getLong("request_id"),
                IdentifierType.valueOf(rs.getString("identifier_type")),
                rs.getString("identifier"),
                ItemStatus.valueOf(rs.getString("status")),
                rs.getObject("user_id", Long.class),
                rs.getString("user_email"),
                rs.getObject("order_id", Long.class),
                rs.getBigDecimal("order_amount"),
                rs.getObject("payment_id", Long.class),
                rs.getString("payment_status"),
                rs.getString("transaction_ref"),
                failedStage == null ? null : Stage.valueOf(failedStage),
                rs.getString("error_message"),
                processedAt == null ? null : processedAt.toInstant());
    }
}
