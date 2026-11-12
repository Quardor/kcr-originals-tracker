package ru.quard0r.kcrbackend.users;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import ru.quard0r.kcrbackend.users.model.Role;
import ru.quard0r.kcrbackend.users.model.User;
import ru.quard0r.kcrbackend.users.repository.UserRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminBootstrapTest {

    private static final String USERNAME = "admin";
    private static final String PASSWORD = "raw-admin-pass";
    private static final String ENCODED = "{bcrypt}encoded-pw";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final ApplicationContext applicationContext = mock(ApplicationContext.class);

    @Test
    void bootstrapSkippedWhenAdminExists() {
        when(userRepository.existsByRole(Role.ADMIN)).thenReturn(true);
        AdminBootstrap bootstrap = bootstrap(new MockEnvironment(), USERNAME, PASSWORD);

        bootstrap.run(new DefaultApplicationArguments());

        verify(userRepository).existsByRole(Role.ADMIN);
        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void createsFirstAdminFromProperties() {
        when(userRepository.existsByRole(Role.ADMIN)).thenReturn(false);
        when(passwordEncoder.encode(PASSWORD)).thenReturn(ENCODED);
        AdminBootstrap bootstrap = bootstrap(new MockEnvironment(), USERNAME, PASSWORD);

        bootstrap.run(new DefaultApplicationArguments());

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User user = captor.getValue();
        assertEquals(USERNAME, user.getUsername());
        assertEquals(ENCODED, user.getPassword());
        assertEquals(Role.ADMIN, user.getRole());
        assertNull(user.getEmail());
    }

    @Test
    void devSkipsWhenCredentialsMissing() {
        when(userRepository.existsByRole(Role.ADMIN)).thenReturn(false);
        AdminBootstrap bootstrap = bootstrap(new MockEnvironment(), "", "");

        assertDoesNotThrow(() -> bootstrap.run(new DefaultApplicationArguments()));

        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void prodFailsFastWhenCredentialsMissing() {
        when(userRepository.existsByRole(Role.ADMIN)).thenReturn(false);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        AdminBootstrap bootstrap = bootstrap(environment, "", "");

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> bootstrap.run(new DefaultApplicationArguments()));

        assertTrue(exception.getMessage().contains("ADMIN_USERNAME"));
        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void failsWhenUsernameTaken() {
        when(userRepository.existsByRole(Role.ADMIN)).thenReturn(false);
        when(userRepository.existsByUsername(USERNAME)).thenReturn(true);
        AdminBootstrap bootstrap = bootstrap(new MockEnvironment(), USERNAME, PASSWORD);

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> bootstrap.run(new DefaultApplicationArguments()));

        assertTrue(exception.getMessage().contains(USERNAME));
        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void forcedModeCreatesAdminEvenIfAdminsExist() {
        when(userRepository.existsByRole(Role.ADMIN)).thenReturn(true);
        when(passwordEncoder.encode(PASSWORD)).thenReturn(ENCODED);
        AdminBootstrap bootstrap = spy(bootstrap(new MockEnvironment(), USERNAME, PASSWORD));
        doNothing().when(bootstrap).exitAfterAdminCreation();

        bootstrap.run(new DefaultApplicationArguments("--" + AdminBootstrap.CREATE_ADMIN_OPTION));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertEquals(Role.ADMIN, captor.getValue().getRole());
        assertEquals(USERNAME, captor.getValue().getUsername());
    }

    @Test
    void forcedModeFailsWithoutCredentials() {
        AdminBootstrap bootstrap = bootstrap(new MockEnvironment(), "", "");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> bootstrap.run(new DefaultApplicationArguments("--" + AdminBootstrap.CREATE_ADMIN_OPTION)));

        assertTrue(exception.getMessage().contains(AdminBootstrap.CREATE_ADMIN_OPTION));
        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private AdminBootstrap bootstrap(Environment environment, String username, String password) {
        return new AdminBootstrap(userRepository, passwordEncoder, environment, applicationContext, username, password);
    }
}
