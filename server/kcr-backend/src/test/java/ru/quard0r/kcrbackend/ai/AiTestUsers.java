package ru.quard0r.kcrbackend.ai;

import org.springframework.jdbc.core.JdbcTemplate;

final class AiTestUsers {

    private static final String USERNAME_PATTERN =
            "^ai-(op|adm|xls|ops|err|rej|out|404|tkt|ws|once|bad|down|org)-[0-9a-f]{8}$";

    private AiTestUsers() {
    }

    static void delete(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update("DELETE FROM refresh_tokens WHERE user_id IN "
                + "(SELECT id FROM users WHERE username ~ ?)", USERNAME_PATTERN);
        jdbcTemplate.update("DELETE FROM users WHERE username ~ ?", USERNAME_PATTERN);
    }
}
