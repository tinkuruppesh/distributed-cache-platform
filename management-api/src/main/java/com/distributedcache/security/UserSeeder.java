package com.distributedcache.security;

import com.distributedcache.persistence.UserAccount;
import com.distributedcache.persistence.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Creates the three initial accounts (admin / operator / viewer) if they do not exist yet. */
@Component
public class UserSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(UserSeeder.class);
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final SecurityProperties props;

    public UserSeeder(UserRepository users, PasswordEncoder encoder, SecurityProperties props) {
        this.users = users; this.encoder = encoder; this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed("admin", props.getAdminPassword(), Role.ADMIN);
        seed("operator", props.getOperatorPassword(), Role.OPERATOR);
        seed("viewer", props.getViewerPassword(), Role.VIEWER);
        if ("Admin@12345".equals(props.getAdminPassword()))
            log.warn("Default admin password is in use. Set ADMIN_PASSWORD before exposing this service.");
    }

    private void seed(String username, String password, Role role) {
        if (users.findByUsername(username).isPresent()) return;
        UserAccount u = new UserAccount();
        u.username = username;
        u.passwordHash = encoder.encode(password);
        u.role = role.name();
        u.enabled = true;
        u.createdAt = u.updatedAt = System.currentTimeMillis();
        users.save(u);
    }
}
