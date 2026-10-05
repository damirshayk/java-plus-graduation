package ru.practicum.ewm.mapper;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import ru.practicum.ewm.dto.category.CategoryDto;
import ru.practicum.ewm.dto.category.NewCategoryDto;
import ru.practicum.ewm.model.Category;

import static org.assertj.core.api.Assertions.assertThat;

class CategoryMapperTest {

    private final CategoryMapper mapper = Mappers.getMapper(CategoryMapper.class);

    @Test
    void shouldMapToDto() {
        Category category = new Category();
        category.setId(1L);
        category.setName("Test Category");

        CategoryDto dto = mapper.toDto(category);

        assertThat(dto).isNotNull();
        assertThat(dto.getId()).isEqualTo(1L);
        assertThat(dto.getName()).isEqualTo("Test Category");
    }

    @Test
    void shouldMapToCategory() {
        NewCategoryDto dto = new NewCategoryDto();
        dto.setName("Test Category");

        Category category = mapper.toCategory(dto);

        assertThat(category).isNotNull();
        assertThat(category.getId()).isNull();
        assertThat(category.getName()).isEqualTo("Test Category");
    }

    @Test
    void shouldUpdateCategory() {
        Category category = new Category();
        category.setId(1L);
        category.setName("Old Name");

        CategoryDto dto = new CategoryDto();
        dto.setName("New Name");

        mapper.updateCategory(category, dto);

        assertThat(category.getId()).isEqualTo(1L);
        assertThat(category.getName()).isEqualTo("New Name");
    }

    @Test
    void shouldIgnoreNullValuesWhenUpdating() {
        Category category = new Category();
        category.setId(1L);
        category.setName("Old Name");

        CategoryDto dto = new CategoryDto();

        mapper.updateCategory(category, dto);

        assertThat(category.getId()).isEqualTo(1L);
        assertThat(category.getName()).isEqualTo("Old Name");
    }
}
