package com.example.jobs.common;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class JobRepository {
    private final JdbcTemplate jdbcTemplate;

    public JobRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private Job map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Job(resultSet.getLong("id"), resultSet.getString("job_type"), resultSet.getString("payload"),
                JobStatus.valueOf(resultSet.getString("status")), resultSet.getInt("attempts"), resultSet.getInt("max_attempts"),
                resultSet.getTimestamp("run_at").toLocalDateTime(), (String) resultSet.getObject("locked_by"),
                resultSet.getTimestamp("locked_at") == null ? null : resultSet.getTimestamp("locked_at").toLocalDateTime(),
                resultSet.getString("last_error"), resultSet.getString("idempotency_key"),
                resultSet.getTimestamp("created_at").toLocalDateTime(), resultSet.getTimestamp("updated_at").toLocalDateTime());
    }

    public Optional<Job> find(long id) { return jdbcTemplate.query("select * from jobs where id=?", this::map, id).stream().findFirst(); }
    public Optional<Job> byKey(String key) { return key == null ? Optional.empty() : jdbcTemplate.query("select * from jobs where idempotency_key=?", this::map, key).stream().findFirst(); }

    public Job create(String type, String payload, LocalDateTime runAt, String key) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("insert into jobs(job_type,payload,run_at,idempotency_key,created_at,updated_at) values(?,?,?,?,UTC_TIMESTAMP(),UTC_TIMESTAMP())", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, type); statement.setString(2, payload); statement.setObject(3, runAt); statement.setString(4, key); return statement;
        }, keyHolder);
        return find(keyHolder.getKey().longValue()).orElseThrow();
    }

    public List<Job> pending() { return jdbcTemplate.query("select * from jobs where status='PENDING' and run_at<=UTC_TIMESTAMP() order by run_at,id limit 1", this::map); }
    public boolean claim(long id, String workerId) { return jdbcTemplate.update("update jobs set status='RUNNING',locked_by=?,locked_at=UTC_TIMESTAMP(),attempts=attempts+1,updated_at=UTC_TIMESTAMP() where id=? and status='PENDING'", workerId, id) == 1; }
    public void success(long id) { jdbcTemplate.update("update jobs set status='SUCCESS',updated_at=UTC_TIMESTAMP() where id=?", id); }
    public void fail(long id, String error) { jdbcTemplate.update("update jobs set status='FAILED',last_error=?,updated_at=UTC_TIMESTAMP() where id=?", error, id); }
    public List<Job> list(JobStatus status, int limit, int offset) { return jdbcTemplate.query("select * from jobs where (? is null or status=?) order by id desc limit ? offset ?", this::map, status == null ? null : status.name(), status == null ? null : status.name(), limit, offset); }
}
