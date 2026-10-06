package ru.practicum.ewm.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import ru.practicum.ewm.model.Category;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class CategoryRepositoryTest {

    @Autowired
    private CategoryRepository categoryRepository;

    @Test
    void shouldSaveAndFindCategory() {
        Category category = new Category();
        category.setName("Test Category");

        Category saved = categoryRepository.save(category);
        Category found = categoryRepository.findById(saved.getId()).orElse(null);

        assertThat(found).isNotNull();
        assertThat(found.getName()).isEqualTo("Test Category");
    }

    @Test
    void shouldCheckExistsByName() {
        Category category = new Category();
        category.setName("Unique Category");
        categoryRepository.save(category);

        assertThat(categoryRepository.existsByName("Unique Category")).isTrue();
        assertThat(categoryRepository.existsByName("Non Existing")).isFalse();
    }

    @Test
    void shouldFindAllWithPagination() {
        for (int i = 0; i < 5; i++) {
            Category category = new Category();
            category.setName("Category " + i);
            categoryRepository.save(category);
        }

        Pageable pageable = PageRequest.of(0, 3);
        var page = categoryRepository.findAll(pageable);

        assertThat(page.getContent()).hasSize(3);
        assertThat(page.getTotalElements()).isEqualTo(5);
    }
}
