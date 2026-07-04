package com.morawski.dev.falcon;

import com.morawski.dev.falcon.user.User;
import com.morawski.dev.falcon.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class UserRepositoryTest {

	@Autowired
	private UserRepository userRepository;

	@AfterEach
	void cleanUp() {
		userRepository.deleteAll();
	}

	@Test
	void savesAndFindsUserByEmail() {
		User user = userRepository.save(new User("person@example.com", "hashed-password", Instant.now()));

		Optional<User> found = userRepository.findByEmail("person@example.com");

		assertThat(found).isPresent();
		assertThat(found.get().getId()).isEqualTo(user.getId());
		assertThat(found.get().getPasswordHash()).isEqualTo("hashed-password");
	}

	@Test
	void existsByEmailReflectsPersistedState() {
		assertThat(userRepository.existsByEmail("nobody@example.com")).isFalse();

		userRepository.save(new User("nobody@example.com", "hashed-password", Instant.now()));

		assertThat(userRepository.existsByEmail("nobody@example.com")).isTrue();
	}

	@Test
	void duplicateEmailViolatesUniqueConstraint() {
		userRepository.saveAndFlush(new User("duplicate@example.com", "hashed-password", Instant.now()));

		assertThatThrownBy(() ->
				userRepository.saveAndFlush(new User("duplicate@example.com", "another-hash", Instant.now())))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

}
