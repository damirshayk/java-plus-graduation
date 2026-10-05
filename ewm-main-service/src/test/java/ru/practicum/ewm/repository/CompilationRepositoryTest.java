package ru.practicum.ewm.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import ru.practicum.ewm.model.Compilation;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class CompilationRepositoryTest {

    @Autowired
    private CompilationRepository compilationRepository;

    @Test
    void shouldSaveAndFindCompilation() {
        Compilation compilation = Compilation.builder()
                .title("Test Compilation")
                .isPinned(true)
                .build();

        Compilation saved = compilationRepository.save(compilation);
        Compilation found = compilationRepository.findById(saved.getId()).orElse(null);

        assertThat(found).isNotNull();
        assertThat(found.getTitle()).isEqualTo("Test Compilation");
        assertThat(found.getIsPinned()).isTrue();
    }

    @Test
    void shouldCheckExistsByTitle() {
        Compilation compilation = Compilation.builder()
                .title("Unique Compilation")
                .isPinned(false)
                .build();
        compilationRepository.save(compilation);

        assertThat(compilationRepository.existsByTitle("Unique Compilation")).isTrue();
        assertThat(compilationRepository.existsByTitle("Non Existing")).isFalse();
    }

    @Test
    void shouldFindAllByPinned() {
        for (int i = 0; i < 3; i++) {
            Compilation compilation = Compilation.builder()
                    .title("isPinned " + i)
                    .isPinned(true)
                    .build();
            compilationRepository.save(compilation);
        }

        for (int i = 0; i < 2; i++) {
            Compilation compilation = Compilation.builder()
                    .title("Not isPinned " + i)
                    .isPinned(false)
                    .build();
            compilationRepository.save(compilation);
        }

        Pageable pageable = PageRequest.of(0, 10);
        var isPinned = compilationRepository.findAllByIsPinned(true, pageable);
        var notPinned = compilationRepository.findAllByIsPinned(false, pageable);

        assertThat(isPinned).hasSize(3);
        assertThat(notPinned).hasSize(2);
    }
}
