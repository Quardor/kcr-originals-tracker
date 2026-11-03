package ru.quard0r.kcrbackend.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import ru.quard0r.kcrbackend.model.Role;
import ru.quard0r.kcrbackend.model.User;
import ru.quard0r.kcrbackend.repository.UserRepository;

@Component
public class AdminBootstrap implements ApplicationRunner {

    public static final String CREATE_ADMIN_OPTION = "create-admin";

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository userRepository;

    private final PasswordEncoder passwordEncoder;

    private final Environment environment;

    private final ApplicationContext applicationContext;

    private final String adminUsername;

    private final String adminPassword;

    public AdminBootstrap(UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          Environment environment,
                          ApplicationContext applicationContext,
                          @Value("${app.admin.username:}") String adminUsername,
                          @Value("${app.admin.password:}") String adminPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.environment = environment;
        this.applicationContext = applicationContext;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (args.containsOption(CREATE_ADMIN_OPTION)) {
            createAdminForced();
            exitAfterAdminCreation();
            return;
        }
        bootstrapIfNeeded();
    }

    void exitAfterAdminCreation() {
        System.exit(SpringApplication.exit(applicationContext));
    }

    private void bootstrapIfNeeded() {
        if (userRepository.existsByRole(Role.ADMIN)) {
            log.info("Admin bootstrap skipped: ADMIN user(s) already exist");
            return;
        }
        if (isBlank(adminUsername) || isBlank(adminPassword)) {
            if (environment.acceptsProfiles(Profiles.of("prod"))) {
                throw new IllegalStateException(
                        "No ADMIN user exists and ADMIN_USERNAME/ADMIN_PASSWORD are not set: "
                                + "provide them via environment, the first administrator "
                                + "cannot be created otherwise");
            }
            log.warn("No ADMIN user exists; ADMIN_USERNAME/ADMIN_PASSWORD not set, "
                    + "bootstrap skipped (dev profile)");
            return;
        }
        createAdmin(adminUsername, adminPassword);
        log.info("First ADMIN account created for user '{}'", adminUsername);
    }

    private void createAdminForced() {
        if (isBlank(adminUsername) || isBlank(adminPassword)) {
            throw new IllegalStateException(
                    "--create-admin requires ADMIN_USERNAME and ADMIN_PASSWORD to be set");
        }
        createAdmin(adminUsername, adminPassword);
        log.info("ADMIN account '{}' created (--create-admin)", adminUsername);
    }

    private void createAdmin(String username, String rawPassword) {
        if (userRepository.existsByUsername(username)) {
            throw new IllegalStateException("User '" + username + "' already exists: "
                    + "choose another ADMIN_USERNAME");
        }
        userRepository.save(User.builder()
                .username(username)
                .password(passwordEncoder.encode(rawPassword))
                .role(Role.ADMIN)
                .build());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
